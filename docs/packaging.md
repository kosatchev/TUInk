# Packaging TUInk

## Complete release

Build the tested JVM ZIP, glibc archive, Debian package, and checksum manifest.
The task runs module tests and a short native PTY render benchmark before
assembling the artifacts:

```bash
./gradlew clean :app:release
```

Outputs are written to:

```text
build/releases/
├── tuink-<version>.zip
├── tuink-<version>-linux-<arch>.tar.gz
├── tuink_<version>_<deb-arch>.deb
└── SHA256SUMS
```

Release archives use reproducible file order and normalized timestamps.
`SOURCE_DATE_EPOCH` can be set explicitly for Debian package timestamps.
Benchmark results are written separately below `build/performance/render/`;
they are diagnostic build output and are not included in release archives.

The JVM ZIP, native archives and Debian package include user documentation,
security information, available demo images and samples. Developer documentation
remains in the source tree; packaged links use the build's source revision
online. Runtime notice directories are selected for each artifact.

Runtime notice bundles are selected from the checked-in `licenses/` directory
without reading the build machine's SDK at packaging time. TUInk's own source
code remains Apache-2.0. Native executables contain Oracle GraalVM 25.0.4
runtime components under GFTC (including the Early Adopter license) and
separately licensed components. GFTC redistribution is subject to its
conditions, including its restriction on fees associated with distribution or
use. Exact licenses, notices, SDK module texts, checksums, and scope are in
[the versioned notice bundle](../licenses/oracle-graalvm-25.0.4/README.md).
The complete Oracle bundle is included in every native archive and Debian
package. The JVM directory and ZIP contain no Java runtime or native-runtime
notice directories.

The static musl archive additionally embeds musl 1.2.6 and zlib 1.3.2; their
exact source notices and provenance are in
[licenses/musl-1.2.6/](../licenses/musl-1.2.6/README.md) and
[licenses/zlib-1.3.2/](../licenses/zlib-1.3.2/README.md).
Only static musl archives include the musl and zlib notice directories. The
glibc archive and Debian package use host libraries for libc and zlib and omit
those two bundles. PGO does not change the notice selection.

All Linux distributions preserve access ACLs through libc calls without
requiring the `acl` package or a separate `libacl` library. JVM runs use Java
25's Foreign Function and Memory API; native binaries link the calls at build
time, including in the fully static musl executable. An ACL read, write, or
verification failure rejects the save before the original file is replaced.

The static musl executable uses JLine's `exec` terminal provider. Interactive
startup also requires the external `tty` and `stty` commands in `PATH` (provided
by `coreutils` on Debian/Ubuntu). Static linking removes the shared-library
requirement, while these terminal commands remain runtime
dependencies. Check them on minimal systems or when using a restricted `PATH`:

```bash
command -v tty stty
```

If the terminal commands are missing, startup can report
`Interactive terminal is not available (dumb terminal)` and suggest a real TTY
even when it already runs in one. Restore the commands in `PATH` and launch the
editor from an interactive terminal.

## Publishing on GitHub

Tagged snapshots that include `.github/workflows/release.yml` can publish the
complete release through GitHub Actions. The workflow first appears in
`v0.13.1`; earlier tags have no automated publisher. Each tag must match
`version` in `gradle.properties` and include `docs/releases/<version>.md`.
Pushing such a tag starts its own release workflow.

To publish an existing tag that has no GitHub release yet, select that tag as
the workflow ref:

```bash
gh workflow run release.yml --ref v0.13.1
```

The equivalent REST request through GitHub CLI is:

```bash
gh api --method POST repos/kosatchev/tuink/actions/workflows/release.yml/dispatches \
  -f ref=v0.13.1
```

Both commands run the workflow stored at the release tag. There is no separate
`tag` input. Running from `main` or another branch is rejected before checkout;
a tag moved after the run started is rejected before building. This keeps each
release's build steps and verification gates together with its source version.
GitHub also requires the workflow to be present on the repository's default
branch before it accepts manual dispatches. See the
[GitHub CLI ref option](https://cli.github.com/manual/gh_workflow_run) and
[workflow dispatch API](https://docs.github.com/en/rest/actions/workflows#create-a-workflow-dispatch-event).

Publication refuses to replace an existing release, including a draft. It
verifies every uploaded artifact before making the draft public; the highest
published stable TUInk version remains Latest when older tags are published.

## Native archives

Build glibc, musl, or both:

```bash
./gradlew :app:nativeDistTarGz
./gradlew :app:nativeDistTarGz -Ptarget=musl
./gradlew :app:nativeDistTarGz -Ptarget=all
```

Each archive is assembled from a staging directory under
`apps/tuink/launcher/build/staging/`. It contains:

```text
tuink-<version>-linux-<arch>/
├── tuink
├── install.sh
├── uninstall.sh
├── README.md
├── LICENSE
├── THIRD_PARTY_NOTICES.md
├── SECURITY.md
├── docs/assets/
├── licenses/
└── samples/
```

Install an extracted archive:

```bash
tar -xzf tuink-<version>-linux-<arch>.tar.gz
cd tuink-<version>-linux-<arch>
./install.sh
```

The archive installer uses `/opt/tuink` and creates
`/usr/local/bin/tuink`.

The extracted archive's README provides the user reference and bundled demo
images offline. The system installer copies the executable, scripts, README,
license, third-party notices, and all notice bundles selected for that native
variant. It leaves images, samples and `SECURITY.md` in the extracted archive;
its README links to images, the security policy and developer documentation on
the archive's source commit online, embedded during packaging. A source archive
without Git metadata uses its matching `v<version>` release tag; an explicit
`-PdocumentationRef=<full-commit-or-version-tag>` supports exported snapshots.

The installer marks `/opt/tuink` as an archive-managed installation. It
refuses to replace an existing non-TUInk file or symlink at
`/usr/local/bin/tuink`, and it refuses to adopt an unmarked `/opt/tuink`
directory. Only the current marker format is supported; markerless and unknown
installations are left unchanged.

The installer records the copied license filenames in
`/opt/tuink/.tuink-license-files`. License source files and destination paths
must be regular contained paths; symlinked license files or parent directories
are refused before installation changes. The manifest supports only the
current format.

The uninstaller removes only the managed symlink and the known files installed
by the archive. It refuses to continue if the installation marker is missing,
the symlink points elsewhere, or `/opt/tuink` contains unexpected entries.
Under `licenses/`, it removes only the files recorded by the installer and
empty parent directories belonging to those paths. Foreign files and empty
foreign directories remain, together with their containing
directories; the managed executable, launcher, and installation marker are
still removed. Such remaining directories are unmarked and are not adopted
by a later install.
Executable and script destinations are treated as files: directories,
including symlinks to foreign directories, are rejected before replacement.

Test archive installation, replacement, link containment, refusal, and cleanup
using temporary directories and a sandboxed `sudo` substitute:

```bash
python3 -B apps/tuink/packaging/archive/test_archive_install.py
```

## Debian package

Build the package from the glibc native binary:

```bash
./gradlew :app:nativeDeb
```

Do not use `-Ptarget=musl` for this task. The package installs:

```text
/usr/bin/tuink
/usr/share/doc/tuink/
/usr/share/doc/tuink/examples/
/usr/share/doc/tuink/licenses/oracle-graalvm-25.0.4/
```

It does not execute the archive `install.sh`, use `/opt`, or invoke `sudo`
during package assembly. The current native binary requires glibc 2.34 or
newer and zlib 1.2.2 or newer; those minimums are declared in the package
metadata. Before assembly, `nativeDeb` uses Python 3.10+, `readelf` and `dpkg` to
check the staged binary's required library versions against `Depends`. A build
that needs newer versions or additional shared libraries fails instead of
producing a package with incomplete dependencies. The fully static musl archive
does not use these host libraries or Debian dependencies.

Test the dependency checker without building a native image:

```bash
python3 -B apps/tuink/packaging/debian/test_native_dependencies.py
```

Inspect and validate the result:

```bash
dpkg-deb --info build/releases/tuink_<version>_<arch>.deb
dpkg-deb --contents build/releases/tuink_<version>_<arch>.deb
lintian build/releases/tuink_<version>_<arch>.deb
```

Install locally:

```bash
sudo apt install ./build/releases/tuink_<version>_<arch>.deb
```
