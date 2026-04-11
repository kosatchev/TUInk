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
