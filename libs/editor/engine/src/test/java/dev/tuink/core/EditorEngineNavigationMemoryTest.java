package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEngineNavigationMemoryTest {
    @Test
    void acceptedLongUnicodeLinesRemainNavigableAfterEditsAndHistoryInASmallHeap(@TempDir Path directory)
        throws Exception {
        for (int[] example : new int[][] {{3584, 64}, {7168, 128}, {11264, 192}}) {
            Path file = directory.resolve("line-" + example[0] + ".txt");
            byte[] block = new byte[8192];
            Arrays.fill(block, (byte) 'a');
            try (var output = Files.newOutputStream(file)) {
                for (int index = 0; index < example[0] / 8; index++) {
                    output.write(block);
                }
                output.write("Ā".getBytes(StandardCharsets.UTF_8));
            }
            String classpath = String.join(File.pathSeparator, location(getClass()), location(EditorEngine.class), location(dev.tuink.core.SearchMatcher.class),
                location(GraphemeText.class), location(LanguageRegistry.class), LinuxXattrTestRuntime.classpath());
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + example[1] + "m", "-cp", classpath, NavigationProbe.class.getName(), file.toString())
                .redirectErrorStream(true).start();
            try {
                assertTrue(child.waitFor(20, TimeUnit.SECONDS), "navigation probe timed out");
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, child.exitValue(), example[0] + " KiB / " + example[1] + " MiB heap: " + output);
            } finally {
                child.destroyForcibly();
            }
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class NavigationProbe {
        public static void main(String[] args) throws Exception {
            TextModel model = new TextModel(new SimpleLineBuffer());
            EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
            engine.open(JvmDocumentStorage.resource(Path.of(args[0])));
            int originalLength = model.getLine(0).length();
            for (int cycle = 0; cycle < 3; cycle++) {
                engine.moveHome();
                engine.moveRight();
                engine.insertInput("b");
                engine.moveRight();
                require(engine.state().getCursor().equals(new Position(0, 3)), "navigation after typing changed");
                require(model.getLine(0).length() == originalLength + 1 && model.getLine(0).startsWith("aba"),
                    "typing changed unrelated text");
                require(engine.undo() && model.getLine(0).length() == originalLength && !engine.state().isDirty(),
                    "undo did not restore the saved document");
                require(engine.redo() && model.getLine(0).length() == originalLength + 1,
                    "redo did not restore the edit");
                engine.moveRight();
                require(engine.undo() && model.getLine(0).length() == originalLength,
                    "navigation must preserve undo history");
                require(model.getLine(0).endsWith("Ā"), "Unicode suffix changed");
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }
    }
}
