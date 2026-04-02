# Packaging TUInk

## Complete release

Build the tested JVM ZIP, glibc archive, Debian package, and checksum manifest:

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
during package assembly.

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
