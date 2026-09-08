#!/usr/bin/env python3
"""Reproducible PTY benchmark for TUInk's native render loop."""

from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import pty
import re
import selectors
import signal
import shutil
import statistics
import struct
import subprocess
import sys
import tempfile
import termios
import time
import tty


PROFILE_PATTERN = re.compile(
    r"^frame=(?P<frame>\d+) "
    r"build=(?P<build>\d+(?:\.\d+)?)ms "
    r"raster=(?P<raster>\d+(?:\.\d+)?)ms "
    r"diff=(?P<diff>\d+(?:\.\d+)?)ms "
    r"search=(?P<search>\d+(?:\.\d+)?)ms "
    r"total=(?P<total>\d+(?:\.\d+)?)ms$"
)
PROFILE_METRICS = ("build", "raster", "diff", "search", "total")
DEFAULT_SCENARIOS = ("down", "shift-down", "mouse-drag")
SCENARIOS = DEFAULT_SCENARIOS + (
    "save-as-left",
    "search-replace-left",
    "tabbar-wheel",
    "open-file-tree",
)
MOUSE_SCENARIOS = ("mouse-drag", "tabbar-wheel")
SAVE_AS_STRESS_TEXT = ("draft-segment-" * 128 + "中😀" * 64).encode("utf-8")
SEARCH_QUERY = b"__tuink_missing_search__"
SEARCH_REPLACE_STRESS_TEXT = ("replace-segment-" * 128 + "中😀" * 64).encode("utf-8")
OPEN_FILE_TREE_ENTRY_COUNT = 360
OPEN_FILE_TREE_FIRST_TOKEN = b"tree-row-000"
OPEN_FILE_TREE_TIMESTAMP_NS = 946684800_000_000_000


def create_settings_fixture(path: Path) -> None:
    path.write_text(
        "\n".join(
            (
                "format=1",
                "ui.showTabBar=true",
                "ui.wrapLines=false",
                "ui.wrapWords=true",
                "ui.readingMode=false",
                "ui.confirmOnClose=true",
                "ui.mouseMode=true",
                "ui.highlightActiveLineNumber=true",
                "ui.smartHomeEnd=true",
                "ui.autoIndent=true",
                "ui.autoPairs=true",
                "ui.autoList=true",
                "ui.visibleWhitespace=true",
                "ui.syntaxHighlighting=true",
                "ui.rainbowBrackets=true",
                "ui.autoSelectionHighlight=true",
                "ui.gutterDiffMode=myers",
                "ui.tabWidth=4",
                "ui.tabInsertMode=auto",
                "ui.theme=cyan",
            )
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        raise ValueError("percentile requires at least one value")
    ordered = sorted(values)
    position = (len(ordered) - 1) * quantile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def distribution(values: list[float]) -> dict[str, float]:
    if not values:
        raise ValueError("distribution requires at least one value")
    return {
        "mean": statistics.fmean(values),
        "p50": statistics.median(values),
        "p95": percentile(values, 0.95),
        "max": max(values),
    }


def parse_profile(path: Path) -> list[dict[str, float | int]]:
    records: list[dict[str, float | int]] = []
    with path.open("r", encoding="utf-8") as stream:
        for line_number, raw_line in enumerate(stream, start=1):
            line = raw_line.strip()
            if not line:
                continue
            match = PROFILE_PATTERN.fullmatch(line)
            if match is None:
                raise ValueError(f"invalid profile record at {path}:{line_number}: {line}")
            record: dict[str, float | int] = {"frame": int(match.group("frame"))}
            for metric in PROFILE_METRICS:
                record[metric] = float(match.group(metric))
            records.append(record)
    return records


def create_fixture(path: Path, line_count: int) -> None:
    lines = [
        "public final class RenderBenchmarkFixture {",
        "    private static final String NAME = \"tuink-render-benchmark\";",
    ]
    for index in range(line_count):
        padding = "x" * ((index * 17) % 91)
        indent = "\t" if index % 11 == 0 else "    "
        lines.append(
            f"{indent}static int renderLine{index:04d}(int input) {{ "
            f"String text = \"line-{index:04d}-{padding}\"; "
            f"return input + text.length() + {index % 97}; }}"
        )
    lines.append("}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def open_file_tree_entry_name(index: int) -> str:
    return (
        f"tree-row-{index:03d}-длинное-имя-中😀-"
        f"segment-{index % 17:02d}-segment-{index % 29:02d}-known-{index:03d}.txt"
    )


def create_open_file_tree_fixture(path: Path, editor_fixture: Path) -> Path:
    """Create the large deterministic directory outside the measured input path."""
    path.mkdir(parents=True, exist_ok=False)
    for index in range(OPEN_FILE_TREE_ENTRY_COUNT):
        entry = path / open_file_tree_entry_name(index)
        entry.write_bytes(b"")
        os.utime(entry, ns=(OPEN_FILE_TREE_TIMESTAMP_NS, OPEN_FILE_TREE_TIMESTAMP_NS))
    launch_fixture = path / "zzzz-editor-fixture.java"
    shutil.copyfile(editor_fixture, launch_fixture)
    os.utime(
        launch_fixture,
        ns=(OPEN_FILE_TREE_TIMESTAMP_NS, OPEN_FILE_TREE_TIMESTAMP_NS),
    )
    os.utime(path, ns=(OPEN_FILE_TREE_TIMESTAMP_NS, OPEN_FILE_TREE_TIMESTAMP_NS))
    return launch_fixture


def directory_manifest_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    for entry in sorted(path.iterdir(), key=lambda candidate: candidate.name):
        if not entry.is_file():
            raise ValueError(f"fixture manifest only supports files: {entry}")
        name = entry.name.encode("utf-8")
        digest.update(len(name).to_bytes(4, "big"))
        digest.update(name)
        digest.update(entry.stat().st_size.to_bytes(8, "big"))
        digest.update(bytes.fromhex(sha256(entry)))
    return digest.hexdigest()


def profile_line_count(path: Path) -> int:
    if not path.exists():
        return 0
    with path.open("rb") as stream:
        return sum(1 for line in stream if line.startswith(b"frame="))


def drain(selector: selectors.BaseSelector, captured: bytearray) -> bool:
    read_any = False
    for key, _ in selector.select(timeout=0):
        try:
            captured.extend(os.read(key.fd, 65536))
            read_any = True
            if len(captured) > 65536:
                del captured[:-65536]
        except OSError:
            try:
                selector.unregister(key.fd)
            except KeyError:
                pass
    return read_any


def event_sequence(scenario: str, index: int) -> bytes:
    if scenario == "down":
        return b"\x1b[B"
    if scenario == "shift-down":
        return b"\x1b[1;2B"
    if scenario == "mouse-drag":
        row = 3 + index % 35
        col = 10 + index % 100
        return f"\x1b[<32;{col};{row}M".encode("ascii")
    if scenario == "tabbar-wheel":
        button = 65 if index % 2 == 0 else 64
        return f"\x1b[<{button};10;2M".encode("ascii")
    if scenario in ("save-as-left", "search-replace-left"):
        return b"\x1b[D"
    if scenario == "open-file-tree":
        return b"\x1b[B"
    raise ValueError(f"unsupported scenario: {scenario}")


def run_profile(
    binary: Path,
    fixture: Path,
    open_file_tree_fixture: Path | None,
    settings_fixture: Path,
    profile_path: Path,
    scenario: str,
    frames: int,
    rows: int,
    cols: int,
    startup_delay: float,
    event_delay: float,
    quiet_period: float,
    completion_timeout: float,
) -> float:
    if profile_path.exists():
        profile_path.unlink()
    runtime_home = Path(tempfile.mkdtemp(prefix="tuink-render-home-"))
    settings_path = runtime_home / ".config" / "tuink" / "tuink.properties"
    settings_path.parent.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(settings_fixture, settings_path)

    pid, master = pty.fork()
    if pid == 0:
        fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        os.environ["TERM"] = "xterm-256color"
        child_args = [
            str(binary),
            f"-Duser.home={runtime_home}",
            f"--profile-file={profile_path}",
            str(open_file_tree_fixture if scenario == "open-file-tree" else fixture),
        ]
        if scenario not in MOUSE_SCENARIOS:
            child_args.insert(2, "--no-mouse")
        os.execv(str(binary), child_args)

    tty.setraw(master)
    selector = selectors.DefaultSelector()
    selector.register(master, selectors.EVENT_READ)
    captured = bytearray()
    status: int | None = None

    def poll() -> int | None:
        nonlocal status
        if status is not None:
            return status
        waited, candidate = os.waitpid(pid, os.WNOHANG)
        if waited:
            status = candidate
        return status

    try:
        startup_deadline = time.monotonic() + startup_delay
        while time.monotonic() < startup_deadline:
            drain(selector, captured)
            time.sleep(0.01)

        if scenario == "save-as-left":
            # Open Save As through the public Ctrl+K binding, then exercise a
            # long Unicode field rather than the closed-overlay hot path.
            os.write(master, b"\x0b")
            time.sleep(0.05)
            drain(selector, captured)
            os.write(master, b"\x1b[200~" + SAVE_AS_STRESS_TEXT + b"\x1b[201~")
            prelude_deadline = time.monotonic() + 0.15
            while time.monotonic() < prelude_deadline:
                drain(selector, captured)
                time.sleep(0.01)
            if b"Save As:" not in captured:
                raise RuntimeError("save-as-left prelude did not open the Save As prompt")
            if b"draft-segment-" not in captured and "中".encode("utf-8") not in captured:
                raise RuntimeError("save-as-left prelude did not render the long draft")

        if scenario == "search-replace-left":
            # Open Search, keep a short absent query in the first field, then
            # traverse the public focus ring to Replace and paste a long
            # Unicode value. This exercises the complete ten-control
            # composition and the horizontally paged second field.
            os.write(master, b"\x06")
            time.sleep(0.05)
            drain(selector, captured)
            os.write(master, b"\x1b[200~" + SEARCH_QUERY + b"\x1b[201~")
            for _ in range(5):
                os.write(master, b"\t")
                time.sleep(0.01)
                drain(selector, captured)
            os.write(
                master,
                b"\x1b[200~" + SEARCH_REPLACE_STRESS_TEXT + b"\x1b[201~",
            )
            prelude_deadline = time.monotonic() + 0.15
            while time.monotonic() < prelude_deadline:
                drain(selector, captured)
                time.sleep(0.01)
            if b"Find:" not in captured or b"Replace:" not in captured:
                raise RuntimeError("search-replace-left prelude did not open both search rows")
            if SEARCH_QUERY not in captured:
                raise RuntimeError("search-replace-left prelude did not render the query")
            if (
                b"replace-segment-" not in captured
                and "中".encode("utf-8") not in captured
            ):
                raise RuntimeError("search-replace-left prelude did not render the replacement")

        if scenario == "tabbar-wheel":
            # Create enough clean tabs to overflow a 140-column strip, then
            # prove that a wheel event on terminal row 2 changes its viewport.
            for _ in range(8):
                os.write(master, b"\x0e")
                time.sleep(0.02)
                drain(selector, captured)
            prelude_deadline = time.monotonic() + 0.15
            while time.monotonic() < prelude_deadline:
                drain(selector, captured)
                time.sleep(0.01)
            if b"--no name--" not in captured:
                raise RuntimeError("tabbar-wheel prelude did not render the created tabs")

            captured.clear()
            os.write(master, event_sequence(scenario, 0))
            prelude_deadline = time.monotonic() + 0.15
            while time.monotonic() < prelude_deadline:
                drain(selector, captured)
                time.sleep(0.01)
            if b"<" not in captured:
                raise RuntimeError("tabbar-wheel prelude did not move the tab viewport")
            os.write(master, event_sequence(scenario, 1))
            time.sleep(0.05)
            drain(selector, captured)

        if scenario == "open-file-tree":
            if open_file_tree_fixture is None:
                raise RuntimeError("open-file-tree fixture was not prepared")
            # Open through the public Ctrl+O binding. Waiting for a quiet
            # interval keeps asynchronous metadata publication outside the
            # measured event stream.
            os.write(master, b"\x0f")
            settle_deadline = time.monotonic() + min(10.0, completion_timeout)
            quiet_since = time.monotonic()
            while time.monotonic() - quiet_since < quiet_period:
                if poll() is not None:
                    raise RuntimeError(
                        f"application exited during open-file-tree prelude with wait status {status}"
                    )
                if time.monotonic() >= settle_deadline:
                    raise TimeoutError("open-file-tree metadata did not become idle")
                if drain(selector, captured):
                    quiet_since = time.monotonic()
                time.sleep(0.01)
            if b"Open File" not in captured:
                raise RuntimeError("open-file-tree prelude did not open the Open File dialog")
            if OPEN_FILE_TREE_FIRST_TOKEN not in captured:
                raise RuntimeError(
                    "open-file-tree prelude did not render the deterministic first row"
                )
            captured.clear()

        input_start = time.monotonic()
        if scenario == "mouse-drag":
            os.write(master, b"\x1b[<0;10;3M")
        for index in range(frames):
            os.write(master, event_sequence(scenario, index))
            drain(selector, captured)
            time.sleep(event_delay)
        if scenario == "mouse-drag":
            os.write(master, b"\x1b[<0;10;3m")

        completion_deadline = time.monotonic() + completion_timeout
        quiet_since = time.monotonic()
        while time.monotonic() - quiet_since < quiet_period:
            if poll() is not None:
                raise RuntimeError(f"application exited early with wait status {status}")
            if time.monotonic() >= completion_deadline:
                raise TimeoutError("terminal output did not become idle")
            if drain(selector, captured):
                quiet_since = time.monotonic()
            time.sleep(0.01)
        input_elapsed = time.monotonic() - input_start

        if scenario == "open-file-tree":
            late_row = min(OPEN_FILE_TREE_ENTRY_COUNT - 1, max(0, frames - 2))
            late_token = f"tree-row-{late_row:03d}".encode("ascii")
            if late_token not in captured:
                raise RuntimeError(
                    "open-file-tree input did not reach the deterministic late row"
                )

        if scenario == "open-file-tree":
            os.write(master, b"\x1b")
            time.sleep(0.05)
            drain(selector, captured)
            os.write(master, b"\x11")
        else:
            os.write(master, b"\x11\x11" if scenario == "save-as-left" else b"\x11")
        exit_deadline = time.monotonic() + 3.0
        while poll() is None and time.monotonic() < exit_deadline:
            drain(selector, captured)
            time.sleep(0.01)
        if poll() is None:
            os.kill(pid, signal.SIGTERM)
            os.waitpid(pid, 0)
            status = 0
    except Exception:
        terminal_tail = captured[-4000:].decode("utf-8", errors="replace")
        if terminal_tail:
            print(terminal_tail, file=sys.stderr)
        raise
    finally:
        if poll() is None:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)
        selector.close()
        os.close(master)
        shutil.rmtree(runtime_home, ignore_errors=True)

    expected = frames // 30
    actual = profile_line_count(profile_path)
    if actual != expected:
        raise RuntimeError(
            f"unexpected checkpoint count for {scenario}: {actual}, expected {expected}"
        )
    return input_elapsed


def git_commit(project_dir: Path) -> str | None:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=project_dir,
        check=False,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def summarize_scenario(
    records: list[dict[str, float | int]], input_elapsed: list[float], repeats: int
) -> dict[str, object]:
    metrics = {
        metric: distribution([float(record[metric]) for record in records])
        for metric in PROFILE_METRICS
    }
    return {
        "repeats": repeats,
        "checkpoints": len(records),
        "metrics_ms": metrics,
        "input_elapsed_s": distribution(input_elapsed),
    }


def format_metric(value: float) -> str:
    return f"{value:.3f}"


def summary_markdown(summary: dict[str, object]) -> str:
    metadata = summary["metadata"]
    scenarios = summary["scenarios"]
    lines = [
        f"# Render benchmark: {metadata['label']}",
        "",
        f"- Timestamp: `{metadata['timestamp_utc']}`",
        f"- Commit: `{metadata.get('commit') or 'unknown'}`",
        f"- Binary SHA-256: `{metadata['binary_sha256']}`",
        f"- Fixture SHA-256: `{metadata['fixture_sha256']}`",
        f"- Settings SHA-256: `{metadata['settings_sha256']}`",
    ]
    if metadata.get("open_file_tree_manifest_sha256"):
        lines.append(
            "- Open-file tree manifest SHA-256: "
            f"`{metadata['open_file_tree_manifest_sha256']}`"
        )
    lines.extend(
        [
            (
                f"- Terminal: `{metadata['rows']}x{metadata['cols']}`, "
                f"events: `{metadata['frames']}`, repeats: `{metadata['repeats']}`"
            ),
            "",
            "Values are aggregated over the profiler checkpoints from all repeats.",
            "",
            "| Scenario | Build mean/p50/p95 | Raster mean/p50/p95 | "
            "Diff mean/p50/p95 | Total mean/p50/p95 | Queue mean, s |",
            "|---|---:|---:|---:|---:|---:|",
        ]
    )
    for scenario, data in scenarios.items():
        metrics = data["metrics_ms"]

        def triple(metric: str) -> str:
            values = metrics[metric]
            return "/".join(
                format_metric(float(values[key])) for key in ("mean", "p50", "p95")
            )

        queue_mean = float(data["input_elapsed_s"]["mean"])
        lines.append(
            f"| {scenario} | {triple('build')} | {triple('raster')} | "
            f"{triple('diff')} | {triple('total')} | {queue_mean:.3f} |"
        )
    return "\n".join(lines) + "\n"


def run_self_test() -> None:
    assert percentile([1.0, 2.0, 3.0, 4.0], 0.5) == 2.5
    assert abs(percentile([1.0, 2.0, 3.0, 4.0], 0.95) - 3.85) < 1e-9
    stats = distribution([1.0, 2.0, 3.0])
    assert stats == {"mean": 2.0, "p50": 2.0, "p95": 2.9, "max": 3.0}
    assert event_sequence("save-as-left", 0) == b"\x1b[D"
    assert event_sequence("search-replace-left", 0) == b"\x1b[D"
    assert event_sequence("tabbar-wheel", 0) == b"\x1b[<65;10;2M"
    assert event_sequence("tabbar-wheel", 1) == b"\x1b[<64;10;2M"
    assert event_sequence("open-file-tree", 0) == b"\x1b[B"
    assert parse_scenarios(
        "down,save-as-left,search-replace-left,tabbar-wheel,open-file-tree"
    ) == [
        "down",
        "save-as-left",
        "search-replace-left",
        "tabbar-wheel",
        "open-file-tree",
    ]

    with tempfile.TemporaryDirectory(prefix="tuink-render-benchmark-") as temp:
        temp_dir = Path(temp)
        first_fixture = temp_dir / "first.java"
        second_fixture = temp_dir / "second.java"
        create_fixture(first_fixture, 32)
        create_fixture(second_fixture, 32)
        assert sha256(first_fixture) == sha256(second_fixture)
        first_settings = temp_dir / "first.properties"
        second_settings = temp_dir / "second.properties"
        create_settings_fixture(first_settings)
        create_settings_fixture(second_settings)
        assert sha256(first_settings) == sha256(second_settings)

        first_tree = temp_dir / "first-tree"
        second_tree = temp_dir / "second-tree"
        first_launch = create_open_file_tree_fixture(first_tree, first_fixture)
        second_launch = create_open_file_tree_fixture(second_tree, second_fixture)
        assert first_launch.name == "zzzz-editor-fixture.java"
        assert len(list(first_tree.iterdir())) == OPEN_FILE_TREE_ENTRY_COUNT + 1
        assert (first_tree / open_file_tree_entry_name(0)).is_file()
        assert (first_tree / open_file_tree_entry_name(359)).is_file()
        assert max(
            len(open_file_tree_entry_name(index).encode("utf-8"))
            for index in range(OPEN_FILE_TREE_ENTRY_COUNT)
        ) < 255
        assert directory_manifest_sha256(first_tree) == directory_manifest_sha256(
            second_tree
        )

        profile = temp_dir / "profile.log"
        profile.write_text(
            "frame=30 build=1.00ms raster=2.00ms diff=3.00ms "
            "search=0.00ms total=6.00ms\n"
            "frame=60 build=2.00ms raster=3.00ms diff=4.00ms "
            "search=0.00ms total=9.00ms\n",
            encoding="utf-8",
        )
        records = parse_profile(profile)
        assert len(records) == 2
        assert records[1]["frame"] == 60
        assert records[1]["total"] == 9.0

    print("render benchmark self-test: OK")


def parse_scenarios(raw: str) -> list[str]:
    scenarios = [value.strip() for value in raw.split(",") if value.strip()]
    unknown = [value for value in scenarios if value not in SCENARIOS]
    if not scenarios or unknown:
        raise ValueError(
            f"scenarios must be a comma-separated subset of {', '.join(SCENARIOS)}; "
            f"unknown: {', '.join(unknown) if unknown else 'none'}"
        )
    return scenarios


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--binary", type=Path)
    parser.add_argument("--output-root", type=Path, default=Path("build/performance/render"))
    parser.add_argument("--label", default="working-tree")
    parser.add_argument("--frames", type=int, default=600)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--scenarios", default=",".join(DEFAULT_SCENARIOS))
    parser.add_argument("--rows", type=int, default=42)
    parser.add_argument("--cols", type=int, default=140)
    parser.add_argument("--fixture-lines", type=int, default=1800)
    parser.add_argument("--startup-delay", type=float, default=2.0)
    parser.add_argument("--event-delay", type=float, default=0.002)
    parser.add_argument("--quiet-period", type=float, default=0.5)
    parser.add_argument("--completion-timeout", type=float, default=30.0)
    args = parser.parse_args()

    if args.self_test:
        run_self_test()
        return
    if os.name != "posix":
        parser.error("the PTY benchmark requires a POSIX platform")
    if args.binary is None:
        parser.error("--binary is required")
    binary = args.binary.resolve()
    if not binary.is_file() or not os.access(binary, os.X_OK):
        parser.error(f"native binary is not executable: {binary}")
    if args.frames <= 0 or args.frames % 30 != 0:
        parser.error("--frames must be a positive multiple of 30")
    if args.repeats <= 0:
        parser.error("--repeats must be positive")
    if args.rows < 4 or args.cols < 20:
        parser.error("terminal must be at least 4x20")
    if args.fixture_lines <= args.frames + args.rows:
        parser.error("--fixture-lines must exceed frames + terminal rows")
    try:
        scenarios = parse_scenarios(args.scenarios)
    except ValueError as error:
        parser.error(str(error))

    project_dir = next(parent for parent in Path(__file__).resolve().parents
                       if (parent / 'settings.gradle').is_file() and (parent / 'gradlew').is_file())
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    safe_label = re.sub(r"[^A-Za-z0-9_.-]+", "-", args.label).strip("-") or "run"
    run_dir = args.output_root.resolve() / f"{timestamp}-{safe_label}"
    run_dir.mkdir(parents=True, exist_ok=False)
    fixture = run_dir / "fixture.java"
    create_fixture(fixture, args.fixture_lines)
    settings_fixture = run_dir / "tuink.properties"
    create_settings_fixture(settings_fixture)
    open_file_tree_fixture: Path | None = None
    open_file_tree_manifest_sha256: str | None = None
    if "open-file-tree" in scenarios:
        open_file_tree_dir = run_dir / "open-file-tree-fixture"
        open_file_tree_fixture = create_open_file_tree_fixture(
            open_file_tree_dir, fixture
        )
        open_file_tree_manifest_sha256 = directory_manifest_sha256(open_file_tree_dir)

    scenario_summaries: dict[str, object] = {}
    for scenario in scenarios:
        scenario_records: list[dict[str, float | int]] = []
        input_elapsed: list[float] = []
        for repeat in range(1, args.repeats + 1):
            profile_path = run_dir / f"{scenario}-{repeat}.log"
            print(f"[{scenario} {repeat}/{args.repeats}]", flush=True)
            elapsed = run_profile(
                binary=binary,
                fixture=fixture,
                open_file_tree_fixture=open_file_tree_fixture,
                settings_fixture=settings_fixture,
                profile_path=profile_path,
                scenario=scenario,
                frames=args.frames,
                rows=args.rows,
                cols=args.cols,
                startup_delay=args.startup_delay,
                event_delay=args.event_delay,
                quiet_period=args.quiet_period,
                completion_timeout=args.completion_timeout,
            )
            records = parse_profile(profile_path)
            scenario_records.extend(records)
            input_elapsed.append(elapsed)
            print(
                f"  checkpoints={len(records)} queue_elapsed={elapsed:.3f}s",
                flush=True,
            )
        scenario_summaries[scenario] = summarize_scenario(
            scenario_records, input_elapsed, args.repeats
        )

    summary: dict[str, object] = {
        "schema": 1,
        "metadata": {
            "label": args.label,
            "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "commit": git_commit(project_dir),
            "binary": str(binary),
            "binary_sha256": sha256(binary),
            "fixture_sha256": sha256(fixture),
            "settings_sha256": sha256(settings_fixture),
            "open_file_tree_manifest_sha256": open_file_tree_manifest_sha256,
            "platform": platform.platform(),
            "machine": platform.machine(),
            "processor": platform.processor(),
            "python": platform.python_version(),
            "rows": args.rows,
            "cols": args.cols,
            "frames": args.frames,
            "repeats": args.repeats,
            "scenarios": scenarios,
            "fixture_lines": args.fixture_lines,
            "event_delay_s": args.event_delay,
        },
        "scenarios": scenario_summaries,
    }
    summary_json = run_dir / "summary.json"
    summary_md = run_dir / "summary.md"
    summary_json.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    markdown = summary_markdown(summary)
    summary_md.write_text(markdown, encoding="utf-8")
    print()
    print(markdown, end="")
    print(f"Results: {run_dir}")


if __name__ == "__main__":
    main()
