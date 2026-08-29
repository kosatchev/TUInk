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
SCENARIOS = DEFAULT_SCENARIOS + ("save-as-left",)
SAVE_AS_STRESS_TEXT = ("draft-segment-" * 128 + "中😀" * 64).encode("utf-8")


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
    if scenario == "save-as-left":
        return b"\x1b[D"
    raise ValueError(f"unsupported scenario: {scenario}")


def run_profile(
    binary: Path,
    fixture: Path,
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

    pid, master = pty.fork()
    if pid == 0:
        fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        os.environ["TERM"] = "xterm-256color"
        child_args = [
            str(binary),
            f"--profile-file={profile_path}",
            str(fixture),
        ]
        if scenario != "mouse-drag":
            child_args.insert(1, "--no-mouse")
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
    assert parse_scenarios("down,save-as-left") == ["down", "save-as-left"]

    with tempfile.TemporaryDirectory(prefix="tuink-render-benchmark-") as temp:
        temp_dir = Path(temp)
        first_fixture = temp_dir / "first.java"
        second_fixture = temp_dir / "second.java"
        create_fixture(first_fixture, 32)
        create_fixture(second_fixture, 32)
        assert sha256(first_fixture) == sha256(second_fixture)

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
