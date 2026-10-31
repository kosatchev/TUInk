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

The shared engine libraries use Java 17 bytecode/API and publish matching Gradle
variants; their desktop integration tests and the application still use Java 25.
Optional JGit and Linux FFM providers are outside the shared runtime dependency
graph. The [external engine probe](../verification/compatibility/android/README.md) runs the
shared JARs, including public session I/O with in-memory storage, on a selected
JDK and compiles them with Android D8/R8. `check` compiles separate Java 17 API
consumers depending only on `core` or `editor-session`. Actual ART,
Android storage/IME and Windows platform acceptance are separate gates.

Linux access ACL support calls libc directly. Neither the tests nor JVM and
native distributions require the `acl` package or a separate `libacl` library.

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
| Run verification | `./gradlew check` | Tests plus architecture/dependency and offline documentation checks |
| Check offline documentation | `./gradlew :app:verifyDistributionDocumentation` | JVM, glibc and musl documentation sets and local Markdown links; no native compilation |
| Build JVM project | `./gradlew clean test build` | Module classes and JARs |
| Run from sources | `./gradlew --console=plain :app:run` | Interactive process |
| Unpacked JVM distribution | `./gradlew :app:dist` | `apps/tuink/launcher/build/install/tuink/` (alias of `installDist`) |
| JVM release ZIP | `./gradlew :app:distZip` | `build/releases/tuink-<version>.zip` |
| Native glibc binary | `./gradlew :app:nativeDist` | `build/native-dist/glibc/tuink` |
| Native musl binary | `./gradlew :app:nativeDist -Pmusl` | `build/native-dist/musl/tuink` |
| Native glibc PGO binary | `./gradlew :app:nativeDist -Ppgo` | `build/native-dist/glibc-pgo/tuink`; existing profile required |
| Native musl PGO binary | `./gradlew :app:nativeDist -Pmusl -Ppgo` | `build/native-dist/musl-pgo/tuink`; existing profile required |
| Native ACL checks | `./gradlew :app:nativeAclCheck -Ptarget=all` | ACL, permissions, raw filename, and errno checks for glibc and static musl |
| Native archive | `./gradlew :app:nativeDistTarGz [-Pmusl] [-Ppgo]` | `build/releases/*.tar.gz`; README, documentation and licenses included |
| All five distribution variants | `./gradlew :app:distributionArchives -Ptarget=all -Ppgo` | JVM ZIP and four native archives; existing PGO profiles required |
| Debian package | `./gradlew :app:nativeDeb [-Ppgo]` | `build/releases/tuink_<version>_<arch>.deb`; glibc only |
| Benchmark harness self-test | `./gradlew :app:perfHarnessTest` | Python fixture, parser, and statistics checks |
| PGO harness self-test | `./gradlew :app:pgoHarnessTest` | Python training-input and profile-merge checks |
| UI harness self-test | `./gradlew :app:uiHarnessTest` | Screen parser and PTY cleanup checks |
| Linux UI regressions | `./gradlew :app:uiRegressionTest` | Real JVM keyboard/pointer/resize/history/recovery checks |
| Fresh PGO training and build | `./gradlew :app:nativeProfileOptimize -Ppgo` | `apps/tuink/launcher/build/native/nativePgoOptimizedCompile/tuink-pgo` |
| Render smoke benchmark | `./gradlew :app:perfSmoke [-Pmusl] [-Ppgo]` | Selected native variant; `build/performance/render/` |
| Full render benchmark | `./gradlew :app:perfCheck [-Pmusl] [-Ppgo]` | Selected native variant; `build/performance/render/` |
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
./gradlew --console=plain :app:run --args='file.txt'
./gradlew --console=plain :app:run --args='--read "my notes.txt"'
./gradlew --console=plain :app:run --args='-- "--notes with spaces.md"'
```

Use Gradle's `--args` option to pass editor options and a filename. The outer
quotes preserve the argument string in the shell; the inner quotes keep a
filename containing spaces together when Gradle parses that string. Use `--`
inside the argument string before a filename beginning with a dash.
The `-PappArgs` property accepts the same quoted argument syntax, for example
`-PappArgs='--read "my notes.txt"'`. Unbalanced quotes are rejected before the
editor starts. If both forms are supplied, `--args` takes precedence.

The editor requires an interactive TTY. An IDE console or redirected stdin may
be detected as a dumb terminal.

Linux CI also runs the separate [terminal UI regression gate](../verification/ui/README.md).
It requires Python 3.10+ and a POSIX PTY, launches the installed JVM distribution
with isolated homes, and leaves diagnostic logs under `build/ui-regression/`.

## Dependency integrity

Dependency versions are locked per module, downloaded artifacts are checked
against `gradle/verification-metadata.xml`, and both the wrapper JAR and Gradle
distribution have pinned SHA-256 checksums. A normal build must not rewrite any
of these files.

After an intentional dependency change, regenerate the lock state and
verification metadata together while resolving the complete CI dependency
graph:

```bash
./gradlew -Ppgo --write-locks --write-verification-metadata sha256 \
  check :app:distZip :app:perfHarnessTest \
  dependencies :editor-contract:dependencies :editor-session:dependencies \
  :unicode-text:dependencies :tuiframe-core:dependencies \
  :tuiframe-jline:dependencies :tuiframe-widgets:dependencies :core:dependencies \
  :editor-diff-jgit:dependencies :editor-storage-jvm:dependencies \
  :render:dependencies :highlight-regex:dependencies \
  :tui:dependencies :app:dependencies
```

Review the resulting coordinate and checksum changes before committing them.
`-Ppgo` registers the PGO configurations so their dependencies are resolved too;
this command does not build native binaries or train profiles.
When upgrading Gradle itself, obtain both wrapper checksums from the official
Gradle distribution index and update `gradle-wrapper.properties` together with
`gradle-wrapper.jar.sha256`.

## JVM distribution

Build an installed JVM layout or ZIP:

```bash
./gradlew :app:dist
./gradlew :app:distZip
```

Run the installed layout:

```bash
./apps/tuink/launcher/build/install/tuink/bin/tuink file.txt
```

`dist` is a short alias of Gradle's standard `installDist`. Both the unpacked
directory and the ZIP contain the same complete distribution, with `bin/`
launchers and `lib/` dependencies. They require Java 25 or newer on the target
machine and include `README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`,
`SECURITY.md`, the two demo GIFs in `docs/assets/`, and `samples/`.
Developer documentation and native-runtime notice directories are omitted.
Links to source-only documents and notices use the packaged source revision
online.

When building an exported source tree, such as a downloaded GitHub source ZIP,
there is no Git metadata from which to read that revision. Pass
`-PdocumentationRef=<full-commit-or-version-tag>` to `check`, distribution and
release commands. Choose the full 40-character commit for a development
snapshot, or the `v<major>.<minor>.<patch>` tag for that exact release archive.
For example, from the matching release's source archive:

```bash
./gradlew check :app:distZip -PdocumentationRef=v0.16.0
```

The build refuses to guess a documentation tag from `version`: development
sources can have the same version as an earlier release. Normal Git checkouts
and worktrees use their own `HEAD` without this option.

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

Build a bare executable, defaulting to glibc without PGO:

```bash
./gradlew :app:nativeDist
./build/native-dist/glibc/tuink file.txt
```

Select musl and/or PGO with independent flags:

```bash
./gradlew :app:nativeDist -Pmusl
./gradlew :app:nativeDist -Ppgo
./gradlew :app:nativeDist -Pmusl -Ppgo
```

Each selected binary is named `tuink`, in a separate output directory:

```text
build/native-dist/glibc/tuink
build/native-dist/musl/tuink
build/native-dist/glibc-pgo/tuink
build/native-dist/musl-pgo/tuink
```

`nativeDist` contains only the executable. Use `nativeDistTarGz` for an archive
with README, documentation, licenses and install scripts. `-Ppgo` requires a
matching existing [PGO profile](#pgo); it never starts training automatically.
Both flags accept an empty value or `true`; use `false` to disable them.
`-PenablePgo` is an exact alias of `-Ppgo`; if both are supplied, their values
must agree.

The explicit `nativeGlibc`, `nativeMusl` and `nativeAll` compile tasks remain
available and honor `-Ppgo`. They write compiler intermediates below
`apps/tuink/launcher/build/native/`. Do not pass a libc selector that contradicts an explicit
task's target.
The legacy `-Ptarget=glibc|musl|all` selector remains supported for automation;
do not combine `-Pmusl` with a conflicting `-Ptarget` value.

Interactive Linux runs use JLine's `exec` provider and require external `tty`
and `stty` commands in `PATH`, including JVM and glibc distributions.
See [runtime dependencies and troubleshooting](packaging.md#complete-release).

Native builds default to `-march=compatibility` so release binaries work on
older x86_64 CPUs. Override it only when the deployment CPU is known:

```bash
./gradlew :app:nativeDist -Pmusl -PnativeMarch=native
```

Use `nativeMarch=native` only for a binary that will run on the same or a known
compatible CPU generation. CPU-specific binaries use
`build/native-dist/<variant>-march-<cpu>/tuink`, and their archive names end in
`-march-<cpu>.tar.gz`. Both `release` and `nativeDeb` require the default
`compatibility` value.

All native variants use Oracle `-O3`, at most two compiler worker threads and
a 3 GiB compiler heap. These build limits do not change the resulting binary's
runtime heap defaults.

## Native release archives

The archive command uses the same flags as `nativeDist`:

```bash
./gradlew :app:nativeDistTarGz
./gradlew :app:nativeDistTarGz -Pmusl
./gradlew :app:nativeDistTarGz -Ppgo
./gradlew :app:nativeDistTarGz -Pmusl -Ppgo
```

Archives are written to `build/releases/` and include the binary, installer,
uninstaller, the same user documentation, demo images and samples as the JVM
distribution, and the complete Oracle runtime notice bundle. Static musl
archives additionally include the musl and zlib notice bundles. `-Ppgo`
selects the optimized archive instead of the ordinary archive; profiles must
already be trained.

The five distribution variants are the JVM ZIP, native glibc, native musl,
native glibc PGO, and native musl PGO. After [training both PGO profiles](#pgo),
assemble all five without running tests, benchmarks, or Debian packaging:

```bash
./gradlew :app:distributionArchives -Ptarget=all -Ppgo
```

Without `-Ppgo`, `distributionArchives` includes only the JVM ZIP and ordinary
native archives selected by `-Pmusl` (default `glibc`). `-Ptarget=all` selects
both libc targets. In this aggregate task, `-Ppgo` adds the selected PGO
archives alongside the ordinary ones; it does not replace them.
PGO archive tasks compile against existing verified profiles; they do not
train automatically or package instrumented binaries, profiles, or receipts.

## Debian package

Build a Debian package from the glibc native binary:

```bash
./gradlew :app:nativeDeb
./gradlew :app:nativeDeb -Ppgo
```

The package is written to:

```text
build/releases/tuink_<version>_<deb-arch>.deb
```

`-Ppgo` selects the optimized glibc binary and requires its existing profile.
Both modes use the same package name, version and output filename: building
one replaces the other, and they cannot be installed side by side.
The Debian task does not use the archive installer and does not invoke `sudo`.
Do not pass `-Pmusl` or `-Ptarget=musl` to `nativeDeb`.

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

Include all five distribution variants, using previously trained PGO profiles:

```bash
./gradlew clean :app:release -Ptarget=all -Ppgo
```

The `release` task runs module tests and smoke benchmarks for every shipped
native variant. With `-Ppgo`, it includes ordinary and PGO archives and uses
the PGO glibc binary in its single Debian package. A musl-only release
(`-Pmusl`) omits the Debian package. The final `SHA256SUMS` covers all selected
release artifacts.

For archive checksums without tests, benchmarks or Debian packaging, use
`:app:releaseChecksums` with the same selection flags. It writes
`ARCHIVE_SHA256SUMS`; the full release writes `SHA256SUMS`, including its DEB
when glibc is selected. Neither task publishes or installs anything outside
the repository.

## PGO

PGO is optional and experimental. It specifically requires **Oracle GraalVM
25.0.4+7.1 with Native Image**: Oracle JDK alone is insufficient, and GraalVM
Community does not support PGO. Its native tasks are registered only with
`-Ppgo` (or its exact legacy alias `-PenablePgo`) and use the same pinned SDK and
`verifyNativeToolchain` preflight as ordinary native builds.
[GraalVM PGO](https://www.graalvm.org/jdk25/reference-manual/native-image/optimizations-and-performance/PGO/)
uses an instrumented training run to guide compilation. Training requires
Python 3.10+ as `python3` on a POSIX system with a pseudo-terminal.

Build the instrumented binary, train, and build the optimized glibc binary:

```bash
./gradlew :app:nativeProfileTrain -Ppgo
./gradlew :app:nativeDist -Ppgo
./build/native-dist/glibc-pgo/tuink file.txt
```

`nativeProfileTrain` first builds the instrumented binary. Low-level
`nativeProfileInstrument` and `nativeProfileBuild` tasks remain available.

For a fresh train-and-build run, use the convenience task:

```bash
./gradlew :app:nativeProfileOptimize -Ppgo
```

The default target is `glibc`. Pass `-Pmusl` to every PGO command for a
separate musl profile and binary; the musl C toolchain is also required.
`-Ptarget=all` runs both targets with separate profiles:

```bash
./gradlew :app:nativeProfileOptimize -Ptarget=all -Ppgo
```

The explicit `nativeProfileGlibcInstrument`, `nativeProfileGlibcTrain`,
`nativeProfileGlibcBuild`, and `nativeProfileGlibcOptimize` tasks select glibc;
corresponding `nativeProfileMusl*` tasks select musl. Do not combine an explicit
task with a contradictory libc selector.

Training uses the current instrumented binary in five checked sessions:
Java/Python/YAML/Rust editing and mouse drag. Each session must exit normally
with code zero; saved bytes are verified in the editing sessions. Fresh
profiles are merged using the selected Oracle SDK. Training is also guarded
by the standalone `:app:pgoHarnessTest`.
Each training editor and the profile-merging utility have a 512 MiB heap cap;
these limits do not change the resulting binary's runtime defaults.

The default merged profile is `.local/pgo/<target>/profile.iprof`, with an
adjacent `profile.iprof.json` receipt. `-PpgoProfile=<path>` selects another
root-relative or absolute path for one selected libc. It is rejected with
`-Ptarget=all`; use `-PpgoGlibcProfile=<path>` and
`-PpgoMuslProfile=<path>` to override the targets independently.
The shared `pgoProfile` follows the selected libc; use the per-target
properties with explicit `nativeProfileGlibc*` or `nativeProfileMusl*` tasks.
Profile path properties require PGO to be enabled. If both a shared path and
the selected libc's path are provided, they must name the same file.
The receipt binds the profile to its classpath, SDK, compiler arguments,
`nativeMarch`, target and training-harness SHA-256. The optimized build rejects
missing, empty or mismatched profiles and receipts; retrain after changing these inputs.
The default files are outside Gradle build directories, survive `clean` and
are ignored by Git. For a custom path, choose an ignored location outside
build directories. Do not commit profiles or receipts or copy a profile
between libc targets.

The compiler's intermediate PGO binaries remain separate build products:

```text
apps/tuink/launcher/build/native/nativePgoOptimizedCompile/tuink-pgo
apps/tuink/launcher/build/native/nativeMuslPgoOptimizedCompile/tuink-musl-pgo
```

Package verified optimized binaries with the same archive command and `-Ppgo`:

```bash
./gradlew :app:nativeDistTarGz -Ppgo
./gradlew :app:nativeDistTarGz -Pmusl -Ppgo
```

Explicit `nativePgoTarGz`, `nativeMuslPgoTarGz` and `nativePgoDistTarGz`
tasks remain available. `distributionArchives` and `release` include these PGO
archives only when `-Ppgo` or the legacy `-PenablePgo` is enabled. Missing or
stale profiles fail the build instead of starting a training run. Profiles,
receipts, training measurements, and instrumented binaries are never included
in the archives.

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
├── tuink-<version>-linux-<arch>-pgo.tar.gz   # when PGO is requested
├── tuink-<version>-linux-<arch>-musl-pgo.tar.gz
├── tuink_<version>_<deb-arch>.deb
└── SHA256SUMS

build/native-dist/
├── glibc/tuink                    # nativeDist default
├── musl/tuink                     # -Pmusl
├── glibc-pgo/tuink                # -Ppgo
└── musl-pgo/tuink                 # -Pmusl -Ppgo
```

`apps/tuink/launcher/build/` and other module `build/` directories are intermediate output.
`build/native-dist/` contains bare binaries for local use. Complete release
archives, documentation and licenses are assembled in `build/releases/`.

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
- `gradle/scripts/build-variants.gradle`: shared libc and PGO selection.
- `gradle/scripts/native.gradle`: regular GraalVM binaries.
- `gradle/scripts/pgo.gradle`: optional PGO binaries and tasks.
- `gradle/scripts/distributions.gradle`: staging and JVM/native archives.
- `gradle/scripts/debian.gradle`: Debian filesystem layout and package task.
- `gradle/scripts/release.gradle`: archive and complete-release checksum manifests.
- `gradle/scripts/performance.gradle`: benchmarks of the selected native variants.
- `apps/tuink/packaging/`: source-controlled packaging templates and archive scripts.

Intermediate application output stays below `apps/tuink/launcher/build/`. Public ZIP, native
archives, Debian packages, and checksums are written to `build/releases/`.
