# Render-loop profiling

`--profile` enables frame timing logs. `--profile-file=<path>` writes those
logs to a file and also enables profiling.

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
frame=30 build=3.10ms raster=0.67ms diff=170.09ms search=0.00ms total=174.03ms
```

- `build`: render-plan and UI preparation.
- `raster`: rasterization into `CellBuffer`.
- `diff`: ANSI diff generation between frames.
- `search`: search match counting.
- `total`: complete frame time.

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
drag. It builds the glibc native binary automatically. Results are written to:

```text
build/performance/render/<timestamp>-<label>/
├── fixture.java
├── tuink.properties
├── <scenario>-<repeat>.log
├── summary.json
└── summary.md
```

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
summary records binary, fixture, and settings SHA-256 values with host metadata.

When completing an interface-related change reachable from a benchmarked
application path, run `perfCheck` before and after on the same host and
toolchain, and keep the accepted results in ignored local build output; see
[benchmark guidance](../verification/performance/README.md). Treat a repeated
10–15% regression or increasing queue-drain time as a reason to investigate;
do not gate arbitrary developer machines on small timing differences. A new
standalone toolkit primitive with no runtime consumer is covered structurally
and functionally first, then performance-gated when its first real consumer
makes the path measurable.
