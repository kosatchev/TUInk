# Shared engine compatibility probe

This external consumer checks `editor-contract`, `unicode-text`, `core`,
`editor-session` and `highlight-regex` JARs without terminal, JGit or FFM
adapters. It compiles against their public APIs with Java 17, exercises editing,
undo/redo, stale-result rejection, search, text ownership, UTF-16 boundaries,
semantic highlighting and grapheme segmentation on the JVM, then processes the
same JARs with D8 and R8.

Build the inputs with the repository's Java 25 toolchain:

```sh
./gradlew :editor-contract:jar :unicode-text:jar :core:jar :editor-session:jar :highlight-regex:jar
python3 verification/compatibility/android/run.py \
  --jdk /path/to/jdk-25 \
  --runtime-jdk /path/to/jdk-17 \
  --sdk /path/to/Android/Sdk \
  --build-tools 36.0.0 --compile-api 36 --min-api 35
```

The API numbers select a verification configuration, not the application's
minimum supported Android version. The tool uses an installed SDK and does not
download tools, start an emulator or install software on a connected device.
Generated DEX archives go to `build/compatibility/android/`.

The repository's `check` also compiles `verification/compatibility/jvm/PortableCoreApiProbe.java`
through a Java 17 Gradle compile variant with a dependency on `core` alone. This
checks that types in its public API are exported to external consumers; the DEX
probe above intentionally supplies the complete shared runtime graph.

A successful DEX conversion does not prove runtime API availability or Unicode
parity on ART. Local-filesystem methods still present in `core` are transitional
and are not an Android storage API. Android lifecycle, IME, storage providers,
actual device/emulator tests and the product's minimum Android version remain
separate acceptance gates.
