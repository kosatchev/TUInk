package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EditorEnginePasteMemoryTest {
    @Test void terminalPasteIsRejectedBeforeDeletingTheSelectionOn32MiB() throws Exception { probe("terminal", 32); }
    @Test void clipboardPasteKeepsSelectionMetadataAndHistoryOn32MiB() throws Exception { probe("clipboard", 32); }
    @Test void clipboardFromAnAdmittedFileCannotExhaustTheHeapOn64MiB() throws Exception { probe("loaded", 64); }
    @Test void anAdmittedUnicodePasteRemainsRecoverableAndUndoableOn32MiB() throws Exception { probe("admitted", 32); }

    private static void probe(String mode, int heapMiB) throws Exception {
        String classpath = String.join(File.pathSeparator, location(EditorEnginePasteMemoryTest.class),
            location(EditorEngine.class), location(GraphemeText.class), location(LanguageRegistry.class), LinuxXattrTestRuntime.classpath());
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx" + heapMiB + "m", "-cp", classpath, MemoryProbe.class.getName(), mode)
            .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Paste admission probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class MemoryProbe {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("admitted")) {
                admitted();
                return;
            }
            EditorClipboard clipboard = new EditorClipboard();
            EditorEngine engine = engine(List.of("one", "two", "three"), clipboard);
            if (args[0].equals("loaded")) {
                Path file = Files.createTempFile("tuink-paste-memory-", ".txt");
                try {
                    Files.writeString(file, "x\n".repeat(190_000));
                    engine.openExisting(JvmDocumentStorage.resource(file));
                } finally {
                    Files.delete(file);
                }
            }
            engine.insertInput("X");
            engine.insertNewline();
            require(engine.undo(), "Could not create a redo branch");
            engine.restoreSelection(new Position(2, 1), new Position(0, 0));
            if (args[0].equals("loaded")) {
                engine.selectAll();
                require(engine.copySelection(), "Could not copy admitted file");
                engine.moveDocumentEnd(false);
            } else {
                clipboard.setText("x\n".repeat(524_288));
            }
            engine.state().setViewport(new ViewportState(0, 0, 3, 3));
            String pasted = clipboard.text();
            reject(engine, args[0].equals("terminal") ? () -> engine.pasteText(pasted) : engine::pasteClipboard);
            require(clipboard.text().equals(pasted), "Rejected paste changed the workspace clipboard");
        }

        private static EditorEngine engine(List<String> lines, EditorClipboard clipboard) {
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.replaceLines(lines, new DocumentFileMetadata(StandardCharsets.UTF_16LE, BomKind.UTF_16_LE,
                "\r\n", java.util.stream.IntStream.range(1, lines.size())
                    .mapToObj(index -> index % 2 == 0 ? "\n" : "\r").toList()));
            EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model), clipboard);
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            engine.setAutoIndentEnabled(false);
            engine.setAutoListEnabled(false);
            return engine;
        }

        private static void reject(EditorEngine engine, Runnable attempted) {
            List<String> lines = engine.model().snapshotLines();
            DocumentFileMetadata metadata = engine.model().fileMetadata();
            EditorViewMemento view = engine.state().viewMemento();
            long revision = engine.model().revision();
            long epoch = engine.model().contentEpoch();
            long navigation = engine.navigationRevision();
            boolean dirty = engine.state().isDirty();
            try {
                attempted.run();
                throw new AssertionError("Oversized paste was admitted");
            } catch (DocumentEditLimitException expected) {
                require(expected.getMessage().contains("available memory"), "Missing admission reason");
            }
            require(engine.model().snapshotLines().equals(lines), "Refusal changed text");
            require(engine.model().fileMetadata().equals(metadata), "Refusal changed file metadata");
            require(engine.state().viewMemento().equals(view), "Refusal changed the cursor, selection or viewport");
            require(engine.model().revision() == revision && engine.model().contentEpoch() == epoch,
                "Refusal changed revision or content epoch");
            require(engine.navigationRevision() == navigation && engine.state().isDirty() == dirty,
                "Refusal changed navigation or dirty state");
            RecoverySnapshot recovery = engine.captureRecoverySnapshot();
            require(recovery.lines().equals(lines) && recovery.fileMetadata().equals(metadata),
                "Refusal left an invalid recovery document");
            require(engine.redo(), "Refusal discarded existing redo history");
            require(engine.undo() && engine.model().snapshotLines().equals(lines), "Redo no longer round-trips");
            require(engine.undo() && !engine.state().isDirty(), "Refusal damaged undo or the clean savepoint");
        }

        private static void admitted() {
            String prefix = "a".repeat(600_000);
            List<String> original = List.of(prefix, "middle", "end");
            EditorEngine engine = engine(original, new EditorClipboard());
            DocumentFileMetadata metadata = engine.model().fileMetadata();
            Position start = new Position(0, prefix.length());
            Position end = new Position(1, 3);
            engine.restoreSelection(end, start);
            require(engine.pasteText("😀\r\nĀ"), "Admitted paste was not inserted");
            List<String> changed = List.of(prefix + "😀", "Ādle", "end");
            require(engine.model().snapshotLines().equals(changed), "Admitted paste lost Unicode text");
            require(engine.captureRecoverySnapshot().lines().equals(changed), "Admitted paste is not recoverable");
            require(engine.undo() && engine.model().snapshotLines().equals(original), "Admitted paste undo failed");
            require(engine.model().fileMetadata().equals(metadata), "Undo lost original separators or encoding");
            require(engine.state().getCursor().equals(end) && engine.state().getSelectionAnchor().equals(start),
                "Undo lost the original selection");
            require(!engine.state().isDirty(), "Undo lost the clean savepoint");
            require(engine.redo() && engine.model().snapshotLines().equals(changed), "Admitted paste redo failed");
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
