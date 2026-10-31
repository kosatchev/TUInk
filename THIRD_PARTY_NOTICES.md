# THIRD_PARTY_NOTICES

TUInk's own source code is licensed under Apache License 2.0. Its distributions
also include third-party software components under the terms listed below.
The JVM distribution does not bundle a Java runtime or native-runtime license
directories.

Native executables include runtime components from Oracle GraalVM 25.0.4.
These Oracle components are subject to the GraalVM Free Terms and Conditions
(GFTC), including License for Early Adopter Versions, and the separate terms
of bundled third-party components. GFTC redistribution is subject to its
conditions, including the restriction on fees associated with distribution or
use. Exact SDK licenses, notices, and attribution are bundled in
[licenses/oracle-graalvm-25.0.4/](licenses/oracle-graalvm-25.0.4/README.md).
These terms apply to the Oracle components of native executables; compiling
the JVM ZIP with the SDK does not bundle those runtime components.
Native archives and Debian packages include the complete Oracle notice bundle.
Links to notice bundles absent from a packaged variant point to that package's
source revision online.

## 1) Gradle Wrapper scripts and wrapper JAR

Files:
- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`

License:
- Apache License, Version 2.0
- SPDX: `Apache-2.0`

Source:
- https://gradle.org/

Notes:
- These files are generated/provided by Gradle Wrapper tooling.

## 2) JLine

Artifact:
- `org.jline:jline-terminal:3.30.17`
- `org.jline:jline-terminal-jni:3.30.17`
- `org.jline:jline-native:3.30.17`

License:
- BSD 3-Clause License

Source:
- https://github.com/jline/jline3
- Maven coordinates listed in project build files.

Notes:
- Used by the TUI terminal frontend. The aggregate JLine artifact and its
  unused remote telnet/SSH modules are not included.
- The backend-independent cell-width compatibility policy lives in
  `tuiframe-core` under `dev.tuiframe.text` and follows JLine 3.30.17. It
  explicitly preserves zero width for U+061C, U+2064 and U+2066–U+2069.
  Standalone zero-width clusters use visible one-cell placeholders.
  Reusable line wrapping and version-aware terminal layout
  caching also live there. The editor core reaches terminal layout only
  through its terminal-neutral `EditorTextLayout` port; the JLine-compatible
  policy is connected by `TerminalEditorTextLayout` in the render adapter.

The cell-width ranges are derived from `org.jline.utils.WCWidth` and retain
the following BSD 3-Clause notice:

Copyright (c) 2002-2025, the original author(s). All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## 3) JGit

Artifact:
- `org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r`

License:
- Eclipse Distribution License v1.0 (EDL-1.0)
- Includes `SHA-1 UbcCheck` component under MIT License (as documented in artifact `about.html`)

Source:
- https://github.com/eclipse-jgit/jgit
- Maven coordinates listed in project build files.

Notes:
- Used by core editor engine for gutter-diff computation.

Eclipse Distribution License - v 1.0

Copyright (c) 2007, Eclipse Foundation, Inc. and its licensors.

All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice,
  this list of conditions and the following disclaimer.
- Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.
- Neither the name of the Eclipse Foundation, Inc. nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

SHA-1 UbcCheck - MIT

Copyright (c) 2017:

    Marc Stevens
    Cryptology Group
    Centrum Wiskunde & Informatica
    P.O. Box 94079, 1090 GB Amsterdam, Netherlands
    marc@marc-stevens.nl

    Dan Shumow
    Microsoft Research
    danshu@microsoft.com

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## 4) JavaEWAH

Artifact:
- `com.googlecode.javaewah:JavaEWAH:1.2.3`

License:
- Apache License, Version 2.0

Source:
- https://github.com/lemire/javaewah

## 5) SLF4J API

Artifact:
- `org.slf4j:slf4j-api:1.7.36`

License:
- MIT License

Source:
- https://www.slf4j.org/

MIT License text:

Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)
All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## 6) Apache Commons Codec

Artifact:
- `commons-codec:commons-codec:1.17.0`

License:
- Apache License, Version 2.0

Source:
- https://commons.apache.org/proper/commons-codec/

The Apache License, Version 2.0 is reproduced in `LICENSE`. The artifact's
`META-INF/NOTICE.txt` reads:

    Apache Commons Codec
    Copyright 2002-2024 The Apache Software Foundation

    This product includes software developed at
    The Apache Software Foundation (https://www.apache.org/).

---

## 7) Static musl native target: musl and zlib

The static musl native archive embeds musl 1.2.6 and standard zlib 1.3.2.
Their exact pinned-source notices are bundled separately from the SDK's
conservative license inventory:

- [musl 1.2.6](licenses/musl-1.2.6/README.md): MIT and the compatible component
  terms reproduced in `COPYRIGHT` and `THIRD_PARTY_NOTICES.txt`.
- [zlib 1.3.2](licenses/zlib-1.3.2/README.md): the complete unmodified zlib
  license and copyright notice in `LICENSE`.

The glibc native archive and Debian package use the host's libc and zlib; they
do not bundle these static library copies. The JVM ZIP also contains no such
runtime libraries. Only the static musl archives include these two notice
directories, with their versions, source URLs, SHA-256 hashes, and scope.

---

If additional third-party dependencies are introduced, this file should be updated accordingly.
