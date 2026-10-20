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
`perfHarnessTest` checks the fixture, profile parser, statistics, and PTY child
shutdown using temporary test programs without a native SDK or editor binary.
It runs explicitly in CI, separately from the JVM
`check` task. `perfSmoke` and `perfCheck` depend on that self-test and build the
selected native variant: ordinary glibc by default, `-Pmusl` for musl and
`-Ppgo` for PGO. Combine the flags for musl PGO, or use `-Ptarget=all` to measure
both libc targets. PGO requires a matching existing profile. The tasks also
require the [native toolchain](../../docs/building.md#native-toolchain) and a
pseudo-terminal.
Native compiler tasks write a binary-adjacent `<binary>.build.json` receipt
after a successful build. This adds no Python prerequisite to native compilation.

| Task | Events per scenario | Repeats | Scenarios |
|---|---:|---:|---|
| `perfSmoke` | 120 | 1 | `down`, `shift-down`, `mouse-drag` |
| `perfCheck` | 600 | 3 | `down`, `shift-down`, `mouse-drag` |

`:app:release` smoke-tests every native variant it ships, including ordinary
and PGO variants when `-Ppgo` is enabled. Override the full-run defaults with
`-PperfFrames=<positive-multiple-of-30>`, `-PperfRepeats=<n>` and
`-PperfLabel=<label>`. `perfFrames` sets the number of injected input events.
Gradle grows the fixture when needed, so a larger event count does not reach
the end of the generated document.

### Method

The default workload uses deterministic ASCII Java source with at least 1,800
generated lines, a `42x140` pseudo-terminal, a two-second startup delay and one
input event every 2 ms. After injection, the harness waits for terminal output to remain
quiet for 500 ms. The built-in profiler logs one checkpoint every 30 frames;
the harness checks the expected checkpoint count before accepting a run. The
editor must also exit normally with code zero within three seconds of Ctrl+Q.
A crash, signal exit, or shutdown timeout fails the run even with a complete
profile. Failed children are cleaned up with SIGTERM and then SIGKILL if needed,
with bounded waits; forced termination never counts as successful shutdown.

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
of 30, and `--fixture-lines` must exceed events plus terminal rows. When invoking
the Python script directly, increase its default 1,800 lines for longer runs.
Functional tests cover exact input routing; retained ANSI output alone does not establish
those semantics.

### Outputs and interpretation

Gradle writes results below
`build/performance/render/<variant>/<smoke-or-check>/<timestamp>-<label>/`.
The label also includes the variant, so glibc, musl and PGO results remain
distinct. Direct Python runs use `build/performance/render/<timestamp>-<label>/`
unless `--output-root` is supplied. Each run contains:

```text
fixture.java
tuink.properties
<scenario>-<repeat>.log
summary.json
summary.md
open-file-tree-fixture/   # only when selected
```

The JSON summary records host/runtime metadata, run parameters and SHA-256
values for the binary, benchmark harness, text fixture and complete settings
fixture. Parameters include startup and event delays, the quiet period,
completion and shutdown timeouts, terminal dimensions, event/repeat counts and
scenario order. The file-tree scenario also records a directory-manifest hash.
Retain raw logs, summaries and build details locally when comparing changes;
build output is ignored by Git.

Summary schema 2 separates `run_checkout` (the checkout running the harness)
from `build_provenance` (the measured executable). A hash-matched build receipt
records the binary's build commit, dirty status, content digest of tracked and
non-ignored untracked files, SDK release and native-image hashes, configured
compiler/JVM arguments and flags, and compiled classpath hashes. The receipt is
written by the native compiler task, checked against its inputs before/after
compilation, and preserved when the task is up to date. Its source digest also
distinguishes subsequent edits to an already dirty tree. These local receipts
are build records, not signed attestations.
For published comparisons, build from a clean tagged checkout and retain the
tag and receipt. A dirty-tree digest distinguishes local inputs but cannot let
readers reconstruct uncommitted or untracked source contents.

The harness checks the receipt's binary hash and embeds the receipt in the
summary. `source_matches_run_checkout` reports whether the build source matches
the checkout at run start; a historical binary retains its recorded origin.
Without a matching complete receipt, the benchmark still runs but explicitly
reports the binary's build origin as unknown. It never labels an arbitrary
`--binary` with the current checkout's HEAD. Rebuild older binaries to generate
a receipt, and keep that sidecar when copying a binary for comparison. Builds
from source archives without Git record unknown commit/source provenance.

`summary.md` reports build, raster, diff and total `mean/p50/p95` in
milliseconds, aggregated over profiler checkpoints from all repeats.
`summary.json` also includes search timing, maxima, checkpoint counts and the
elapsed-time distribution across repeats.

These are sampled frame diagnostics, not input-to-paint latency or percentiles
of all frames. The reported `Queue` time (`input_elapsed_s` in JSON) includes
input injection, render backlog and the configured quiet window (500 ms by
default). Compare it
between paired runs; its absolute value is not a per-event latency.

Compare before/after runs on the same host with the same SDK, compiler options,
fixture, settings and harness parameters. Repeat pairs in opposite orders and
avoid overlapping probes or heavy background load. Investigate a repeated
10–15% regression or increasing queue-drain time; small differences on an
arbitrary machine are not a hard failure. A lower sampled mean alone does not
establish an overall speedup.

## Optional PGO training

PGO is experimental and specifically requires **Oracle GraalVM 25.0.4+7.1
Native Image**: Oracle JDK alone is insufficient, and GraalVM Community does
not support PGO. It uses the same pinned SDK and `verifyNativeToolchain`
preflight as ordinary native builds. Run the harness
self-test without a native SDK or editor binary:

```bash
./gradlew :app:pgoHarnessTest
```

It requires POSIX Python 3.10+ as `python3`, is available without `-Ppgo`,
and runs explicitly in CI. Train and optimize the default glibc target:

```bash
./gradlew :app:nativeProfileTrain -Ppgo
./gradlew :app:nativeDist -Ppgo
```

`nativeProfileTrain` first builds the instrumented binary.
`nativeProfileOptimize -Ppgo` performs fresh training and the optimized
build together. Training runs five checked PTY sessions: Java, Python, YAML and
Rust editing, plus mouse drag. Editing sessions verify saved bytes; every
session must exit normally with code zero and produce a fresh nonempty profile
before the profiles are merged.

The default profile is `.local/pgo/<target>/profile.iprof`, with an adjacent
`profile.iprof.json` receipt binding it to the SDK, classpath, compiler arguments,
CPU/libc target and harness. Retrain when these inputs change. Use
`-PpgoProfile=<path>` for a custom ignored location outside build directories
when selecting one libc.
Pass `-Pmusl` to every command for a separate musl profile and binary;
`-Ptarget=all` processes both libc targets with separate profiles. With `all`,
use `-PpgoGlibcProfile=<path>` and `-PpgoMuslProfile=<path>` for custom paths;
the shared `-PpgoProfile` is rejected. Explicit `nativeProfileGlibc*` and
`nativeProfileMusl*` tasks select their named libc; do not add a contradictory
libc selector. All profile path properties require PGO to be enabled; shared
and per-target paths must agree when both are supplied for the same target.
Profiles survive `clean` and remain local build inputs.

`nativeDist -Ppgo` exposes the selected bare executable as
`build/native-dist/glibc-pgo/tuink`, or `build/native-dist/musl-pgo/tuink` with
`-Pmusl`. The compiler's intermediate optimized binaries remain separate outputs:

```text
apps/tuink/launcher/build/native/nativePgoOptimizedCompile/tuink-pgo
apps/tuink/launcher/build/native/nativeMuslPgoOptimizedCompile/tuink-musl-pgo
```

`nativeDistTarGz` uses an ordinary Oracle O3 binary by default; `-Ppgo`
selects the optimized archive, and `-Pmusl` selects its libc. `nativeDeb -Ppgo`
uses optimized glibc, with the same package name and version as the ordinary
package. `distributionArchives -Ptarget=all -Ppgo` produces
all five application archives. `release` adds the selected PGO archives only
with `-Ppgo` or the legacy `-PenablePgo`. Explicit per-variant archive tasks and
the legacy `-Ptarget=glibc|musl|all` selector remain supported.
Packaging requires matching existing profiles and does not train automatically.
Profiles, receipts, training measurements and instrumented binaries are excluded. See
[PGO build instructions](../../docs/building.md#pgo) for target prerequisites and
profile validation.

Training is not a timing comparison: instrumentation adds overhead. Evaluate
optimized binaries on separate representative corpora against the ordinary
Oracle O3 binary from the same SDK, using paired runs. Measure CPU, wall time,
RSS and sampled render diagnostics separately. PGO does not establish a general
speedup, and checkpoint statistics do not establish all-frame latency
percentiles.
