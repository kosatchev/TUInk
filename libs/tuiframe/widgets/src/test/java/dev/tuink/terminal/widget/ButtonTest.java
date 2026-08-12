package dev.tuink.terminal.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.cell.Cell;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Point;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.geometry.Size;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import org.junit.jupiter.api.Test;

class ButtonTest {
    private static final CellStyle ACCENT = CellStyle.fg(CellColor.BRIGHT_CYAN);

    @Test
    void canvasPainterMatchesLegacyForAUnicodeProductionButton() {
        Button button = new Button(1, 2, "中e\u0301");
        CellBuffer legacy = filled(3, 12);
        CellBuffer actual = filled(3, 12);

        button.draw(legacy, legacy.cols(), ACCENT);
        button.draw(new Canvas(actual), ACCENT);

        assertBuffersEqual(legacy, actual);
        assertEquals(5, button.width());
        assertEquals(3, button.cursorCol());
        assertTrue(button.hit(1, 2));
        assertTrue(button.hit(1, 6));
        assertFalse(button.hit(1, 7));
    }

    @Test
    void buttonClipProtectsCrossingWidePairAndCanvasState() {
        CellBuffer buffer = filled(1, 5);
        buffer.set(0, 0, "中", ACCENT);
        long signature1 = buffer.rowSignature1(0);
        long signature2 = buffer.rowSignature2(0);
        Canvas canvas = new Canvas(buffer);

        Button.plain(0, 1, "x").draw(canvas, ACCENT);

        assertEquals(signature1, buffer.rowSignature1(0));
        assertEquals(signature2, buffer.rowSignature2(0));
        assertEquals("中", buffer.get(0, 0).grapheme());
        assertTrue(buffer.get(0, 1).continuation());
        assertTrue(canvas.put(4, 0, "z", ACCENT));
    }

    @Test
    void translatedDrawAtUsesTextRelativeTabsWithoutChangingHitGeometry() {
        CellBuffer buffer = filled(3, 10);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(1, 1);
        Button button = Button.plain(0, 1, "\tX");

        button.drawAt(canvas, 1, ACCENT);

        for (int col = 2; col < 6; col++) {
            assertEquals(" ", buffer.get(2, col).grapheme());
            assertEquals(ACCENT, buffer.get(2, col).style());
        }
        assertEquals("X", buffer.get(2, 6).grapheme());
        assertTrue(button.hit(0, 1));
        assertTrue(button.hit(0, 5));
        assertFalse(button.hit(1, 1));
        assertEquals(5, button.cursorCol());
        assertTrue(canvas.put(0, 0, "r", ACCENT));
        assertEquals("r", buffer.get(1, 1).grapheme());
    }

    @Test
    void wideLabelAtPhysicalRightEdgeRemainsTransparent() {
        CellBuffer buffer = filled(1, 4);

        Button.plain(0, 3, "中").draw(new Canvas(buffer), ACCENT);

        assertEquals(".", buffer.get(0, 3).grapheme());
    }

    @Test
    void genericLayoutMeasuresPaintsAndHitsInParentCoordinates() {
        CellStyle normal = CellStyle.fg(CellColor.WHITE);
        CellStyle focused = CellStyle.fg(CellColor.BRIGHT_GREEN);
        CellStyle disabled = CellStyle.fg(CellColor.BRIGHT_BLACK);
        Theme theme = key -> {
            if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
                return focused;
            }
            if (WidgetThemeKeys.BUTTON_DISABLED.equals(key)) {
                return disabled;
            }
            return normal;
        };
        Button button = new Button("中e\u0301");

        assertEquals(new Size(5, 1), button.measure(new Size(20, 3)));
        assertEquals(new Size(3, 0), button.measure(new Size(3, 0)));
        PlacedWidget placed = button.layout(new Rect(2, 1, 5, 1));
        assertEquals(new Rect(2, 1, 5, 1), placed.bounds());
        assertTrue(placed.hit(1, 2));
        assertTrue(placed.hit(new Point(6, 1)));
        assertFalse(placed.hit(2, 2));
        assertFalse(placed.hit(1, 7));

        CellBuffer buffer = filled(3, 10);
        placed.paint(new Canvas(buffer), theme, WidgetState.FOCUSED);

        assertEquals("[", buffer.get(1, 2).grapheme());
        assertEquals(focused, buffer.get(1, 2).style());
        assertEquals("中", buffer.get(1, 3).grapheme());
        assertTrue(buffer.get(1, 4).continuation());
        assertEquals("e\u0301", buffer.get(1, 5).grapheme());
        assertEquals("]", buffer.get(1, 6).grapheme());
    }

    @Test
    void genericButtonHonorsItsAllocatedClipAndDisabledThemeRole() {
        CellStyle disabled = CellStyle.fg(CellColor.BRIGHT_BLACK);
        CellBuffer buffer = filled(2, 8);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(1, 1);
        PlacedWidget placed = Button.plain("a中b").layout(new Rect(1, 0, 2, 1));

        assertTrue(placed.hit(0, 1));
        assertFalse(placed.hit(1, 2));

        placed.paint(
            canvas,
            key -> WidgetThemeKeys.BUTTON_DISABLED.equals(key) ? disabled : ACCENT,
            WidgetState.DISABLED
        );

        assertEquals("a", buffer.get(1, 2).grapheme());
        assertEquals(disabled, buffer.get(1, 2).style());
        assertEquals(".", buffer.get(1, 3).grapheme());
        assertEquals(".", buffer.get(1, 4).grapheme());
        assertTrue(canvas.put(0, 0, "r", ACCENT));
        assertEquals("r", buffer.get(1, 1).grapheme());
    }

    private static CellBuffer filled(int rows, int cols) {
        CellBuffer buffer = new CellBuffer(rows, cols);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        return buffer;
    }

    private static void assertBuffersEqual(CellBuffer expected, CellBuffer actual) {
        assertEquals(expected.rows(), actual.rows());
        assertEquals(expected.cols(), actual.cols());
        for (int row = 0; row < expected.rows(); row++) {
            for (int col = 0; col < expected.cols(); col++) {
                assertEquals(expected.get(row, col), actual.get(row, col), col + "," + row);
            }
            assertEquals(expected.rowSignature1(row), actual.rowSignature1(row));
            assertEquals(expected.rowSignature2(row), actual.rowSignature2(row));
        }
    }
}
