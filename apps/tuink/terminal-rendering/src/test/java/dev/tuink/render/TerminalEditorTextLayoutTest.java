package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalEditorTextLayoutTest {
    private static final int TAB_WIDTH = 4;

    @Test
    void exposesTerminalCellMetricsThroughTheEditorLayoutPort() {
        TextModel model = model("A中B", "ab\tcd");
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);

        assertSame(model, layout.model());
        assertEquals(4, layout.visualWidth(0, 4));
        assertEquals(3, layout.visualColumnAt(0, 2, 4));
        assertEquals(
            1,
            layout.textOffsetAtVisualColumn(0, 2, 4, EditorTextLayout.Bias.FLOOR)
        );
        assertEquals(
            1,
            layout.textOffsetAtVisualColumn(0, 2, 4, EditorTextLayout.Bias.NEAREST)
        );
        assertEquals(
            2,
            layout.textOffsetAtVisualColumn(0, 2, 4, EditorTextLayout.Bias.CEIL)
        );
        assertEquals(List.of(0, 3), layout.wrapStarts(1, 4, false, 4));
        assertEquals(3, layout.totalWrappedRows(4, false, 4));
        assertEquals(1, layout.lineIndexFromWrappedOffset(2, 4, false, 4));
        assertEquals(6, layout.maxVisualLineWidth(4));
        assertEquals(
            new EditorTextLayout.VisualRange(1, 2, 1),
            layout.visualRange(0, 2, 1, 4)
        );
        assertEquals(
            new EditorTextLayout.VisualRange(2, 5, 2),
            layout.visualRange(1, 3, 3, 4)
        );
    }

    @Test
    void observesIncrementalEditsAndWholeContentReplacement() {
        TextModel model = model("a\t中");
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);

        assertEquals(6, layout.visualWidth(0, 4));

        model.insertChar(new Position(0, model.getLine(0).length()), 'b');
        assertEquals(7, layout.visualWidth(0, 4));
        assertEquals(7, layout.maxVisualLineWidth(4));

        model.replaceLines(List.of("x", "wide中"));
        assertEquals(1, layout.visualWidth(0, 4));
        assertEquals(6, layout.maxVisualLineWidth(4));
    }

    @Test
    void insertCharInvalidatesWarmedMetricsWrapsAndPrefix() {
        TextModel model = model("a\t中", "zzzz");
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
        assertLayout(
            layout,
            2,
            line("a\t中", 6, 0, 1, 2),
            line("zzzz", 4, 0, 2)
        );

        assertEquals(new Position(0, 4), model.insertChar(new Position(0, 3), 'x'));

        assertLayout(
            layout,
            2,
            line("a\t中x", 7, 0, 1, 2, 3),
            line("zzzz", 4, 0, 2)
        );
    }

    @Test
    void insertNewlineInvalidatesWarmedCachesAndShiftsFollowingLineMetrics() {
        TextModel model = model("ab中c", "q", "12345");
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
        assertLayout(
            layout,
            3,
            line("ab中c", 5, 0, 2),
            line("q", 1, 0),
            line("12345", 5, 0, 3)
        );

        assertEquals(new Position(1, 0), model.insertNewline(new Position(0, 2)));

        assertLayout(
            layout,
            3,
            line("ab", 2, 0),
            line("中c", 3, 0),
            line("q", 1, 0),
            line("12345", 5, 0, 3)
        );
    }

    @Test
    void deleteBackwardInvalidatesWarmedCachesAndShiftsFollowingLineMetrics() {
        TextModel model = model("a", "中中", "bb", "12345");
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
        assertLayout(
            layout,
            3,
            line("a", 1, 0),
            line("中中", 4, 0, 1),
            line("bb", 2, 0),
            line("12345", 5, 0, 3)
        );

        assertEquals(new Position(0, 1), model.deleteBackward(new Position(1, 0)));

        assertLayout(
            layout,
            3,
            line("a中中", 5, 0, 2),
            line("bb", 2, 0),
            line("12345", 5, 0, 3)
        );
    }

    @Test
    void deleteForwardInvalidatesWarmedCachesAndShiftsFollowingLineMetrics() {
        TextModel model = model("ab", "中中", "q", "12345");
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
        assertLayout(
            layout,
            3,
            line("ab", 2, 0),
            line("中中", 4, 0, 1),
            line("q", 1, 0),
            line("12345", 5, 0, 3)
        );

        assertEquals(new Position(0, 2), model.deleteForward(new Position(0, 2)));

        assertLayout(
            layout,
            3,
            line("ab中中", 6, 0, 2, 3),
            line("q", 1, 0),
            line("12345", 5, 0, 3)
        );
    }

    @Test
    void insertTextInvalidatesWarmedCachesAndShiftsFollowingLineMetrics() {
        TextModel model = model("head", "a\t中", "tail中", "z");
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
        assertLayout(
            layout,
            3,
            line("head", 4, 0, 3),
            line("a\t中", 6, 0, 1, 2),
            line("tail中", 6, 0, 3),
            line("z", 1, 0)
        );

        assertEquals(
            new Position(3, 2),
            model.insertText(new Position(1, 1), "X\n中\nYZ")
        );

        assertLayout(
            layout,
            3,
            line("head", 4, 0, 3),
            line("aX", 2, 0),
            line("中", 2, 0),
            line("YZ\t中", 6, 0, 2, 3),
            line("tail中", 6, 0, 3),
            line("z", 1, 0)
        );
    }

    @Test
    void deleteRangeInvalidatesWarmedCachesAndShiftsFollowingLineMetrics() {
        TextModel model = model("pre中", "a\t中", "BBBBB", "tail中", "z");
        TerminalEditorTextLayout layout = new TerminalEditorTextLayout(model);
        assertLayout(
            layout,
            3,
            line("pre中", 5, 0, 3),
            line("a\t中", 6, 0, 1, 2),
            line("BBBBB", 5, 0, 3),
            line("tail中", 6, 0, 3),
            line("z", 1, 0)
        );

        assertEquals(
            new Position(0, 1),
            model.deleteRange(new Position(0, 1), new Position(2, 3))
        );

        assertLayout(
            layout,
            3,
            line("pBB", 3, 0),
            line("tail中", 6, 0, 3),
            line("z", 1, 0)
        );
    }

    private static void assertLayout(
        TerminalEditorTextLayout layout,
        int wrapWidth,
        ExpectedLine... expectedLines
    ) {
        TextModel model = layout.model();
        assertEquals(expectedLines.length, model.lineCount());

        int totalRows = 0;
        int maxVisualWidth = 0;
        for (int line = 0; line < expectedLines.length; line++) {
            ExpectedLine expected = expectedLines[line];
            assertEquals(expected.text(), model.getLine(line), "text at line " + line);
            assertEquals(
                expected.visualWidth(),
                layout.visualWidth(line, TAB_WIDTH),
                "visual width at line " + line
            );
            assertEquals(
                expected.wrapStarts(),
                layout.wrapStarts(line, wrapWidth, false, TAB_WIDTH),
                "wrap starts at line " + line
            );
            assertEquals(
                totalRows,
                layout.wrappedRowsBefore(line, wrapWidth, false, TAB_WIDTH),
                "wrapped prefix at line " + line
            );
            totalRows += expected.wrapStarts().size();
            maxVisualWidth = Math.max(maxVisualWidth, expected.visualWidth());
        }

        assertEquals(
            totalRows,
            layout.totalWrappedRows(wrapWidth, false, TAB_WIDTH),
            "total wrapped rows"
        );
        assertEquals(maxVisualWidth, layout.maxVisualLineWidth(TAB_WIDTH));

        int wrappedOffset = 0;
        for (int line = 0; line < expectedLines.length; line++) {
            int rows = expectedLines[line].wrapStarts().size();
            for (int row = 0; row < rows; row++) {
                assertEquals(
                    line,
                    layout.lineIndexFromWrappedOffset(
                        wrappedOffset++,
                        wrapWidth,
                        false,
                        TAB_WIDTH
                    ),
                    "line at wrapped offset " + (wrappedOffset - 1)
                );
            }
        }
    }

    private static ExpectedLine line(String text, int visualWidth, Integer... wrapStarts) {
        return new ExpectedLine(text, visualWidth, List.of(wrapStarts));
    }

    private record ExpectedLine(String text, int visualWidth, List<Integer> wrapStarts) {
    }

    private static TextModel model(String... lines) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, lines.length == 0 ? "" : lines[0]);
        for (int index = 1; index < lines.length; index++) {
            buffer.insertLine(index, lines[index]);
        }
        return new TextModel(buffer);
    }
}
