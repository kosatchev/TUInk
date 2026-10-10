# musl 1.2.6 notices

The static musl native archive embeds musl 1.2.6 and includes this notice
directory. The glibc native archive and Debian package use the operating
system's libc instead. Those artifacts and the JVM distribution omit this
directory.

`COPYRIGHT` is copied byte for byte from the pinned source archive. It includes
the complete MIT license, contributor attribution, and component notice index.
`THIRD_PARTY_NOTICES.txt` reproduces the original C/assembly comment blocks
containing copyright, permission, or license notices from 128 source files
(130 blocks), including the BSD and math-library terms referenced by
`COPYRIGHT`. The comment blocks are unmodified; source-path headings and the
introductory text identify their origin.

This conservative inventory includes notices for architectures and functions
that may not be present in TUInk's x86_64 executable. No musl library binaries
or implementation code are distributed in this documentation directory.
`SHA256SUMS` covers the copied copyright file and collected source notices.

Source archive:
[musl 1.2.6](https://musl.libc.org/releases/musl-1.2.6.tar.gz).
Archive SHA-256:
`d585fd3b613c66151fc3249e8ed44f77020cb5e6c1e635a616d3f9f82460512a`.
