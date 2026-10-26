package dev.tuink.render;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.text.TerminalText;
import dev.tuink.core.EditorState;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderPlanBuilderBracketMemoryTest {
    @Test
    void acceptedDenseBracketFilesRenderInASmallHeapWithEveryDecorationCombination(@TempDir Path directory)
        throws Exception {
        for (String pattern : List.of("(", ")", "()", "([)]")) {
            Path file = directory.resolve("brackets-" + pattern.hashCode() + ".txt");
            byte[] block = new byte[8192];
            for (int index = 0; index < block.length; index++) block[index] = (byte) pattern.charAt(index % pattern.length());
            try (var output = Files.newOutputStream(file)) {
                for (int index = 0; index < 128; index++) output.write(block);
            }
            String classpath = String.join(File.pathSeparator, location(getClass()), location(RenderPlanBuilder.class),
                location(TextModel.class), location(TerminalText.class), location(DocumentView.class), location(dev.tuink.storage.jvm.JvmDocumentStorage.class));
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-cp", classpath, DenseBracketProbe.class.getName(), file.toString())
                .redirectErrorStream(true).start();
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Dense bracket rendering exceeded its deadline");
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, child.exitValue(), "pattern=" + pattern + ": " + output);
            } finally {
                child.destroyForcibly();
            }
            assertEquals(1024 * 1024, Files.size(file));
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class DenseBracketProbe {
        public static void main(String[] args) throws Exception {
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.load(JvmDocumentStorage.resource(Path.of(args[0])));
            int length = model.getLine(0).length();
            TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
            for (boolean matching : new boolean[] {true, false}) {
                for (boolean rainbow : new boolean[] {true, false}) {
                    RenderPlanBuilder builder = RenderPlanBuilder.standalone(line -> List.of(), layout);
                    builder.setSyntaxHighlightingEnabled(false);
                    builder.setBracketMatchingEnabled(matching);
                    builder.setRainbowBracketsEnabled(rainbow);
                    for (boolean wrap : new boolean[] {false, true}) {
                        for (int columns : new int[] {80, 5}) {
                            EditorState state = new EditorState();
                            if (wrap) state.setTopWrapSegment(1000);
                            RenderPlan plan = builder.build(model, state, 24, columns, "top", "bottom", null,
                                SearchOptions.DEFAULT, null, null, null, null, wrap, false);
                            int spans = plan.lines().stream().mapToInt(line -> line.spans().size()).sum();
                            require(spans <= 2 * plan.contentRows() * columns + 2,
                                "Decorations must be limited to the visible text");
                            require(builder.bracketFactCount() <= RenderPlanBuilder.MAX_BRACKET_FACTS,
                                "Result storage exceeded its independent budget");
                            if (!matching && !rainbow) {
                                require(builder.bracketFactsStorageBytes() == 0 && builder.lexicalCacheSize() == 0,
                                    "Disabled bracket features retained their caches");
                            }
                        }
                    }
                }
            }
            require(model.getLine(0).length() == length, "Rendering changed the document");
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
