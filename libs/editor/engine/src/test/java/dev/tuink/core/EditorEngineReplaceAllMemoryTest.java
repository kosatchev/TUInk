package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EditorEngineReplaceAllMemoryTest {
    @Test
    void twoMillionMatchesAndTheirHistoryFitInABoundedHeap() throws Exception {
        runProbe(MemoryProbe.class);
    }

    @Test
    void unchangedReplacementDoesNotBuildAnotherCopyOfAManyLineDocument() throws Exception {
        runProbe(ManyLinesProbe.class);
    }

    private static void runProbe(Class<?> probe) throws Exception {
        String classpath = String.join(File.pathSeparator,
            location(EditorEngineReplaceAllMemoryTest.class), location(EditorEngine.class), location(dev.tuink.core.SearchMatcher.class), location(TextModel.class),
            location(GraphemeText.class), location(LanguageRegistry.class));
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", classpath, probe.getName()
        ).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Replacement probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class ManyLinesProbe {
        public static void main(String[] args) {
            int lineCount = 1_250_000;
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.replaceLines(Collections.nCopies(lineCount, "a"));
            EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            long revision = model.revision();

            MemoryProbe.require(engine.replaceAll("x", "y") == 0, "Unmatched text must not produce replacements");
            MemoryProbe.require(engine.replaceAll("a", "a") == lineCount, "Identical matches must still be counted");
            MemoryProbe.require(model.revision() == revision && !engine.state().isDirty(),
                "An unchanged many-line document must preserve its savepoint");
            MemoryProbe.require(!engine.undo(), "Unchanged replacements must not create history");
        }
    }

    public static final class MemoryProbe {
        public static void main(String[] args) {
            int length = 2_000_000;
            String original = "a".repeat(length);
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.replaceLines(List.of(original));
            EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            long revision = model.revision();

            require(engine.replaceAll("a", "a") == length, "Identical matches must still be counted");
            require(model.revision() == revision && !engine.state().isDirty(), "Identical text must remain unchanged");
            require(!engine.undo(), "An identical replacement must not create history");
            require(engine.replaceAll("a", "b") == length, "Every occurrence must be replaced");
            require(model.getLine(0).equals("b".repeat(length)), "Replacement output is incomplete");
            require(engine.undo(), "Dense replacement must be undoable");
            require(model.getLine(0).equals(original) && !engine.state().isDirty(), "Undo must restore the savepoint");
            require(!engine.undo(), "Dense replacement must occupy a single history step");
            require(engine.redo(), "Dense replacement must be redoable");
            require(model.getLine(0).equals("b".repeat(length)), "Redo must restore the full output");
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }
    }
}
