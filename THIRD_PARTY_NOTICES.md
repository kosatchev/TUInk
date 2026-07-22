# THIRD_PARTY_NOTICES

This project includes third-party software components.

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
- `org.jline:jline-terminal:3.30.6`
- `org.jline:jline-terminal-jni:3.30.6`
- `org.jline:jline-native:3.30.6`

License:
- BSD 3-Clause License

Source:
- https://github.com/jline/jline3
- Maven coordinates listed in project build files.

Notes:
- Used by the TUI terminal frontend. The aggregate JLine artifact and its
  unused remote telnet/SSH modules are not included.
- The temporary backend-independent cell-width compatibility policy preserves
  the behavior of JLine 3.30.6 while terminal metrics are moved out of the
  editor core.

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
- `org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r`

License:
- Eclipse Distribution License v1.0 (EDL-1.0)
- Includes `SHA-1 UbcCheck` component under MIT License (as documented in artifact `about.html`)

Source:
- https://github.com/eclipse-jgit/jgit
- Maven coordinates listed in project build files.

Notes:
- Used by core editor engine for gutter-diff computation.

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

## 6) Apache Commons Codec

Artifact:
- `commons-codec:commons-codec:1.17.0`

License:
- Apache License, Version 2.0

Source:
- https://commons.apache.org/proper/commons-codec/

---

If additional third-party dependencies are introduced, this file should be updated accordingly.
