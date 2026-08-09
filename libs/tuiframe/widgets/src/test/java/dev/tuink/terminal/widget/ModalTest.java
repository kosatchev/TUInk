package dev.tuink.terminal.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.cell.Cell;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import org.junit.jupiter.api.Test;

class ModalTest {
    private static final CellStyle BODY = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle BORDER = CellStyle.fg(CellColor.BRIGHT_BLUE);
    private static final CellStyle TITLE = CellStyle.fg(CellColor.BRIGHT_CYAN);
    private static final CellStyle ACCENT = CellStyle.fg(CellColor.BRIGHT_MAGENTA);

    @Test
    void canvasPainterMatchesLegacyForAnInBoundsUnicodeTitle() {
        Modal modal = new Modal(1, 2, 14, 4, " T中e\u0301 ", BODY, BORDER, TITLE);
        CellBuffer legacy = filled(7, 20);
        CellBuffer actual = filled(7, 20);

        modal.draw(legacy, legacy.rows(), legacy.cols());
        modal.draw(new Canvas(actual));

        assertBuffersEqual(legacy, actual);
        assertEquals("X", actual.get(1, modal.closeButton().col()).grapheme());
        assertEquals(TITLE, actual.get(1, modal.closeButton().col()).style());
        assertSame(actual.get(1, 3), actual.get(1, 11));
        assertSame(actual.get(1, 3), actual.get(4, 3));
        assertSame(actual.get(2, 2), actual.get(2, 15));
        assertSame(actual.get(2, 3), actual.get(2, 4));
    }

    @Test
    void titleClipProtectsWideCloseButtonBoundaryAndRightCorner() {
        CellBuffer buffer = filled(5, 13);
        Modal modal = new Modal(1, 1, 10, 3, "abcd中Z", BODY, BORDER, TITLE);

        modal.draw(new Canvas(buffer));

        assertEquals("a", buffer.get(1, 3).grapheme());
        assertEquals("d", buffer.get(1, 6).grapheme());
        assertEquals("─", buffer.get(1, 7).grapheme());
        assertEquals("X", buffer.get(1, 8).grapheme());
        assertEquals("─", buffer.get(1, 9).grapheme());
        assertEquals("┐", buffer.get(1, 10).grapheme());
        assertEquals(".", buffer.get(1, 11).grapheme());
    }

    @Test
    void translatedParentClipIsHonoredAndCanvasStateIsRestored() {
        CellBuffer buffer = filled(7, 14);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(2, 1);
        assertTrue(canvas.clip(0, 0, 7, 6));
        Modal modal = new Modal(1, 1, 8, 4, " title ", BODY, BORDER, TITLE);

        modal.draw(canvas);

        assertEquals("┌", buffer.get(2, 3).grapheme());
        assertEquals("X", buffer.get(2, 8).grapheme());
        assertEquals(".", buffer.get(2, 9).grapheme());
        assertEquals(".", buffer.get(2, 2).grapheme());
        assertTrue(canvas.put(0, 0, "z", ACCENT));
        assertEquals("z", buffer.get(1, 2).grapheme());
    }

    @Test
    void strictModalBoundaryPreservesCrossingWidePairsAndClearsInternalPair() {
        CellBuffer buffer = filled(5, 12);
        buffer.set(2, 1, "中", ACCENT);
        buffer.set(2, 5, "😀", ACCENT);
        buffer.set(2, 9, "界", ACCENT);
        Cell leftLead = buffer.get(2, 1);
        Cell leftContinuation = buffer.get(2, 2);
        Cell rightLead = buffer.get(2, 9);
        Cell rightContinuation = buffer.get(2, 10);
        Modal modal = new Modal(1, 2, 8, 3, " ok ", BODY, BORDER, TITLE);

        modal.draw(new Canvas(buffer));

        assertSame(leftLead, buffer.get(2, 1));
        assertSame(leftContinuation, buffer.get(2, 2));
        assertSame(rightLead, buffer.get(2, 9));
        assertSame(rightContinuation, buffer.get(2, 10));
        assertEquals(" ", buffer.get(2, 5).grapheme());
        assertEquals(BODY, buffer.get(2, 5).style());
        assertEquals(" ", buffer.get(2, 6).grapheme());
        assertEquals(BODY, buffer.get(2, 6).style());
        assertSignaturesMatchRebuiltBuffer(buffer);
    }

    @Test
    void narrowAndNonPositiveGeometryNeverSpills() {
        CellBuffer buffer = filled(2, 5);
        Canvas canvas = new Canvas(buffer);

        new Modal(0, 1, 1, 1, "title", BODY, BORDER, TITLE).draw(canvas);

        assertEquals("┌", buffer.get(0, 1).grapheme());
        assertEquals(".", buffer.get(0, 0).grapheme());
        assertEquals(".", buffer.get(0, 2).grapheme());
        long signature1 = buffer.rowSignature1(0);
        long signature2 = buffer.rowSignature2(0);

        new Modal(0, 0, 0, 2, "title", BODY, BORDER, TITLE).draw(canvas);
        new Modal(0, 0, -1, 2, "title", BODY, BORDER, TITLE).draw(canvas);
        new Modal(0, 0, 2, 0, "title", BODY, BORDER, TITLE).draw(canvas);
        new Modal(0, 0, 2, -1, "title", BODY, BORDER, TITLE).draw(canvas);
        new Modal(0, Integer.MAX_VALUE, 1, 1, "title", BODY, BORDER, TITLE).draw(canvas);

        assertEquals(signature1, buffer.rowSignature1(0));
        assertEquals(signature2, buffer.rowSignature2(0));
    }

    @Test
    void hugeLogicalFrameVisitsOnlyItsVisibleIntersection() {
        CellBuffer buffer = filled(2, 5);
        Modal modal = new Modal(0, 0, Integer.MAX_VALUE, 2, "", BODY, BORDER, TITLE);

        modal.draw(new Canvas(buffer));

        assertEquals("┌", buffer.get(0, 0).grapheme());
        assertEquals("└", buffer.get(1, 0).grapheme());
        for (int col = 1; col < buffer.cols(); col++) {
            assertEquals("─", buffer.get(0, col).grapheme());
            assertEquals("─", buffer.get(1, col).grapheme());
        }
    }

    @Test
    void exceptionalPaintRestoresCanvasAndOverflowDoesNotMutate() {
        CellBuffer buffer = filled(3, 7);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(1, 0);
        assertTrue(canvas.clip(0, 0, 6, 3));
        Modal invalidStyle = new Modal(0, 1, 4, 2, "", BODY, null, TITLE);

        assertThrows(IllegalArgumentException.class, () -> invalidStyle.draw(canvas));
        assertTrue(canvas.put(0, 0, "z", ACCENT));
        assertEquals("z", buffer.get(0, 1).grapheme());
        long signature1 = buffer.rowSignature1(1);
        long signature2 = buffer.rowSignature2(1);

        Modal overflowing = new Modal(0, Integer.MAX_VALUE, 2, 2, "", BODY, BORDER, TITLE);
        assertThrows(ArithmeticException.class, () -> overflowing.draw(canvas));

        assertEquals(signature1, buffer.rowSignature1(1));
        assertEquals(signature2, buffer.rowSignature2(1));
        assertTrue(canvas.put(0, 1, "y", ACCENT));
        assertEquals("y", buffer.get(1, 1).grapheme());
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

    private static void assertSignaturesMatchRebuiltBuffer(CellBuffer actual) {
        CellBuffer rebuilt = new CellBuffer(actual.rows(), actual.cols());
        for (int row = 0; row < actual.rows(); row++) {
            for (int col = 0; col < actual.cols(); col++) {
                Cell cell = actual.get(row, col);
                if (!cell.continuation()) {
                    rebuilt.set(row, col, cell.grapheme(), cell.style());
                }
            }
            assertEquals(rebuilt.rowSignature1(row), actual.rowSignature1(row));
            assertEquals(rebuilt.rowSignature2(row), actual.rowSignature2(row));
        }
    }
}
