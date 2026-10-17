package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.junit.jupiter.api.Test;

class EditorEngineSingleReplaceMemoryTest {
    @Test void rejectsAnOversizedReplacementOfTheSelectedMatchOn64MiB() throws Exception { probe("selected"); }
    @Test void rejectsBeforeSelectingTheNextMatchOn64MiB() throws Exception { probe("next"); }
    @Test void rejectsBeforeAllocatingNormalizedReplacementTextOn64MiB() throws Exception { probe("normalize"); }

    private static void probe(String mode) throws Exception {
        String classpath = String.join(File.pathSeparator, location(EditorEngineSingleReplaceMemoryTest.class),
            location(EditorEngine.class), location(GraphemeText.class), location(LanguageRegistry.class),
            location(DiffAlgorithm.class));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", classpath, MemoryProbe.class.getName(), mode)
            .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Replacement admission probe exceeded its deadline");
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
            Path file = Files.createTempFile("tuink-single-replace-", ".txt");
            try {
                Files.writeString(file, "a".repeat(1_048_576) + "needle" + "a".repeat(1_048_576));
                TextModel model = new TextModel(new SimpleLineBuffer());
                EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
                engine.openExisting(file);
                engine.setGutterDiffMode(GutterDiffMode.DISABLED);
                engine.setAutoPairsEnabled(false);
                engine.insertInput("X");
                require(engine.undo(), "Could not create a redo branch");
                if (args[0].equals("selected")) require(engine.findNext("needle"), "Missing selected match");
                engine.state().setViewport(new ViewportState(0, 0, 3, 3));
                String replacement = args[0].equals("normalize")
                    ? "b\r\n".repeat(5_592_405) : "b".repeat(16 * 1024 * 1024);
                List<String> lines = model.snapshotLines();
                DocumentFileMetadata metadata = model.fileMetadata();
                EditorViewMemento view = engine.state().viewMemento();
                long revision = model.revision();
                long epoch = model.contentEpoch();
                long navigation = engine.navigationRevision();
                try {
                    engine.replaceCurrentOrNext("needle", replacement);
                    throw new AssertionError("Oversized replacement was admitted");
                } catch (DocumentEditLimitException expected) {
                    require(expected.getMessage().contains("available memory"), "Missing admission reason");
                }
                require(model.snapshotLines().equals(lines), "Refusal changed text");
                require(model.fileMetadata().equals(metadata), "Refusal changed file metadata");
                require(engine.state().viewMemento().equals(view), "Refusal changed cursor, selection or viewport");
                require(model.revision() == revision && model.contentEpoch() == epoch, "Refusal changed revision");
                require(engine.navigationRevision() == navigation && !engine.state().isDirty(),
                    "Refusal changed navigation or the clean savepoint");
                RecoverySnapshot recovery = engine.captureRecoverySnapshot();
                require(recovery.lines().equals(lines) && recovery.fileMetadata().equals(metadata),
                    "Refusal left an invalid recovery document");
                require(engine.redo(), "Refusal discarded redo history");
                require(engine.undo() && model.snapshotLines().equals(lines) && !engine.state().isDirty(),
                    "Existing history no longer round-trips");
                require(Files.size(file) == 2_097_158, "Refusal changed the original file");
            } finally {
                Files.delete(file);
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
