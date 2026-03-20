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
- `org.jline:jline:3.30.6`

License:
- BSD 3-Clause License

Source:
- https://github.com/jline/jline3
- Maven coordinates listed in project build files.

Notes:
- Used by TUI terminal frontend.

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

---

If additional third-party dependencies are introduced, this file should be updated accordingly.
