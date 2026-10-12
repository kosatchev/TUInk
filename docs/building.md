# Building TUInk

Run the built-in command reference from the repository root:

```bash
./gradlew buildHelp
```

This task only prints help. It does not compile sources or create artifacts.

## Requirements

JVM builds use **Java 25** from any vendor, without a required patch version.
Use the checked-in Gradle 9.8.1 wrapper with a compatible JDK, such as 17,
21 or 25. The configured Java 25 toolchain handles compilation, tests and
application runs; the Foojay resolver downloads a matching JDK if none is
installed. The first download requires network access. `GRAALVM_HOME` is
required only for native builds.
The packaged JVM ZIP requires Java 25 or newer. A step-by-step guide for a new
computer is available in Russian: [Перенос, сборка и запуск](setup-ru.md).

On Linux, install the `acl` package (`getfacl` and `setfacl`) for the tests and
for saving existing documents. This runtime requirement applies to both JVM
and native distributions; the Debian package declares it as a dependency.

Check the active JVM and Gradle environment:

```bash
./gradlew --version
```

Do not rely on a system Gradle installation: use `./gradlew` so the project
uses the checked-in wrapper version.

Native builds and release tasks additionally require:

- the explicitly selected SDK described in [Native toolchain](#native-toolchain);
- a system C toolchain;
- the platform libraries required by GraalVM;
- a musl-capable toolchain for the static musl target.

Benchmark tasks require Python 3.10+ available as `python3` on a POSIX system.
The standalone `:app:perfHarnessTest` and `:app:pgoHarnessTest` validate their
harnesses without building or launching the native editor. They are separate
from `check` and run explicitly in CI. `:app:perfSmoke` and `:app:perfCheck` also
require a POSIX pseudo-terminal and the native toolchain; both run the harness self-test first.
The short benchmark is part of the complete release task.
Optional [PGO builds](#pgo) use the same SDK and require a POSIX pseudo-terminal
for training.

Debian packaging additionally requires Linux with Python 3.10+ as `python3`,
`dpkg-deb`, `dpkg`, `gzip`, and `strip`/`readelf` from `binutils`. Before package
assembly, `readelf` inspects the staged binary and `dpkg --compare-versions`
checks that the minimum versions in `Depends` cover its required glibc/zlib ABI.
`lintian` is optional and is used only for package validation.

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
| PGO harness self-test | `./gradlew :app:pgoHarnessTest` | Python training-input and profile-merge checks |
| Optional PGO glibc binary | `./gradlew :app:nativeProfileOptimize -PenablePgo` | `apps/tuink/launcher/build/native/nativePgoOptimizedCompile/tuink-pgo` |
| Render smoke benchmark | `./gradlew :app:perfSmoke` | `build/performance/render/` |
| Full render benchmark | `./gradlew :app:perfCheck` | `build/performance/render/` |
| Complete release | `./gradlew clean :app:release` | ZIP, glibc archive, DEB, checksums |

## JVM development build

Build and test all modules:

```bash
./gradlew clean check build
```

To verify the JVM distribution along with tests:

```bash
./gradlew --console=plain clean check :app:distZip
```

Run only a specific module's tests when iterating:

```bash
./gradlew :tuiframe-core:test
./gradlew :tuiframe-jline:test
./gradlew :tuiframe-widgets:test
./gradlew :editor-contract:test
./gradlew :core:test
./gradlew :editor-session:test
./gradlew :render:test
./gradlew :tui:test
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
  :unicode-text:dependencies :tuiframe-core:dependencies \
  :tuiframe-jline:dependencies :tuiframe-widgets:dependencies :core:dependencies \
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

The JVM distribution requires Java 25 or newer on the target machine. It includes
`README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, `SECURITY.md`, available
demo images and `samples/`. Developer documentation stays in the source tree;
packaged links use the build's immutable source revision online.

Gradle also creates module JARs as internal build products. They are not
standalone fat/executable JAR distributions and do not carry the complete
runtime dependency set. Use `:app:distZip` when a portable JVM release is
needed.

## Native toolchain

Native and release tasks require exactly Oracle GraalVM 25.0.4+7.1 with Java
25. Set both `JAVA_HOME` and `GRAALVM_HOME` to the absolute path of that
distribution. Gradle, compilation, tests, JVM runs, and native-image builds use
this one JDK. Automatic native toolchain discovery is disabled, and the
preflight verifies that both variables select the pinned installation.

The pinned Linux x86_64 archive is:

```text
https://download.oracle.com/graalvm/25/archive/graalvm-jdk-25.0.4_linux-x64_bin.tar.gz
SHA-256: 76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276
```

Verify the downloaded archive before extracting it:

```bash
printf '%s  %s\n' \
  76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276 \
  graalvm-jdk-25.0.4_linux-x64_bin.tar.gz \
  | sha256sum -c -
```

After extraction, select the toolchain and run the same preflight used by every
native-image build task:

```bash
export GRAALVM_HOME=/absolute/path/to/graalvm-jdk-25.0.4+7.1
export JAVA_HOME="$GRAALVM_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:verifyNativeToolchain
```

The preflight checks the distribution's `release` metadata, both `java` and
`native-image`, and rejects a missing, relative, non-Oracle, or mismatched
toolchain.
CI and the release workflow use the same download/checksum recipe through
[the shared setup action](https://github.com/kosatchev/tuink/blob/main/.github/actions/setup-graalvm/action.yml).

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

Interactive musl runs use JLine's `exec` provider and require external `tty`
and `stty` commands in `PATH`, as well as the ACL tools for saving existing
files. See [runtime dependencies and troubleshooting](packaging.md#complete-release).

Native builds default to `-march=compatibility` so release binaries work on
older x86_64 CPUs. Override it only when the deployment CPU is known:

```bash
./gradlew :app:nativeMusl -PnativeMarch=native
```

Use `nativeMarch=native` only for a binary that will run on the same or a known
compatible CPU generation. Release builds should keep the default
`compatibility` value.

All native variants use Oracle `-O3`, at most two compiler worker threads and
a 3 GiB compiler heap. These build limits do not change the resulting binary's
runtime heap defaults.

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

PGO is optional and experimental. Its native tasks are registered only with
`-PenablePgo` and use the same pinned SDK and `verifyNativeToolchain` preflight
as ordinary native builds. [GraalVM PGO](https://www.graalvm.org/jdk25/reference-manual/native-image/optimizations-and-performance/PGO/)
uses an instrumented training run to guide compilation. Training requires
Python 3.10+ as `python3` on a POSIX system with a pseudo-terminal.

Build the instrumented binary, train, and build the optimized glibc binary:

```bash
./gradlew :app:nativeProfileInstrument -PenablePgo
./gradlew :app:nativeProfileTrain -PenablePgo
./gradlew :app:nativeProfileBuild -PenablePgo
./apps/tuink/launcher/build/native/nativePgoOptimizedCompile/tuink-pgo file.txt
```

For a fresh train-and-build run, use the convenience task:

```bash
./gradlew :app:nativeProfileOptimize -PenablePgo
```

The default target is `glibc`. Pass `-Ptarget=musl` to every PGO command for a
separate musl profile and binary; the musl C toolchain is also required.
`-Ptarget=all` is rejected so glibc and musl profiles cannot be mixed.

Training uses the current instrumented binary in five checked sessions:
Java/Python/YAML/Rust editing and mouse drag. Each session must exit normally
with code zero; saved bytes are verified in the editing sessions. Fresh
profiles are merged using the selected Oracle SDK. Training is also guarded
by the standalone `:app:pgoHarnessTest`.
Each training editor and the profile-merging utility have a 512 MiB heap cap;
these limits do not change the resulting binary's runtime defaults.

The default merged profile is `.local/pgo/<target>/profile.iprof`, with an
adjacent `profile.iprof.json` receipt. `-PpgoProfile=<path>` selects another
root-relative or absolute path for both training and the optimized build.
The receipt binds the profile to its classpath, SDK, compiler arguments,
`nativeMarch`, target and training-harness SHA-256. The optimized build rejects
missing, empty or mismatched profiles and receipts; retrain after changing these inputs.
The default files are outside Gradle build directories, survive `clean` and
are ignored by Git. For a custom path, choose an ignored location outside
build directories. Do not commit profiles or receipts or copy a profile
between libc targets.

PGO binaries are separate build products:

```text
apps/tuink/launcher/build/native/nativePgoOptimizedCompile/tuink-pgo
apps/tuink/launcher/build/native/nativeMuslPgoOptimizedCompile/tuink-musl-pgo
```

The regular archives, Debian package and release task package the ordinary
Oracle O3 binaries. PGO profiles and optimized binaries remain separate local
outputs.

Evaluate PGO on representative editing and mouse workloads against the ordinary
Oracle O3 binary from the same SDK. Measure CPU, wall time, memory and frame
latency separately; a profile does not guarantee an improvement across all
operations. See [benchmark guidance](../verification/performance/README.md#optional-pgo-training).

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
`.local/pgo/<target>/` profiles and their receipts are intentionally outside
Gradle build directories and survive `clean`.

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
