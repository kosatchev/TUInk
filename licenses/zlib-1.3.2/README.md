# zlib 1.3.2 license

The static musl native archive embeds the standard zlib 1.3.2 library.
`LICENSE` is copied byte for byte from its pinned source archive; its copyright
and complete zlib license remain unmodified. Optional source `contrib/`
utilities are not part of this static library build.

The glibc native archive and Debian package use the operating system's zlib
instead. Only the static musl archive includes this notice directory; the
glibc archive, Debian package and JVM distribution omit it. The JVM
distribution contains no zlib binary. `SHA256SUMS` records the copied
license's hash; this README is TUInk's provenance documentation.

Source archive:
[zlib 1.3.2](https://zlib.net/fossils/zlib-1.3.2.tar.gz).
Archive SHA-256:
`bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16`.
