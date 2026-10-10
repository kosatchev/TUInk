# Render benchmarks

This directory contains the native PTY render benchmark and optional PGO
training harness. For individual frame logs and metric definitions, see
[render-loop profiling](../../docs/profiling.md).

## Current benchmark

Run from the repository root:

```bash
./gradlew :app:perfHarnessTest
./gradlew :app:perfSmoke
./gradlew :app:perfCheck -PperfLabel=render-check
```

The harness requires Python 3.10+ as `python3` on a POSIX system.
`perfHarnessTest` checks the fixture, profile parser and statistics without a
native SDK or editor binary. It runs explicitly in CI, separately from the JVM
`check` task. `perfSmoke` and `perfCheck` depend on that self-test and build the
glibc native binary; they also require the
[native toolchain](../../docs/building.md#native-toolchain) and a pseudo-terminal.

| Task | Events per scenario | Repeats | Scenarios |
|---|---:|---:|---|
| `perfSmoke` | 120 | 1 | `down`, `shift-down`, `mouse-drag` |
| `perfCheck` | 600 | 3 | `down`, `shift-down`, `mouse-drag` |

`perfSmoke` is included in `:app:release`. Override the full-run defaults with
`-PperfFrames=<positive-multiple-of-30>`, `-PperfRepeats=<n>` and
`-PperfLabel=<label>`. `perfFrames` sets the number of injected input events.

### Method

The default workload uses deterministic ASCII Java source with 1,800 generated
lines, a `42x140` pseudo-terminal, a two-second startup delay and one input event
every 2 ms. After injection, the harness waits for terminal output to remain
quiet for 500 ms. The built-in profiler logs one checkpoint every 30 frames;
the harness checks the expected checkpoint count before accepting a run.

Each child receives a temporary `user.home` containing the same generated
`tuink.properties`. The settings fixture enables the tab bar, mouse mode and
syntax features, uses four-column tabs and the cyan theme, and disables line
wrapping and reading mode. Keyboard-only scenarios additionally use
`--no-mouse`. Saved user preferences therefore cannot change the workload.

### Focused scenarios

These scenarios are opt-in and do not change the default `perfCheck` mix:

| Scenario | Workload |
|---|---|
| `save-as-left` | Opens Save As through Ctrl+K, pastes a long Unicode value, verifies prompt/payload tokens, then sends Left events. |
| `search-replace-left` | Opens Search through Ctrl+F, enters an absent query, traverses the focus ring to Replace, pastes a long Unicode replacement, verifies both rows and values, then sends Left events. |
| `tabbar-wheel` | Creates an overflowing tab strip, verifies that its viewport moves, then alternates wheel directions on terminal row 2. |
| `open-file-tree` | Creates 360 deterministic long Unicode filenames with fixed timestamps, opens their directory through Ctrl+O, waits for metadata output to settle, verifies dialog/row tokens, then sends Down events and checks a later row. |

Build once, then select one scenario or a comma-separated subset. For example,
this runs all four with 300 events and three repeats each:

```bash
./gradlew --console=plain :app:nativeCompile
python3 verification/performance/render_benchmark.py \
  --binary apps/tuink/launcher/build/native/nativeCompile/tuink \
  --label=widget-input \
  --frames=300 --repeats=3 \
  --scenarios=save-as-left,search-replace-left,tabbar-wheel,open-file-tree
```

The script also accepts `--rows`, `--cols`, `--fixture-lines`, `--startup-delay`,
`--event-delay`, `--quiet-period`, `--completion-timeout` and `--output-root`.
Keep these equal across comparisons. `--frames` must be a positive multiple
of 30, and `--fixture-lines` must exceed events plus terminal rows. Functional
tests cover exact input routing; retained ANSI output alone does not establish
those semantics.

### Outputs and interpretation

Results are written below `build/performance/render/<timestamp>-<label>/`:

```text
fixture.java
tuink.properties
<scenario>-<repeat>.log
summary.json
summary.md
open-file-tree-fixture/   # only when selected
```

The JSON summary records host/runtime metadata, run parameters and SHA-256
values for the binary, text fixture and complete settings fixture. The file-tree
scenario also records a directory-manifest hash. Retain raw logs, summaries and
build details locally when comparing changes; build output is ignored by Git.

`summary.md` reports build, raster, diff and total `mean/p50/p95` in
milliseconds, aggregated over profiler checkpoints from all repeats.
`summary.json` also includes search timing, maxima, checkpoint counts and the
elapsed-time distribution across repeats.

These are sampled frame diagnostics, not input-to-paint latency or percentiles
of all frames. The reported `Queue` time (`input_elapsed_s` in JSON) includes
input injection, render backlog and the fixed 500 ms quiet window. Compare it
between paired runs; its absolute value is not a per-event latency.

Compare before/after runs on the same host with the same SDK, compiler options,
fixture, settings and harness parameters. Repeat pairs in opposite orders and
avoid overlapping probes or heavy background load. Investigate a repeated
10–15% regression or increasing queue-drain time; small differences on an
arbitrary machine are not a hard failure. A lower sampled mean alone does not
establish an overall speedup.

## Optional PGO training

PGO is experimental and uses the same pinned Oracle GraalVM SDK and
`verifyNativeToolchain` preflight as ordinary native builds. Run the harness
self-test without a native SDK or editor binary:

```bash
./gradlew :app:pgoHarnessTest
```

It requires POSIX Python 3.10+ as `python3`, is available without `-PenablePgo`,
and runs explicitly in CI. Build, train and optimize the default glibc target:

```bash
./gradlew :app:nativeProfileInstrument -PenablePgo
./gradlew :app:nativeProfileTrain -PenablePgo
./gradlew :app:nativeProfileBuild -PenablePgo
```

`nativeProfileOptimize -PenablePgo` performs fresh training and the optimized
build together. Training runs five checked PTY sessions: Java, Python, YAML and
Rust editing, plus mouse drag. Editing sessions verify saved bytes; every
session must exit normally with code zero and produce a fresh nonempty profile
before the profiles are merged.

The default profile is `.local/pgo/<target>/profile.iprof`, with an adjacent
`profile.iprof.json` receipt binding it to the SDK, classpath, compiler arguments,
CPU/libc target and harness. Retrain when these inputs change. Use
`-PpgoProfile=<path>` for a custom ignored location outside build directories.
Pass `-Ptarget=musl` to every command for a separate musl profile and binary;
`-Ptarget=all` is rejected. Profiles survive `clean` and remain local build
inputs.

Optimized binaries are separate outputs:

```text
apps/tuink/launcher/build/native/nativePgoOptimizedCompile/tuink-pgo
apps/tuink/launcher/build/native/nativeMuslPgoOptimizedCompile/tuink-musl-pgo
```

Regular release packaging uses ordinary Oracle O3 binaries. Profiles, training
measurements and PGO binaries are excluded. See
[PGO build instructions](../../docs/building.md#pgo) for target prerequisites and
profile validation.

Training is not a timing comparison: instrumentation adds overhead. Evaluate
optimized binaries on separate representative corpora against the ordinary
Oracle O3 binary from the same SDK, using paired runs. Measure CPU, wall time,
RSS and sampled render diagnostics separately. PGO does not establish a general
speedup, and checkpoint statistics do not establish all-frame latency
percentiles.
