package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.unicode.GraphemeText;
import dev.tuink.verification.MemoryDocumentStorage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EditorEngineEditLimitMemoryTest {
    @Test void asciiExpansionIsRejectedAtomicallyOn64MiB() throws Exception { runProbe("ascii64", 64); }
    @Test void asciiExpansionIsRejectedAtomicallyOn192MiB() throws Exception { runProbe("ascii192", 192); }
    @Test void newlineFanOutIsRejectedBeforeAllocatingPerLineState() throws Exception { runProbe("lines", 64); }
    @Test void aLaterOversizedBatchEditCannotCommitAnEarlierSmallEdit() throws Exception { runProbe("batch", 64); }
    @Test void anAdmittedLateUtf16WideningRemainsUndoable() throws Exception { runProbe("widen", 64); }

    private static void runProbe(String mode, int heapMiB) throws Exception {
        String classpath = String.join(File.pathSeparator, location(EditorEngineEditLimitMemoryTest.class),
            location(EditorEngine.class), location(dev.tuink.core.SearchMatcher.class), location(TextModel.class), location(GraphemeText.class),
            location(LanguageRegistry.class), LinuxXattrTestRuntime.classpath());
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx" + heapMiB + "m", "-cp", classpath, MemoryProbe.class.getName(), mode)
            .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Edit admission probe exceeded its deadline");
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
        public static void main(String[] args) {
            switch (args[0]) {
                case "ascii64" -> {
                    EditorEngine engine = engine(List.of("a".repeat(500_000)));
                    reject(engine, () -> engine.replaceAll("a", "b".repeat(64)));
                }
                case "ascii192" -> {
                    EditorEngine engine = engine(List.of("a".repeat(1_000_000)));
                    reject(engine, () -> engine.replaceAll("a", "b".repeat(128)));
                }
                case "lines" -> {
                    EditorEngine engine = engine(List.of("a".repeat(100_000)));
                    reject(engine, () -> engine.replaceAll("a", "\n".repeat(8)));
                }
                case "batch" -> {
                    EditorEngine engine = engine(List.of("left", "right"));
                    reject(engine, () -> engine.applyTextReplacements(List.of(
                        new TextReplacement(new Position(0, 0), new Position(0, 1), "small"),
                        new TextReplacement(new Position(1, 0), new Position(1, 5), "\n".repeat(500_000))
                    )));
                }
                case "widen" -> {
                    String original = "a".repeat(2_000_000) + "x";
                    EditorEngine engine = engine(List.of(original));
                    require(engine.replaceAll("x", "Ā") == 1, "Widening replacement was not applied");
                    require(engine.model().getLine(0).endsWith("Ā"), "Widening lost the replacement");
                    require(engine.undo() && engine.model().getLine(0).equals(original), "Widening undo failed");
                    require(!engine.state().isDirty(), "Widening undo lost the savepoint");
                    require(engine.redo() && engine.model().getLine(0).endsWith("Ā"), "Widening redo failed");
                }
                default -> throw new AssertionError("Unknown probe");
            }
        }

        private static EditorEngine engine(List<String> lines) {
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.replaceLines(lines);
            EditorState state = new EditorState();
            state.setCurrentResource(new MemoryDocumentStorage().resource(Path.of("limits.java").toString()));
            EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            return engine;
        }

        private static void reject(EditorEngine engine, Runnable attempted) {
            engine.insertInput("X");
            engine.insertNewline();
            require(engine.undo(), "Could not create a redo branch");
            engine.restoreSelection(new Position(0, 2), new Position(0, 0));
            engine.state().setViewport(new ViewportState(0, 0, 3, 3));
            List<String> lines = engine.model().snapshotLines();
            DocumentFileMetadata metadata = engine.model().fileMetadata();
            EditorViewMemento view = engine.state().viewMemento();
            long revision = engine.model().revision();
            long epoch = engine.model().contentEpoch();
            long navigation = engine.navigationRevision();
            boolean dirty = engine.state().isDirty();
            try {
                attempted.run();
                throw new AssertionError("Oversized edit was admitted");
            } catch (DocumentEditLimitException expected) {
                require(expected.getMessage().startsWith("Replacement result is too large"), "Missing user-facing reason");
            }
            require(engine.model().snapshotLines().equals(lines), "Refusal changed text");
            require(engine.model().fileMetadata().equals(metadata), "Refusal changed metadata");
            require(engine.state().viewMemento().equals(view), "Refusal changed view/selection");
            require(engine.model().revision() == revision && engine.model().contentEpoch() == epoch,
                "Refusal changed revision/epoch");
            require(engine.navigationRevision() == navigation && engine.state().isDirty() == dirty,
                "Refusal changed navigation/savepoint");
            require(engine.redo(), "Refusal discarded redo history");
            require(engine.undo() && engine.model().snapshotLines().equals(lines), "Redo branch no longer round-trips");
            require(engine.undo() && !engine.state().isDirty(), "Refusal damaged prior undo/savepoint");
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
