# Render benchmarks

This directory contains the reproducible native PTY render benchmark.

## Current benchmark

The canonical benchmark generates a deterministic 1,800-line ASCII Java
fixture, opens it in a `42x140` pseudo-terminal, injects input every 2 ms, and
waits until terminal output has been quiet for 500 ms. TUInk's built-in frame
profiler records one checkpoint every 30 frames.

```bash
./gradlew :app:perfHarnessTest
./gradlew :app:perfSmoke
./gradlew :app:perfCheck -PperfLabel=render-check
```

`perfSmoke` runs 120 events once and is part of `:app:release`. `perfCheck`
runs 600 events three times for `Down`, `Shift+Down`, and mouse drag. Override
the full-run defaults with `-PperfFrames=<multiple-of-30>` and
`-PperfRepeats=<n>`.

Raw profiles, `summary.json`, and a ready-to-copy `summary.md` are written
below `build/performance/render/<timestamp>-<label>/`. Build output is ignored
by Git; keep comparison results locally.

Do not use small timing differences as a hard failure on an arbitrary machine.
Compare paired runs on the same host and toolchain, and investigate a repeated
regression of 10–15% or a growing queue-drain time.
