# Building TUInk

Run the built-in command reference from the repository root:

```bash
./gradlew buildHelp
```

This task only prints help. It does not compile sources or create artifacts.

## Requirements

JVM builds and `check` require Java 17 and use the checked-in Gradle wrapper.

Check the active JVM and Gradle environment:

```bash
./gradlew --version
```

Do not rely on a system Gradle installation: use `./gradlew` so the project
uses the checked-in wrapper version.

Native builds additionally require:

- GraalVM with `native-image`;
- a system C toolchain;
- the platform libraries required by GraalVM;
- a musl-capable toolchain for the static musl target.

Benchmark tasks require Python 3.10+ available as `python3` on a POSIX system.
The standalone `:app:perfHarnessTest` validates the benchmark harness without
building or launching the native editor. It is separate from `check` and runs
explicitly in CI. `:app:perfSmoke` and `:app:perfCheck` also require a POSIX
pseudo-terminal and the native toolchain; both run the harness self-test first.
The short benchmark is part of the complete release task.

Debian packaging additionally requires Linux with `dpkg-deb`, `gzip`, and
`strip`. `lintian` is optional and is used only for package validation.

## Choose a build

| Goal | Command | Primary output |
|---|---|---|
| Run verification | `./gradlew check` | Tests plus architecture/dependency checks |
| Build JVM project | `./gradlew clean test build` | Module classes and JARs |
| Run from sources | `./gradlew --console=plain :app:run` | Interactive process |
| Installable JVM layout | `./gradlew :app:installDist` | `apps/tuink/launcher/build/install/tuink/` |
| JVM release ZIP | `./gradlew :app:distZip` | `build/releases/tuink-<version>.zip` |
| Native glibc binary | `./gradlew :app:nativeGlibc` | `apps/tuink/launcher/build/native/nativeCompile/tuink` |
| Native musl binary | `./gradlew :app:nativeMusl` | `apps/tuink/launcher/build/native/nativeMuslCompile/tuink-musl` |
| Native archive | `./gradlew :app:nativeDistTarGz` | `build/releases/*.tar.gz` |
| Debian package | `./gradlew :app:nativeDeb` | `build/releases/tuink_<version>_<arch>.deb` |
| Benchmark harness self-test | `./gradlew :app:perfHarnessTest` | Python fixture, parser, and statistics checks |
| Render smoke benchmark | `./gradlew :app:perfSmoke` | `build/performance/render/` |
| Full render benchmark | `./gradlew :app:perfCheck` | `build/performance/render/` |
| Complete release | `./gradlew clean :app:release` | ZIP, glibc archive, DEB, checksums |

## JVM development build

Build and test all modules:

```bash
./gradlew clean check build
```

Run only a specific module's tests when iterating:

```bash
./gradlew :tui-core:test
./gradlew :core:test
./gradlew :render:test
./gradlew :tui:test
```

Verify the JVM build and distribution:

```bash
./gradlew --console=plain clean check :app:distZip
```

Run the canonical application entrypoint:

```bash
./gradlew --console=plain :app:run
./gradlew --console=plain :app:run -PappArgs="file.txt"
```

The editor requires an interactive TTY. An IDE console or redirected stdin may
be detected as a dumb terminal.

## Dependency integrity

Dependency versions are locked per module, downloaded artifacts are checked
against `gradle/verification-metadata.xml`, and both the wrapper JAR and Gradle
distribution have pinned SHA-256 checksums. A normal build must not rewrite any
of these files.

After an intentional dependency change, regenerate the lock state and
verification metadata together while resolving the complete CI dependency
graph:

```bash
./gradlew --write-locks --write-verification-metadata sha256 \
  check :app:distZip :app:perfHarnessTest \
  :unicode-text:dependencies :tui-core:dependencies :core:dependencies \
  :render:dependencies :highlight-regex:dependencies \
  :tui:dependencies :app:dependencies
```

Review the resulting coordinate and checksum changes before committing them.
When upgrading Gradle itself, obtain both wrapper checksums from the official
Gradle distribution index and update `gradle-wrapper.properties` together with
`gradle-wrapper.jar.sha256`.

## JVM distribution

Build an installed JVM layout or ZIP:

```bash
./gradlew :app:installDist
./gradlew :app:distZip
```

Run the installed layout:

```bash
./apps/tuink/launcher/build/install/tuink/bin/tuink file.txt
```

The JVM distribution requires Java 17 on the target machine. It includes
`README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, and `samples/`.

Gradle also creates module JARs as internal build products. They are not
standalone fat/executable JAR distributions and do not carry the complete
runtime dependency set. Use `:app:distZip` when a portable JVM release is
needed.

## Native targets

Build the default glibc binary:

```bash
./gradlew :app:nativeGlibc
./apps/tuink/launcher/build/native/nativeCompile/tuink file.txt
```

Build the static musl binary or both variants:

```bash
./gradlew :app:nativeMusl
./gradlew :app:nativeAll
```

The musl binary is written to:

```text
apps/tuink/launcher/build/native/nativeMuslCompile/tuink-musl
```

Native builds default to `-march=compatibility` so release binaries work on
older x86_64 CPUs. Override it only when the deployment CPU is known:

```bash
./gradlew :app:nativeMusl -PnativeMarch=native
```

Use `nativeMarch=native` only for a binary that will run on the same or a known
compatible CPU generation. Release builds should keep the default
`compatibility` value.

## Native release archives

Build a glibc, musl, or both native archives:

```bash
./gradlew :app:nativeDistTarGz
./gradlew :app:nativeDistTarGz -Ptarget=musl
./gradlew :app:nativeDistTarGz -Ptarget=all
```

Archives are written to `build/releases/` and include the binary, installer,
uninstaller, README, licenses, third-party notices, and samples.

## Debian package

Build a Debian package from the glibc native binary:

```bash
./gradlew :app:nativeDeb
```

The package is written to:

```text
build/releases/tuink_<version>_<deb-arch>.deb
```

The Debian task does not use the archive installer and does not invoke `sudo`.
Do not pass `-Ptarget=musl` to `nativeDeb`.

See [Packaging TUInk](packaging.md) for package contents, inspection, linting,
and installation.

## Complete release

Build the tested JVM ZIP, default glibc archive, Debian package, and checksum
manifest:

```bash
./gradlew clean :app:release
```

Include the musl archive as well:

```bash
./gradlew clean :app:release -Ptarget=all
```

The `release` task runs module tests and the short native render benchmark
before assembling public artifacts. It does not publish or install anything
outside the repository.

## PGO

PGO tasks are optional and are registered only when `-PenablePgo` is present.

Build an instrumented binary:

```bash
./gradlew :app:nativeProfileInstrument -PenablePgo -Ptarget=glibc
./gradlew :app:nativeProfileInstrument -PenablePgo -Ptarget=musl
```

After collecting a profile, build the optimized binary:

```bash
./gradlew :app:nativeProfileBuild \
  -PenablePgo \
  -Ptarget=glibc \
  -PpgoProfile=/absolute/path/to/profile.iprof
```

If `-PpgoProfile` is omitted, the default profile location is
`.local/pgo/default.iprof`. Local profiles are ignored by Git and survive
`clean`.

PGO is an internal optimization step and does not create a separate release
format.

## Output layout

```text
apps/tuink/launcher/build/
├── install/tuink/                 # JVM installDist layout
├── libs/                          # internal app JAR
├── native/nativeCompile/          # glibc native binary
├── native/nativeMuslCompile/      # musl native binary
└── staging/                       # temporary release staging

build/releases/
├── tuink-<version>.zip
├── tuink-<version>-linux-<arch>.tar.gz
├── tuink-<version>-linux-<arch>-musl.tar.gz  # when requested
├── tuink_<version>_<deb-arch>.deb
└── SHA256SUMS
```

`apps/tuink/launcher/build/` and other module `build/` directories are intermediate output.
Only `build/releases/` is the public release output directory.

## Cleaning

Remove generated module output and public release artifacts:

```bash
./gradlew clean
```

The project-local `.gradle/` cache is not a release artifact. The ignored
`.local/pgo/default.iprof` profile is intentionally outside Gradle build
directories and survives `clean`.

## Inspect available tasks

```bash
./gradlew tasks
./gradlew :app:tasks --group build
./gradlew :app:tasks --group distribution
./gradlew help --task :app:release
./gradlew help --task :app:nativeDistTarGz
```

Use `--stacktrace` for a failure trace and `--info` when toolchain discovery or
task inputs need inspection:

```bash
./gradlew :app:nativeGlibc --stacktrace
./gradlew :app:nativeDeb --info
```

## Build configuration layout

- `apps/tuink/launcher/build.gradle`: application entrypoint and dependencies.
- `gradle/scripts/native.gradle`: regular GraalVM binaries.
- `gradle/scripts/pgo.gradle`: optional PGO binaries and tasks.
- `gradle/scripts/distributions.gradle`: staging, archives, and checksums.
- `gradle/scripts/debian.gradle`: Debian filesystem layout and package task.
- `apps/tuink/packaging/`: source-controlled packaging templates and archive scripts.

Intermediate application output stays below `apps/tuink/launcher/build/`. Public ZIP, native
archives, Debian packages, and checksums are written to `build/releases/`.
