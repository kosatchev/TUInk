# Building TUInk

## Requirements

JVM builds require Java 17 and use the checked-in Gradle wrapper.

```bash
./gradlew clean test build
```

Native builds additionally require GraalVM with `native-image` and a system C
toolchain.

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

## Build configuration layout

- `apps/tuink/launcher/build.gradle`: application entrypoint and dependencies.
- `gradle/scripts/native.gradle`: regular GraalVM binaries.
- `gradle/scripts/pgo.gradle`: optional PGO binaries and tasks.
- `gradle/scripts/distributions.gradle`: staging, archives, and checksums.
- `gradle/scripts/debian.gradle`: Debian filesystem layout and package task.
- `apps/tuink/packaging/`: source-controlled packaging templates and archive scripts.

Intermediate application output stays below `apps/tuink/launcher/build/`. Public ZIP, native
archives, Debian packages, and checksums are written to `build/releases/`.
