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

The extracted archive's README provides the user reference and any bundled demo
images offline. The system installer copies the executable, scripts, README, license,
and third-party notices; its README links to documentation and images on the
archive's source commit online, embedded during packaging. A source archive
without Git metadata uses its matching `v<version>` release tag; an explicit
`-PdocumentationRef=<full-commit-or-version-tag>` supports exported snapshots.

The installer marks `/opt/tuink` as an archive-managed installation. It
refuses to replace an existing non-TUInk file or symlink at
`/usr/local/bin/tuink`, and it refuses to adopt an unmarked `/opt/tuink`
directory. Only the current marker format is supported; markerless and unknown
installations are left unchanged.

The uninstaller removes only the managed symlink and the known files installed
by the archive. It refuses to continue if the installation marker is missing,
the symlink points elsewhere, or `/opt/tuink` contains unexpected entries.

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
```

It does not execute the archive `install.sh`, use `/opt`, or invoke `sudo`
during package assembly. The current native binary requires glibc 2.34 or
newer and zlib 1.1.4 or newer; those minimums are declared in the package
metadata.

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
