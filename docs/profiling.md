# Render-loop profiling

`--profile` enables frame timing logs. `--profile-file=<path>` writes those
logs to a new file and also enables profiling. The log must differ from the
document being opened, including a document symlink whose target does not
exist yet. Existing log files and symlinks are rejected without changing
their contents; choose a fresh log path for each run.

JVM examples:

```bash
./gradlew --console=plain :app:run \
  -PappArgs="--profile file.txt" 2>profile-jvm.log

./gradlew --console=plain :app:run \
  -PappArgs="--profile-file=/absolute/path/profile-jvm.log file.txt"
```

Native example:

```bash
./apps/tuink/launcher/build/native/nativeCompile/tuink \
  --profile \
  --profile-file=/absolute/path/profile-native.log \
  file.txt
```

Example record:

```text
frame=30 build=3.10ms raster=0.67ms diff=1.09ms search=0.00ms total=5.80ms compose=0.40ms input=0.10ms output=0.30ms response=6.20ms chars=240 events=1 heap=8388608 surface=source ready=1
```

- `build`: editor render-plan construction; Preview has no editor plan, so its value is zero.
- `compose`: preparation of the Source or Preview frame composition. `search` is a nested measurement and must not be added to it.
- `raster`: rasterization into `CellBuffer`. The historical Source scope covers the document body; Preview covers its complete frame. Use `total` for cross-surface comparisons until the common rendering path unifies these phases.
- `diff`: ANSI diff generation between frames.
- `search`: search match counting.
- `output`: presenter writer output and flush, separate from `diff`.
- `input`: handler wall time for input events associated with this sampled frame.
- `response`: first associated decoded event delivery to frame completion, including application maintenance. This is not physical keypress or terminal display latency. `events=0` has no input association and is excluded from input/response distributions.
- `chars`: presenter-emitted UTF-16 character count; it is not an encoded byte count.
- `heap`: current runtime used-heap snapshot, including uncollected garbage; it is not retained-cache memory.
- `surface` and `ready`: the active surface and whether it has accepted current content; Source is always ready.
- `total`: complete frame time. Logs still sample every 30 rendered frames.

The original `frame/build/raster/diff/search/total` prefix remains readable by
the benchmark. New fields are optional when measuring older binaries; an absent
measurement is unavailable, rather than zero.

When a raster cache owner is available, records also contain `metrics_builds`,
`cache_misses`, `rows_reused` and `retained_cache_bytes`. The first three are
counter differences sampled immediately before and after this frame's raster
pass. They do not count composition, input-time navigation, parsing or all
Unicode segmentation in the application. `metrics_builds` counts successful
admitted index construction, excluding cache hits and refused admission; borrowed
geometry does not build a second index. Source samples its prepared-row metrics;
Preview samples its accepted layout's visual/semantic geometry. `cache_misses`
and `rows_reused` describe prepared rows, including Source's previous-frame reuse.

`retained_cache_bytes` is the actual live admission charge after rasterization,
including allocations retained by frame handles after LRU eviction. Source's
scope includes prepared rows, fallback identities and frame slots; Preview's
scope includes visual/semantic geometry, its mapping and prepared rows within
the shared layout pool. Neither scope includes all heap, CellBuffer, chrome,
raw Markdown or the model/layout's separate charges. These distinct scopes
must be kept when comparing surfaces. An unready Preview has no accepted cache
owner and omits the fields. A measured zero is not substituted for an absent
owner. JSON summaries record `counter_samples` for each available counter,
so mixed historical/current records do not imply full coverage. Cache diagnostics
remain opt-in and add no per-glyph instrumentation.

## Paired Markdown benchmark

The opt-in matrix compares Source and Preview on identical deterministic
Unicode paragraphs, long tabbed code rows, wide tables, and math/Mermaid blocks.
It reuses the render harness's PTY lifecycle, settings and build provenance;
the canonical `perfCheck` scenarios remain unchanged.

```bash
python3 -B verification/performance/markdown_benchmark.py \
  --binary apps/tuink/launcher/build/native/nativeCompile/tuink \
  --label=markdown-before --frames=60 --repeats=1 \
  --fixtures=paragraphs,code --sizes=24x80 \
  --scenarios=down,shift-down

python3 -B verification/performance/markdown_benchmark.py \
  --binary apps/tuink/launcher/build/native/nativeCompile/tuink \
  --label=markdown-matrix --frames=120 --repeats=1 \
  --fixtures=paragraphs,code,table,embedded --sizes=24x80,50x180 \
  --scenarios=down,shift-down,wheel,hscroll,mouse-drag
```

Each session verifies the Source screen; Preview additionally waits for an
accepted layout without `updating`. The startup and initial Preview readiness
times are recorded separately. After warmup events, a visible File-menu open/close
round trip acknowledges that the earlier input queue was processed; output quiet
alone would miss queued events whose frames are unchanged. A quiet-output barrier
then precedes the measured stream. The screen oracle runs only for readiness and warmup,
keeping Python cell parsing out of measured output handling.

Results under `build/performance/markdown/` retain all fixtures, per-case profile
logs, summaries, dimensions, surface, warmup parameters and hashes. Cold
readiness records and warm frame distributions remain distinct. The JSON also
records actual received PTY bytes, including bytes discarded from its bounded
diagnostic tail. Horizontal cases use SGR wheel events shared by Source and Preview
on code/table fixtures; drag coordinates
stay within the selected terminal size. Reported frame p50/p95/p99 values are
sampled diagnostics; queue elapsed still includes injection and the quiet window.
Repeat comparisons with the same fixture/settings/harness hashes and parameters.

## Reproducible native benchmark

Use the repository PTY harness instead of sending keys manually when comparing
rendering or input changes:

```bash
./gradlew :app:perfHarnessTest
./gradlew :app:perfSmoke
./gradlew :app:perfCheck -PperfLabel=render-check
```

`perfSmoke` runs 120 events once and is included in `:app:release`.
`perfCheck` runs 600 events three times for `Down`, `Shift+Down`, and mouse
drag. It builds the selected native variant automatically (glibc by default).
Gradle writes results to:

```text
build/performance/render/<variant>/<smoke-or-check>/<timestamp>-<label>/
├── fixture.java
├── tuink.properties
├── <scenario>-<repeat>.log
├── summary.json
└── summary.md
```

Direct Python harness runs use `build/performance/render/<timestamp>-<label>/`
unless `--output-root` is supplied.

The harness also has opt-in `save-as-left`, `search-replace-left`, and
`tabbar-wheel` scenarios. The first opens Save As through Ctrl+K. The second
opens Search through Ctrl+F, inserts a distinct query, traverses the ten-target
focus ring to Replace, and inserts a long Unicode replacement. Both verify
their prompt/payload tokens before injecting Left events. `tabbar-wheel`
creates enough tabs to overflow the strip, verifies that a wheel event moves
its viewport, then alternates wheel directions on terminal row 2. These
scenarios are deliberately not part of the canonical closed-overlay task:

```bash
./gradlew --console=plain :app:nativeCompile
python3 verification/performance/render_benchmark.py \
  --binary apps/tuink/launcher/build/native/nativeCompile/tuink \
  --label=save-as-cursor \
  --frames=300 --repeats=3 --scenarios=save-as-left

python3 verification/performance/render_benchmark.py \
  --binary apps/tuink/launcher/build/native/nativeCompile/tuink \
  --label=search-cursor \
  --frames=300 --repeats=3 --scenarios=search-replace-left

python3 verification/performance/render_benchmark.py \
  --binary apps/tuink/launcher/build/native/nativeCompile/tuink \
  --label=tabbar-scroll \
  --frames=300 --repeats=3 --scenarios=tabbar-wheel
```

The generated fixture, terminal size, injection interval, queue quiet window,
isolated current-schema settings, and statistical aggregation are controlled by
`verification/performance/render_benchmark.py`. Each child receives a temporary `user.home`,
so saved mouse/tab preferences cannot silently change a scenario. The JSON
summary records binary, harness, fixture, and settings SHA-256 values, host
metadata, and all configurable delays/timeouts. Native builds produce an adjacent
`.build.json` receipt with the binary hash, source commit and dirty-tree content
digest, SDK identity, compiler arguments and compiled inputs. A matching receipt
is embedded in the summary; missing or stale receipts report unknown build origin.
The checkout running the harness is recorded separately and never substitutes
for the origin of an arbitrary `--binary`. Keep the receipt with copied binaries;
see [build provenance](../verification/performance/README.md#outputs-and-interpretation).

When completing an interface-related change reachable from a benchmarked
application path, run `perfCheck` before and after on the same host and
toolchain. Keep the paired results in the local benchmark output directory;
see [benchmark methodology](../verification/performance/README.md). Treat a repeated
10–15% regression or increasing queue-drain time as a reason to investigate;
do not gate arbitrary developer machines on small timing differences. A new
standalone toolkit primitive with no runtime consumer is covered structurally
and functionally first, then performance-gated when its first real consumer
makes the path measurable.
