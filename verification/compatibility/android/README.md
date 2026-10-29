# Shared engine compatibility probe

This external consumer checks `editor-contract`, `unicode-text`, `core`,
`editor-session` and `highlight-regex` JARs without terminal, JGit or FFM
adapters. It compiles against their public APIs with Java 17, exercises editing,
undo/redo, stale-result rejection, search, text ownership, UTF-16 boundaries,
semantic highlighting and grapheme segmentation on the JVM. It also loads,
edits and saves a document through the public session API and an in-memory
storage adapter, checking owner/worker phases, BOM, mixed EOL and savepoints.
The save coordinator also rejects concurrent edits and retains a dirty savepoint
after an uncertain write. Recovery runs through the portable coordinator and
checks that detaching preserves a draft until explicit discard.
It then processes the same JARs and fixture with D8 and R8.

Ordinary typing, coalesced Undo/Redo and replacement also run through typed
session commands with document/view targets. They use the same dispatch as TUI
input, without exposing a mutable core view to the consumer.

The public `EditorWorkspace` probe admits two views of one document, preserves
selection across batch admission and view retirement, and verifies shared Undo
with independent positions. Deferred commands to a retired view are rejected;
the surviving view supplies recovery. A document cannot be admitted by two
workspace owners, and a foreign thread cannot retire a view. These cases run on
the selected JVM 17 runtime and are also included in the D8/R8 inputs.

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
and `PortableSessionIoApiProbe.java` through Java 17 Gradle compile variants,
depending only on `core` and `editor-session` respectively. These check API
dependency exports; the DEX probe above supplies the complete shared runtime
graph, without the JVM filesystem adapter.

A successful DEX conversion does not prove runtime API availability or Unicode
parity on ART. Filesystem access is isolated in `editor-storage-jvm`; an Android
adapter for the neutral resource protocol is not yet implemented. Android lifecycle, IME, storage providers,
actual device/emulator tests and the product's minimum Android version remain
separate acceptance gates.
