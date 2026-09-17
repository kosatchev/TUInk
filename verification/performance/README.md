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

These tasks require Python 3.10+ available as `python3` on a POSIX system.
`perfHarnessTest` checks the fixture, parser, and statistics without building or
launching TUInk. It runs explicitly in CI and is separate from the JVM `check`
task. Both native benchmarks depend on this self-test and additionally require
the [native toolchain](../../docs/building.md#native-toolchain) and a POSIX
pseudo-terminal.

`perfSmoke` runs 120 events once and is part of `:app:release`. `perfCheck`
runs 600 events three times for `Down`, `Shift+Down`, and mouse drag. Override
the full-run defaults with `-PperfFrames=<multiple-of-30>` and
`-PperfRepeats=<n>`.

For focused widget work, the same harness accepts opt-in scenarios
`save-as-left`, `search-replace-left`, `tabbar-wheel`, and `open-file-tree`. The field scenarios
open the corresponding field, insert a long Unicode value, verify
prompt/payload tokens, and drive 300 Left events. `tabbar-wheel` creates an
overflowing strip, fail-fast checks that its viewport moves, and alternates
wheel directions on terminal row 2. `open-file-tree` creates 360 deterministic
long Unicode filenames with fixed timestamps, opens their directory through
Ctrl+O in an isolated home, waits for metadata output to settle, verifies the
dialog and known rows, then drives Down. These scenarios are excluded from
`perfCheck` so its default scenario set remains stable; exact routing remains
covered by functional tests rather than inferred only from retained ANSI output.

Raw profiles, `summary.json`, and a ready-to-copy `summary.md` are written
below `build/performance/render/<timestamp>-<label>/`. Build output is ignored
by Git; keep comparison results locally. The directory also contains the deterministic
`tuink.properties` used through a temporary isolated `user.home`; summary
metadata records its SHA-256 together with binary and text-fixture hashes.

Do not use small timing differences as a hard failure on an arbitrary machine.
Compare paired runs on the same host and toolchain, and investigate a repeated
regression of 10–15% or a growing queue-drain time.
