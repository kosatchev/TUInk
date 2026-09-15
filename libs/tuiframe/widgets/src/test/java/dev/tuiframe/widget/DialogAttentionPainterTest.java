package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import org.junit.jupiter.api.Test;

class DialogAttentionPainterTest {
    private static final CellStyle BASE = new CellStyle(CellColor.WHITE, CellColor.BLUE, false, true);
    private static final CellStyle ACCENT = new CellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW, true, false);

    @Test
    void framedAttentionRestylesOnlyThePerimeterAndPreservesEveryGlyph() {
        CellBuffer buffer = patternedBuffer(5, 9);
        Cell[][] before = snapshot(buffer);
        Rect bounds = new Rect(1, 1, 6, 3);

        DialogAttentionPainter.paint(buffer, bounds, true, ACCENT);

        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                boolean perimeter = bounds.contains(col, row)
                    && (row == 1 || row == 3 || col == 1 || col == 6);
                assertEquals(perimeter ? ACCENT : before[row][col].style(), buffer.get(row, col).style(), row + ":" + col);
            }
        }
        assertGlyphsUnchanged(before, buffer);
        assertSignaturesMatchRebuiltFrame(buffer);
    }

    @Test
    void inlineAttentionPreservesCjkEmojiCombiningMarksAndTheirContinuationCells() {
        CellBuffer buffer = patternedBuffer(4, 8);
        buffer.set(1, 1, "中", BASE);
        buffer.set(2, 1, "e\u0301", BASE);
        buffer.set(2, 3, "👩‍💻", BASE);
        Cell[][] before = snapshot(buffer);

        DialogAttentionPainter.paint(buffer, new Rect(0, 1, 8, 2), false, ACCENT);

        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                assertEquals(row == 1 || row == 2 ? ACCENT : before[row][col].style(), buffer.get(row, col).style());
            }
        }
        assertWidePair(buffer, 1, 1, "中", ACCENT);
        assertWidePair(buffer, 2, 3, "👩‍💻", ACCENT);
        assertGlyphsUnchanged(before, buffer);
        assertSignaturesMatchRebuiltFrame(buffer);
    }

    @Test
    void inlineAreaClippingSkipsBothHalvesOfEveryPartiallyCoveredWideGlyph() {
        CellBuffer buffer = patternedBuffer(3, 9);
        buffer.set(1, 1, "中", BASE);
        buffer.set(1, 5, "🦀", BASE);
        Cell[][] before = snapshot(buffer);

        DialogAttentionPainter.paint(buffer, new Rect(2, 1, 4, 1), false, ACCENT);

        for (int col = 0; col < buffer.cols(); col++) {
            assertEquals(col == 3 || col == 4 ? ACCENT : before[1][col].style(), buffer.get(1, col).style(), "column " + col);
        }
        assertWidePair(buffer, 1, 1, "中", BASE);
        assertWidePair(buffer, 1, 5, "🦀", BASE);
        assertGlyphsUnchanged(before, buffer);
        assertSignaturesMatchRebuiltFrame(buffer);
    }

    @Test
    void framedSidesSkipWideGlyphsCrossingTheInteriorAndKeepCompleteTopAndBottomGlyphs() {
        CellBuffer buffer = patternedBuffer(5, 10);
        buffer.set(0, 3, "中", BASE);
        buffer.set(1, 2, "🦀", BASE);
        buffer.set(2, 5, "中", BASE);
        buffer.set(3, 1, "🦀", BASE);
        buffer.set(4, 5, "👩‍💻", BASE);
        Cell[][] before = snapshot(buffer);

        DialogAttentionPainter.paint(buffer, new Rect(2, 0, 5, 5), true, ACCENT);

        assertWidePair(buffer, 0, 3, "中", ACCENT);
        assertWidePair(buffer, 1, 2, "🦀", BASE);
        assertWidePair(buffer, 2, 5, "中", BASE);
        assertWidePair(buffer, 3, 1, "🦀", BASE);
        assertWidePair(buffer, 4, 5, "👩‍💻", ACCENT);
        assertEquals(ACCENT, buffer.get(1, 6).style());
        assertEquals(ACCENT, buffer.get(2, 2).style());
        assertEquals(before[2][4], buffer.get(2, 4));
        assertGlyphsUnchanged(before, buffer);
        assertSignaturesMatchRebuiltFrame(buffer);
    }

    @Test
    void aTwoColumnFrameCanRestyleWideGlyphsWhoseEntireFootprintIsPerimeter() {
        CellBuffer buffer = patternedBuffer(4, 5);
        buffer.set(1, 1, "中", BASE);
        buffer.set(2, 1, "🦀", BASE);
        Cell[][] before = snapshot(buffer);

        DialogAttentionPainter.paint(buffer, new Rect(1, 0, 2, 4), true, ACCENT);

        assertWidePair(buffer, 1, 1, "中", ACCENT);
        assertWidePair(buffer, 2, 1, "🦀", ACCENT);
        assertGlyphsUnchanged(before, buffer);
    }

    @Test
    void clippingAFrameDoesNotTurnVisibleInteriorEdgesIntoABorder() {
        CellBuffer buffer = patternedBuffer(4, 7);
        Cell[][] before = snapshot(buffer);

        DialogAttentionPainter.paint(buffer, new Rect(-1, -1, 5, 4), true, ACCENT);

        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                boolean perimeter = row < 3 && col < 4 && (row == 2 || col == 3);
                assertEquals(perimeter ? ACCENT : before[row][col].style(), buffer.get(row, col).style(), row + ":" + col);
            }
        }
        assertGlyphsUnchanged(before, buffer);
    }

    @Test
    void inlineAttentionClipsToTheBufferWithoutTouchingOtherRowsOrColumns() {
        CellBuffer buffer = patternedBuffer(4, 7);
        Cell[][] before = snapshot(buffer);

        DialogAttentionPainter.paint(buffer, new Rect(-4, -2, 9, 4), false, ACCENT);

        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                assertEquals(row < 2 && col < 5 ? ACCENT : before[row][col].style(), buffer.get(row, col).style());
            }
        }
        assertGlyphsUnchanged(before, buffer);
    }

    @Test
    void emptyAndOffscreenBoundsAreNoOpsEvenAtExtremeCoordinates() {
        CellBuffer buffer = patternedBuffer(3, 5);
        Cell[][] before = snapshot(buffer);

        for (Rect bounds : List.of(new Rect(1, 1, 0, 2), new Rect(1, 1, 2, 0),
            new Rect(20, 20, 3, 3), new Rect(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0),
            new Rect(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE))) {
            DialogAttentionPainter.paint(buffer, bounds, false, ACCENT);
            DialogAttentionPainter.paint(buffer, bounds, true, ACCENT);
        }
        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                assertEquals(before[row][col], buffer.get(row, col));
            }
        }
        DialogAttentionPainter.paint(new CellBuffer(0, 0), new Rect(0, 0, 3, 3), true, ACCENT);
    }

    @Test
    void suppliedMonochromeAccentChangesAttributesAndRepeatedPaintingIsIdempotent() {
        CellBuffer buffer = new CellBuffer(1, 5);
        buffer.set(0, 1, "中", CellStyle.DEFAULT);
        Cell[][] before = snapshot(buffer);
        CellStyle monochrome = new CellStyle(CellColor.DEFAULT, CellColor.DEFAULT, true, true);
        Rect bounds = new Rect(0, 0, 5, 1);

        DialogAttentionPainter.paint(buffer, bounds, false, monochrome);

        assertNotEquals(before[0][1].style(), buffer.get(0, 1).style());
        assertWidePair(buffer, 0, 1, "中", monochrome);
        long signature1 = buffer.rowSignature1(0);
        long signature2 = buffer.rowSignature2(0);
        Cell[][] accented = snapshot(buffer);
        DialogAttentionPainter.paint(buffer, bounds, false, monochrome);
        for (int col = 0; col < buffer.cols(); col++) {
            assertEquals(accented[0][col], buffer.get(0, col));
        }
        assertEquals(signature1, buffer.rowSignature1(0));
        assertEquals(signature2, buffer.rowSignature2(0));
        assertGlyphsUnchanged(before, buffer);
    }

    private static CellBuffer patternedBuffer(int rows, int columns) {
        CellBuffer buffer = new CellBuffer(rows, columns);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                buffer.set(row, col, Character.toString('a' + (row * columns + col) % 26), BASE);
            }
        }
        return buffer;
    }

    private static Cell[][] snapshot(CellBuffer buffer) {
        Cell[][] cells = new Cell[buffer.rows()][buffer.cols()];
        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                cells[row][col] = buffer.get(row, col);
            }
        }
        return cells;
    }

    private static void assertGlyphsUnchanged(Cell[][] before, CellBuffer buffer) {
        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                Cell original = before[row][col];
                Cell painted = buffer.get(row, col);
                assertEquals(original.grapheme(), painted.grapheme(), row + ":" + col);
                assertEquals(original.width(), painted.width(), row + ":" + col);
                assertEquals(original.continuation(), painted.continuation(), row + ":" + col);
            }
        }
    }

    private static void assertWidePair(CellBuffer buffer, int row, int col, String grapheme, CellStyle style) {
        Cell lead = buffer.get(row, col);
        Cell continuation = buffer.get(row, col + 1);
        assertEquals(grapheme, lead.grapheme());
        assertEquals(2, lead.width());
        assertFalse(lead.continuation());
        assertTrue(continuation.continuation());
        assertEquals(0, continuation.width());
        assertEquals(style, lead.style());
        assertEquals(style, continuation.style());
    }

    private static void assertSignaturesMatchRebuiltFrame(CellBuffer buffer) {
        CellBuffer rebuilt = new CellBuffer(buffer.rows(), buffer.cols());
        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                Cell cell = buffer.get(row, col);
                if (!cell.continuation()) {
                    rebuilt.set(row, col, cell.grapheme(), cell.style());
                }
            }
            assertEquals(rebuilt.rowSignature1(row), buffer.rowSignature1(row));
            assertEquals(rebuilt.rowSignature2(row), buffer.rowSignature2(row));
        }
    }
}
