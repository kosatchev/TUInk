# Oracle GraalVM 25.0.4 license notices

TUInk's native executables are built with Oracle GraalVM 25.0.4,
SDK directory `graalvm-jdk-25.0.4+7.1`, Java `25.0.4+7-LTS-jvmci-b01`.
They contain Oracle runtime components covered by the
[GraalVM Free Terms and Conditions, including License for Early Adopter Versions](LICENSE.txt)
and separately licensed components under their own terms.
Redistribution of the Oracle components is subject to the GFTC conditions,
including its restriction on fees associated with distribution or use.
The complete copied terms control; this description does not replace them.

TUInk's own source code remains under the [Apache License 2.0](../../LICENSE).
The JVM distribution contains application JARs and launch scripts, without a
bundled Java runtime or this notice directory. The Oracle runtime terms apply
to the Oracle components of the native executables.
Application dependency notices are in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).

## Exact SDK documentation

These files are copied byte for byte from the pinned Linux x64 SDK:

- `LICENSE.txt`: complete GFTC, including the Early Adopter license.
- `NOTICE.txt` and `GRAALVM-README.md`: SDK notices and readme.
- `license-information-user-manual.html`: the unmodified HTML member of the
  SDK's `license-information-user-manual.zip`, including Native Image notices.
- `legal/`: the complete SDK module notice tree, including JDK copyright
  notices, Oracle No-Fee Terms and Conditions, and third-party license texts.

The complete notice inventory is retained conservatively. It does not imply
that every SDK module or optional language pack is present in TUInk's native
executables. No SDK binaries are included in this documentation directory.
`SHA256SUMS` records the hashes of the copied upstream files; this README and
the checksum manifest itself are project documentation.

SDK source:
[Oracle GraalVM 25.0.4 Linux x64 archive](https://download.oracle.com/graalvm/25/archive/graalvm-jdk-25.0.4_linux-x64_bin.tar.gz).
Archive SHA-256:
`76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276`.
Official references:
[GFTC](https://www.oracle.com/downloads/licenses/graal-free-license.html),
[GraalVM licensing FAQ](https://www.graalvm.org/faq/), and
[Oracle GraalVM 25 Licensing Information User Manual](https://docs.oracle.com/en/graalvm/jdk/25/docs/licensing-information/).

Native archives include this directory as `licenses/oracle-graalvm-25.0.4/`;
Debian packages include it under `/usr/share/doc/tuink/licenses/`.
The archive installer keeps it under `/opt/tuink/licenses/`.
