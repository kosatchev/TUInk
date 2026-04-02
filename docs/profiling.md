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
