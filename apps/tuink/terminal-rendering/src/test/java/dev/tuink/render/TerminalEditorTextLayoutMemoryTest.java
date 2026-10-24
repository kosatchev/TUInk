package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.text.TerminalText;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.EditorState;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalEditorTextLayoutMemoryTest {
    @Test
    void acceptedLongLineCanResizeAndNavigateAtOneColumnOnASmallHeap(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("narrow.txt");
        byte[] block = new byte[8192];
        Arrays.fill(block, (byte) 'a');
        try (var output = Files.newOutputStream(file)) {
            for (int index = 0; index < 384; index++) output.write(block);
        }
        String classpath = String.join(File.pathSeparator, location(getClass()), location(RenderPlanBuilder.class),
            location(TextModel.class), location(TerminalText.class), location(DocumentView.class));
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", classpath, NarrowWrapProbe.class.getName(), file.toString())
            .redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "narrow wrap probe exceeded its deadline");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void acceptedLargeLinesNeverReachLegacyFullLineStylingInEitherLayout(@TempDir Path directory) throws Exception {
        for (int[] example : new int[][] {{3, 64}, {10, 192}}) {
            Path file = directory.resolve("legacy-" + example[0] + ".csv");
            byte[] block = "1,".repeat(4096).getBytes(StandardCharsets.UTF_8);
            try (var output = Files.newOutputStream(file)) {
                for (int index = 0; index < example[0] * 128; index++) {
                    output.write(block);
                }
            }
            String classpath = String.join(File.pathSeparator, location(getClass()), location(RenderPlanBuilder.class),
                location(TextModel.class), location(TerminalText.class), location(DocumentView.class));
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + example[1] + "m", "-cp", classpath, BoundedRenderProbe.class.getName(), file.toString())
                .redirectErrorStream(true).start();
            try {
                assertTrue(child.waitFor(20, TimeUnit.SECONDS), "bounded render probe exceeded its deadline");
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, child.exitValue(), example[0] + " MiB / " + example[1] + " MiB heap: " + output);
            } finally {
                child.destroyForcibly();
            }
        }
    }

    @Test
    void acceptedLongUnicodeLinesFitTheirFirstLayoutInASmallHeap(@TempDir Path directory) throws Exception {
        for (int[] example : new int[][] {{3, 64}, {9, 192}}) {
            Path file = directory.resolve("line-" + example[0] + ".txt");
            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 'a');
            try (var output = Files.newOutputStream(file)) {
                for (int index = 0; index < example[0] * 128; index++) {
                    output.write(chunk);
                }
                output.write("Ā".getBytes(StandardCharsets.UTF_8));
            }
            String classpath = String.join(File.pathSeparator, location(TerminalEditorTextLayoutMemoryTest.class),
                location(TerminalEditorTextLayout.class), location(TextModel.class), location(TerminalText.class),
                location(DocumentView.class));
            Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + example[1] + "m", "-cp", classpath, FirstLayoutProbe.class.getName(), file.toString()
            ).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "First layout exceeded its deadline");
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), example[0] + " MiB / " + example[1] + " MiB heap: " + output);
            } finally {
                process.destroyForcibly();
            }
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class NarrowWrapProbe {
        public static void main(String[] args) throws Exception {
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.load(Path.of(args[0]));
            int length = model.getLine(0).length();
            TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
            RenderPlanBuilder builder = RenderPlanBuilder.standalone(line -> java.util.List.of(), layout);
            builder.setSyntaxHighlightingEnabled(false);
            builder.setBracketMatchingEnabled(false);
            builder.setRainbowBracketsEnabled(false);
            for (boolean words : new boolean[]{false, true}) {
                for (int columns : new int[]{80, 5, 80, 7, 4, 1, 80}) {
                    EditorState state = new EditorState();
                    render(builder, model, state, columns, words);
                    state.setCursor(new Position(0, length));
                    // A filled one-column line still reserves an insertion row at EOF.
                    var coordinates = new EditorViewCoordinates(layout, 1, true, words, 4);
                    var end = coordinates.topForAnchor(state.getCursor());
                    require(end.segment() == length, "EOF row disappeared");
                    require(coordinates.anchor(end).equals(state.getCursor()), "EOF anchor changed");
                    require(coordinates.positionAt(length / 2, 0).equals(new Position(0, length / 2)),
                        "A distant wrapped hit changed its offset");
                    state.setTopWrapSegment(length / 2);
                    render(builder, model, state, columns, words);
                }
            }
            require(model.getLine(0).length() == length, "Resizing changed the document");
        }

        private static void render(RenderPlanBuilder builder, TextModel model, EditorState state,
                                   int columns, boolean words) {
            builder.build(model, state, 24, columns, "top", "bottom", null, SearchOptions.DEFAULT,
                null, null, null, null, true, words);
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }

    public static final class FirstLayoutProbe {
        public static void main(String[] args) throws Exception {
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.load(Path.of(args[0]));
            int length = model.getLine(0).length();
            TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
            require(layout.maxVisualLineWidth(4) == length, "First frame has the wrong line width");
            require(layout.visualColumnAt(0, length, 4) == length, "End-of-line column changed");
            require(layout.totalWrappedRows(80, false, 4) == length / 80 + 1, "Wrapped row count changed");
            require(layout.wrapStarts(0, 80, false, 4).getLast() == length / 80 * 80,
                "Wrapped line starts changed");
            require(model.getLine(0).endsWith("Ā"), "Layout changed document text");
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }
    }

    public static final class BoundedRenderProbe {
        public static void main(String[] args) throws Exception {
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.load(Path.of(args[0]));
            RenderPlanBuilder builder = RenderPlanBuilder.standalone(line -> {
                throw new AssertionError("Oversized input reached a legacy full-line highlighter");
            }, new TerminalEditorTextLayout(model));
            builder.setBracketMatchingEnabled(false);
            builder.setRainbowBracketsEnabled(false);
            EditorState state = new EditorState();
            render(builder, model, state, false);
            state.setLeftVisualColumn(1_000_000);
            render(builder, model, state, false);
            state.setTopWrapSegment(1000);
            render(builder, model, state, true);
        }

        private static void render(RenderPlanBuilder builder, TextModel model, EditorState state, boolean wrap) {
            RenderPlan plan = builder.build(model, state, 24, 80, "top", "bottom", null, SearchOptions.DEFAULT,
                null, null, null, null, wrap, false);
            if (plan.lines().stream().anyMatch(line -> !line.spans().isEmpty())) {
                throw new AssertionError("The compatibility fallback must leave unsupported input unstyled");
            }
        }
    }
}
