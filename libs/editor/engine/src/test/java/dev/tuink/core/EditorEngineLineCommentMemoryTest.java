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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EditorEngineLineCommentMemoryTest {
    @Test void rejectsFourHundredThousandLinesBeforeMutationOn128MiB() throws Exception {
        probe("reject");
    }

    @Test void anAdmittedBatchRemainsRecoverableAndUndoableOn128MiB() throws Exception {
        probe("admit");
    }

    private static void probe(String mode) throws Exception {
        String classpath = String.join(File.pathSeparator, location(EditorEngineLineCommentMemoryTest.class),
            location(EditorEngine.class), location(dev.tuink.core.SearchMatcher.class), location(GraphemeText.class), location(LanguageRegistry.class), LinuxXattrTestRuntime.classpath());
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx128m", "-cp", classpath, MemoryProbe.class.getName(), mode)
            .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Comment admission probe exceeded its deadline");
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
            int count = args[0].equals("reject") ? 400_000 : 5_000;
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.replaceLines(Collections.nCopies(count, "x"));
            EditorState state = new EditorState();
            state.setCurrentResource(new MemoryDocumentStorage().resource(Path.of("Demo.java").toString()));
            EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            engine.insertInput("X");
            engine.insertNewline();
            require(engine.undo(), "Could not create a redo branch");
            engine.selectAll();
            state.setViewport(new ViewportState(1, 0, 2, 2));
            List<String> original = model.snapshotLines();
            DocumentFileMetadata metadata = model.fileMetadata();
            EditorViewMemento view = state.viewMemento();
            long revision = model.revision();
            long epoch = model.contentEpoch();
            long navigation = engine.navigationRevision();
            boolean dirty = state.isDirty();
            if (args[0].equals("reject")) {
                try {
                    engine.toggleLineComment();
                    throw new AssertionError("The oversized comment batch was admitted");
                } catch (DocumentEditLimitException expected) {
                    require(expected.getMessage().contains("available memory"), "Missing admission reason");
                }
                require(model.snapshotLines().equals(original), "Refusal changed document text");
                require(model.fileMetadata().equals(metadata), "Refusal changed metadata");
                require(state.viewMemento().equals(view), "Refusal changed the cursor, selection or viewport");
                require(model.revision() == revision && model.contentEpoch() == epoch,
                    "Refusal changed revision or content epoch");
                require(engine.navigationRevision() == navigation && state.isDirty() == dirty,
                    "Refusal changed navigation or the dirty state");
                require(engine.captureRecoverySnapshot().lines().equals(original), "Recovery changed after refusal");
                require(engine.redo(), "Refusal discarded the existing redo branch");
                require(engine.undo() && model.snapshotLines().equals(original), "Existing redo no longer round-trips");
                require(engine.undo() && !state.isDirty(), "Refusal damaged undo or the clean savepoint");
            } else {
                require(engine.toggleLineComment().lineCount() == count, "The admitted batch lost lines");
                require(model.getLine(0).equals("// Xx") && model.getLine(count - 1).equals("// x"),
                    "The admitted comment batch changed the wrong text");
                require(state.isDirty(), "The admitted batch is not dirty");
                require(engine.captureRecoverySnapshot().lines().equals(model.snapshotLines()), "Recovery lost the batch");
                require(engine.undo() && model.snapshotLines().equals(original), "Batch undo did not restore text");
                require(model.fileMetadata().equals(metadata) && state.viewMemento().equals(view),
                    "Batch undo lost metadata or the selection");
                require(engine.redo() && model.getLine(count - 1).equals("// x"), "Batch redo failed");
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
