#!/usr/bin/env python3
"""Opt-in paired Source/Preview benchmark on deterministic Markdown fixtures."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re

import render_benchmark as benchmark


FIXTURES = ("paragraphs", "code", "table", "embedded")
SURFACES = ("source", "preview")
SCENARIOS = ("down", "shift-down", "wheel", "hscroll", "mouse-drag")


def selection(raw: str, choices: tuple[str, ...]) -> list[str]:
    values = [part.strip() for part in raw.split(",") if part.strip()]
    if not values or len(set(values)) != len(values) or any(value not in choices for value in values):
        raise ValueError(f"choose distinct comma-separated values from {', '.join(choices)}")
    return values


def terminal_sizes(raw: str) -> list[tuple[int, int]]:
    values = []
    for part in raw.split(","):
        match = re.fullmatch(r"(\d+)x(\d+)", part.strip())
        if match is None or int(match[1]) < 12 or int(match[2]) < 40:
            raise ValueError("sizes must be comma-separated ROWSxCOLS, at least 12x40")
        values.append((int(match[1]), int(match[2])))
    if not values or len(set(values)) != len(values):
        raise ValueError("terminal sizes must be nonempty and distinct")
    return values


def create_markdown_fixture(path: Path, kind: str, lines: int) -> None:
    header = "# Markdown benchmark\n\n"
    if kind == "paragraphs":
        body = "\n\n".join(f"Строка {i:04d}: текст с **выделением**, `кодом`, 中😀 и [ссылкой](#markdown-benchmark)."
                            for i in range(lines))
    elif kind == "code":
        payload = "данные_中😀\t" * 90
        body = "```text\n" + "\n".join(f"{i:04d}\t{payload}" for i in range(lines)) + "\n```"
    elif kind == "table":
        cell = "широкая_ячейка_中😀_" * 5
        body = "| Номер | Значение | Комментарий |\n|---|---|---|\n" + "\n".join(
            f"| {i:04d} | {cell} | {cell} |" for i in range(lines))
    elif kind == "embedded":
        block = ("$$\n\\int_0^1 x^2\\,dx = \\frac{1}{3} + \\sqrt{x^{2^3}}\n$$\n\n"
                 "```mermaid\nflowchart LR\n  A[Ввод] --> B[Обработка] --> C[Результат]\n```\n\n")
        body = block * max(1, math.ceil(lines / 8))
    else:
        raise ValueError(f"unsupported Markdown fixture: {kind}")
    path.write_text(header + body + "\n", encoding="utf-8", newline="\n")


def cases(fixtures: list[str], surfaces: list[str], sizes: list[tuple[int, int]], scenarios: list[str]):
    for kind in fixtures:
        for rows, cols in sizes:
            for surface in surfaces:
                for scenario in scenarios:
                    # Wrapped paragraphs and embedded boxes need not overflow;
                    # keep horizontal measurements on guaranteed wide fixtures.
                    if scenario == "hscroll" and kind not in ("code", "table"):
                        continue
                    yield kind, rows, cols, surface, scenario


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, default=Path("build/performance/markdown"))
    parser.add_argument("--label", default="markdown-paired")
    parser.add_argument("--frames", type=int, default=120)
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--fixtures", default=",".join(FIXTURES))
    parser.add_argument("--surfaces", default=",".join(SURFACES))
    parser.add_argument("--sizes", default="24x80,50x180")
    parser.add_argument("--scenarios", default="down,shift-down,wheel")
    parser.add_argument("--fixture-lines", type=int, default=600)
    parser.add_argument("--warmup-events", type=int, default=30)
    parser.add_argument("--startup-delay", type=float, default=0.1)
    parser.add_argument("--event-delay", type=float, default=0.002)
    parser.add_argument("--quiet-period", type=float, default=0.2)
    parser.add_argument("--completion-timeout", type=float, default=30.0)
    args = parser.parse_args()
    try:
        fixtures = selection(args.fixtures, FIXTURES)
        surfaces = selection(args.surfaces, SURFACES)
        scenarios = selection(args.scenarios, SCENARIOS)
        sizes = terminal_sizes(args.sizes)
    except ValueError as error:
        parser.error(str(error))
    binary = args.binary.resolve()
    if os.name != "posix" or not binary.is_file() or not os.access(binary, os.X_OK):
        parser.error("--binary must be executable on a POSIX platform")
    if args.frames <= 0 or args.frames % 30 or args.repeats < 1 or args.warmup_events < 0:
        parser.error("frames must be a positive multiple of30; repeats positive; warmup events nonnegative")
    # A wheel event pans three visual/source rows. The source fixture has at
    # least this many rows, so the measured stream does not hit document end.
    if args.fixture_lines <= 3 * (args.frames + args.warmup_events) + max(rows for rows, _ in sizes):
        parser.error("fixture-lines must exceed 3*(frames+warmup-events)+largest terminal rows")
    for name in ("startup_delay", "event_delay", "quiet_period", "completion_timeout"):
        value = getattr(args, name)
        if not math.isfinite(value) or value < 0 or (name in ("quiet_period", "completion_timeout") and value == 0):
            parser.error(f"invalid --{name.replace('_', '-')}")

    project = Path(__file__).resolve().parents[2]
    checkout = benchmark.source_state(project)
    binary_hash = benchmark.sha256(binary)
    provenance = benchmark.build_provenance(binary, binary_hash, checkout)
    harness_files = [Path(__file__).resolve(), Path(benchmark.__file__).resolve(), project / "verification/ui/vt.py"]
    harness_hashes = {str(path.relative_to(project)): benchmark.sha256(path) for path in harness_files}
    combined_hash = hashlib.sha256(json.dumps(harness_hashes, sort_keys=True).encode()).hexdigest()
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    label = re.sub(r"[^A-Za-z0-9_.-]+", "-", args.label).strip("-") or "run"
    output = args.output_root.resolve() / f"{timestamp}-{label}"
    output.mkdir(parents=True, exist_ok=False)
    settings = output / "tuink.properties"
    benchmark.create_settings_fixture(settings)
    paths = {}
    for kind in fixtures:
        paths[kind] = output / f"fixture-{kind}.md"
        create_markdown_fixture(paths[kind], kind, args.fixture_lines)
    fixture_hashes = {kind: benchmark.sha256(path) for kind, path in paths.items()}
    summaries = {}
    for kind, rows, cols, surface, scenario in cases(fixtures, surfaces, sizes, scenarios):
        name = f"{kind}-{rows}x{cols}-{surface}-{scenario}"
        records, elapsed, measurements = [], [], []
        for repeat in range(1, args.repeats + 1):
            profile = output / f"{name}-{repeat}.log"
            measurement = {}
            print(f"[{name} {repeat}/{args.repeats}]", flush=True)
            elapsed.append(benchmark.run_profile(binary, paths[kind], None, settings, profile, scenario,
                args.frames, rows, cols, args.startup_delay, args.event_delay, args.quiet_period,
                args.completion_timeout, surface=surface, warmup_events=args.warmup_events,
                diagnostics=measurement))
            records.extend(benchmark.parse_profile(profile)[int(measurement["profile_start_checkpoint"]):])
            measurements.append(measurement)
        data = benchmark.summarize_scenario(records, elapsed, args.repeats)
        data.update(fixture=kind, fixture_sha256=fixture_hashes[kind], surface=surface,
                    rows=rows, cols=cols, scenario=scenario, measurements=measurements)
        data["pty_output_bytes"] = benchmark.distribution([float(m["pty_output_bytes"]) for m in measurements])
        data["source_ready_s"] = benchmark.distribution([float(m["source_ready_s"]) for m in measurements])
        if surface == "preview":
            data["preview_ready_s"] = benchmark.distribution([float(m["preview_ready_s"]) for m in measurements])
        summaries[name] = data
    if (benchmark.sha256(binary) != binary_hash or any(benchmark.sha256(path) != harness_hashes[str(path.relative_to(project))]
                                                    for path in harness_files)):
        raise RuntimeError("binary or harness changed during the run; results were not accepted")
    summary = {"schema": 1, "workload": "markdown-paired", "metadata": {
        "label": args.label, "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "binary": str(binary), "binary_sha256": binary_hash, "run_checkout": checkout,
        "build_provenance": provenance, "harness_sha256": combined_hash, "harness_files": harness_hashes,
        "fixture_sha256": hashlib.sha256(json.dumps(fixture_hashes, sort_keys=True).encode()).hexdigest(),
        "fixture_hashes": fixture_hashes, "settings_sha256": benchmark.sha256(settings),
        "platform": platform.platform(), "machine": platform.machine(), "python": platform.python_version(),
        "rows": "varies", "cols": "varies", "terminal_sizes": [f"{r}x{c}" for r, c in sizes],
        "frames": args.frames, "repeats": args.repeats, "fixture_lines": args.fixture_lines,
        "fixtures": fixtures, "surfaces": surfaces, "scenarios": scenarios,
        "startup_delay_s": args.startup_delay, "event_delay_s": args.event_delay,
        "quiet_period_s": args.quiet_period, "completion_timeout_s": args.completion_timeout,
        "warmup_events": args.warmup_events, "screen_oracle": "readiness-and-warmup-only",
        "profile_every_frames": 30,
        "exit_timeout_s": benchmark.EXIT_TIMEOUT, "terminate_timeout_s": benchmark.TERMINATE_TIMEOUT,
        "kill_timeout_s": benchmark.KILL_TIMEOUT,
        "inherited_environment": {name: os.environ.get(name) for name in ("COLORTERM", "LANG", "LC_ALL", "TZ")},
    }, "scenarios": summaries}
    (output / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    (output / "summary.md").write_text(benchmark.summary_markdown(summary))
    print(f"Results: {output}", flush=True)


if __name__ == "__main__":
    main()
