package dev.tuink.tui.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellStyle;
import dev.tuink.render.Color;
import dev.tuink.render.Style;
import dev.tuink.tui.style.UiStyles;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Window chrome has the same text geometry and placement for any content adapter. */
class WindowTextPainterTest {
    private static final Cell SENTINEL = new Cell("#", CellStyle.DEFAULT);
    private static final UiStyles.Palette PALETTE = UiStyles.forTheme(UiStyles.Theme.CYAN);

    @Test
    void chromeUsesWidgetTabStopsAndPreservesWideAndCombiningGraphemes() {
        var buffer = filled(5, 8);
        WindowTextPainter.paint(new Canvas(buffer), 5, 8,
            new WindowTextPainter.Text("A\t中e\u0301", "😀Z", null, null, null, null), PALETTE);

        assertEquals("A", buffer.get(0, 0).grapheme());
        for (int column : new int[] {1, 2, 3, 7}) assertEquals(" ", buffer.get(0, column).grapheme());
        assertEquals("中", buffer.get(0, 4).grapheme());
        assertEquals(2, buffer.get(0, 4).width());
        assertTrue(buffer.get(0, 5).continuation());
        assertEquals("e\u0301", buffer.get(0, 6).grapheme());
        assertEquals(1, buffer.get(0, 6).width());
        assertEquals("😀", buffer.get(4, 0).grapheme());
        assertTrue(buffer.get(4, 1).continuation());
        assertEquals("Z", buffer.get(4, 2).grapheme());
        assertRowStyle(buffer, 0, PALETTE.renderTopbar());
        assertRowStyle(buffer, 4, PALETTE.renderStatus());
        for (int row = 1; row < 4; row++) assertEquals("########", row(buffer, row));
        assertWideCellsComplete(buffer);
    }

    @ParameterizedTest
    @CsvSource(value = {"1,A", "2,A ", "3,A中", "4,A中B"}, ignoreLeadingAndTrailingWhitespace = false)
    void clippingNeverDrawsHalfOfAWideGrapheme(int columns, String expected) {
        var buffer = filled(3, columns);
        WindowTextPainter.paint(new Canvas(buffer), 3, columns,
            new WindowTextPainter.Text("A中B", null, null, null, null, null), PALETTE);

        assertEquals(expected, visibleRow(buffer, 0));
        assertEquals(" ".repeat(columns), visibleRow(buffer, 2));
        assertWideCellsComplete(buffer);
    }

    @ParameterizedTest
    @EnumSource(UiStyles.Theme.class)
    void eachThemeProvidesDefaultsAndExplicitStylesCoverTextAndPadding(UiStyles.Theme theme) {
        var palette = UiStyles.forTheme(theme);
        var buffer = filled(6, 12);
        var defaults = new WindowTextPainter.Text("top", "status", "prompt", null, "notice", null);
        WindowTextPainter.paint(new Canvas(buffer), 6, 12, defaults, palette);
        assertRowStyle(buffer, 0, palette.renderTopbar());
        assertRowStyle(buffer, 3, palette.renderNotification());
        assertRowStyle(buffer, 4, palette.renderInteractive());
        assertRowStyle(buffer, 5, palette.renderStatus());
        assertEquals("############", row(buffer, 1));
        assertEquals("############", row(buffer, 2));

        var promptStyle = new Style(Color.BRIGHT_WHITE, Color.RED, true, true);
        var noticeStyle = new Style(Color.BLACK, Color.BRIGHT_YELLOW, false, true);
        WindowTextPainter.paint(new Canvas(buffer), 6, 12,
            new WindowTextPainter.Text("top", "status", "prompt", promptStyle, "notice", noticeStyle), palette);
        assertRowStyle(buffer, 3, EditorStyleAdapter.toCell(noticeStyle));
        assertRowStyle(buffer, 4, EditorStyleAdapter.toCell(promptStyle));
        assertRowStyle(buffer, 0, palette.renderTopbar());
        assertRowStyle(buffer, 5, palette.renderStatus());
    }

    @Test
    void multilinePromptKeepsItsTrailingBlankRowAndNotificationRemainsOneEscapedRow() {
        var buffer = filled(8, 40);
        WindowTextPainter.paint(new Canvas(buffer), 8, 40,
            new WindowTextPainter.Text("top", "status", "Find:\nReplace:\n", null,
                "A\nB\rC\tD\u001B\u0085", null), PALETTE);

        assertEquals("#".repeat(40), row(buffer, 1));
        assertEquals("#".repeat(40), row(buffer, 2));
        assertEquals(padded("A\\nB\\rC\\tD\\u001B\\u0085", 40), row(buffer, 3));
        assertEquals(padded("Find:", 40), row(buffer, 4));
        assertEquals(padded("Replace:", 40), row(buffer, 5));
        assertEquals(" ".repeat(40), row(buffer, 6));
        assertEquals(padded("status", 40), row(buffer, 7));
        assertRowStyle(buffer, 3, PALETTE.renderNotification());
        for (int row = 4; row <= 6; row++) assertRowStyle(buffer, row, PALETTE.renderInteractive());
    }

    @Test
    void hiddenTextClearsBothBarsAndALongNoticeFitsOneRowWithAnEllipsis() {
        var buffer = filled(5, 8);
        WindowTextPainter.paint(new Canvas(buffer), 5, 8,
            new WindowTextPainter.Text(null, null, null, null, "message\nwith details", null), PALETTE);

        assertEquals("        ", row(buffer, 0));
        assertEquals("########", row(buffer, 1));
        assertEquals("########", row(buffer, 2));
        assertEquals("message…", row(buffer, 3));
        assertEquals("        ", row(buffer, 4));
        assertRowStyle(buffer, 0, PALETTE.renderTopbar());
        assertRowStyle(buffer, 3, PALETTE.renderNotification());
        assertRowStyle(buffer, 4, PALETTE.renderStatus());
    }

    @Test
    void tinyWindowsKeepStatusAndTheAvailableTailOfThePrompt() {
        List<List<String>> expected = List.of(
            List.of("status"),
            List.of("", "status"),
            List.of("second", "", "status"),
            List.of("first", "second", "", "status"),
            List.of("notice", "first", "second", "", "status")
        );
        for (int rows = 1; rows <= expected.size(); rows++) {
            var buffer = filled(rows, 8);
            WindowTextPainter.paint(new Canvas(buffer), rows, 8,
                new WindowTextPainter.Text("top", "status", "first\nsecond\n", null, "notice", null), PALETTE);
            for (int row = 0; row < rows; row++) {
                assertEquals(padded(expected.get(rows - 1).get(row), 8), row(buffer, row), "height=" + rows + ", row=" + row);
            }
            assertRowStyle(buffer, rows - 1, PALETTE.renderStatus());
        }
    }

    @ParameterizedTest
    @CsvSource({"0,4", "-1,4", "3,0", "3,-1"})
    void unavailableViewportLeavesTheCanvasUntouched(int rows, int columns) {
        var buffer = filled(3, 4);
        WindowTextPainter.paint(new Canvas(buffer), rows, columns,
            new WindowTextPainter.Text("top", "status", "prompt", null, "notice", null), PALETTE);
        for (int row = 0; row < 3; row++) assertEquals("####", row(buffer, row));
    }

    @Test
    void translatedClippedCanvasIsPreservedForFollowingPainters() {
        var buffer = filled(8, 14);
        var canvas = new Canvas(buffer);
        canvas.translate(2, 1);
        canvas.clip(new Rect(0, 0, 6, 5));
        WindowTextPainter.paint(canvas, 5, 6,
            new WindowTextPainter.Text("中ABCD", "status", "p\nq", null, "notice", null), PALETTE);

        assertEquals("中ABCD", visibleSlice(buffer, 1, 2, 6));
        assertEquals("notice", visibleSlice(buffer, 2, 2, 6));
        assertEquals("p     ", visibleSlice(buffer, 3, 2, 6));
        assertEquals("q     ", visibleSlice(buffer, 4, 2, 6));
        assertEquals("status", visibleSlice(buffer, 5, 2, 6));
        for (int row = 0; row < buffer.rows(); row++) {
            for (int column = 0; column < buffer.cols(); column++) {
                if (row < 1 || row >= 6 || column < 2 || column >= 8) {
                    assertEquals(SENTINEL, buffer.get(row, column), "outside clip at " + row + ":" + column);
                }
            }
        }
        canvas.put(5, 2, "!", CellStyle.DEFAULT);
        assertEquals("!", buffer.get(3, 7).grapheme(), "the painter must restore the caller's clip and origin");
        canvas.put(6, 2, "X", CellStyle.DEFAULT);
        assertEquals(SENTINEL, buffer.get(3, 8), "the original clip must remain in force");
        assertWideCellsComplete(buffer);
    }

    private static CellBuffer filled(int rows, int columns) {
        var buffer = new CellBuffer(rows, columns);
        buffer.fill(SENTINEL);
        return buffer;
    }

    private static String padded(String value, int columns) {
        return value + " ".repeat(columns - value.length());
    }

    private static String row(CellBuffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int column = 0; column < buffer.cols(); column++) text.append(buffer.get(row, column).grapheme());
        return text.toString();
    }

    private static String visibleRow(CellBuffer buffer, int row) {
        return visibleSlice(buffer, row, 0, buffer.cols());
    }

    private static String visibleSlice(CellBuffer buffer, int row, int start, int columns) {
        StringBuilder text = new StringBuilder();
        for (int column = start; column < start + columns; column++) {
            Cell cell = buffer.get(row, column);
            if (!cell.continuation()) text.append(cell.grapheme());
        }
        return text.toString();
    }

    private static void assertRowStyle(CellBuffer buffer, int row, CellStyle expected) {
        for (int column = 0; column < buffer.cols(); column++) {
            assertEquals(expected, buffer.get(row, column).style(), "style at " + row + ":" + column);
        }
    }

    private static void assertWideCellsComplete(CellBuffer buffer) {
        for (int row = 0; row < buffer.rows(); row++) {
            for (int column = 0; column < buffer.cols(); column++) {
                Cell cell = buffer.get(row, column);
                if (cell.continuation()) {
                    assertTrue(column > 0 && buffer.get(row, column - 1).width() == 2, "orphan continuation");
                } else if (cell.width() == 2) {
                    assertTrue(column + 1 < buffer.cols() && buffer.get(row, column + 1).continuation(), "clipped wide glyph");
                }
            }
        }
    }
}
