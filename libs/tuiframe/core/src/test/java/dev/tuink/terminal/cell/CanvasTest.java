package dev.tuink.terminal.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CanvasTest {
    private static final CellStyle ACCENT = CellStyle.fg(CellColor.BRIGHT_CYAN);

    @Test
    void nestedTranslationAndClippingRestoreWithoutLeakingWrites() {
        CellBuffer buffer = filled(4, 8, ".");
        Canvas canvas = new Canvas(buffer);
        int root = canvas.save();
        canvas.translate(2, 1);
        assertTrue(canvas.clip(new Rect(1, 1, 3, 2)));

        assertFalse(canvas.put(0, 1, "outside", ACCENT));
        assertTrue(canvas.put(1, 1, "a", ACCENT));

        int nested = canvas.save();
        canvas.translate(1, 0);
        assertTrue(canvas.clip(1, 1, 1, 1));
        assertTrue(canvas.put(1, 1, "b", ACCENT));
        assertFalse(canvas.put(2, 1, "outside", ACCENT));

        canvas.restore(nested);
        assertTrue(canvas.put(3, 2, "c", ACCENT));
        canvas.restore(root);
        assertTrue(canvas.put(7, 0, "r", ACCENT));

        assertEquals(".", buffer.get(2, 2).grapheme());
        assertEquals("a", buffer.get(2, 3).grapheme());
        assertEquals("b", buffer.get(2, 4).grapheme());
        assertEquals("c", buffer.get(3, 5).grapheme());
        assertEquals("r", buffer.get(0, 7).grapheme());
        assertThrows(IllegalArgumentException.class, () -> canvas.restore(nested));
    }

    @Test
    void disjointAndInvalidClipsAreSafeAndRestorable() {
        CellBuffer buffer = filled(2, 3, ".");
        Canvas canvas = new Canvas(buffer);
        int mark = canvas.save();

        assertFalse(canvas.clip(100, 100, 2, 2));
        assertFalse(canvas.put(0, 0, "x", ACCENT));
        assertFalse(canvas.clip(0, 0, 1, 1));

        canvas.restore(mark);
        assertTrue(canvas.put(0, 0, "x", ACCENT));
        assertEquals("x", buffer.get(0, 0).grapheme());
        assertThrows(IllegalArgumentException.class, () -> canvas.clip(0, 0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> canvas.restore(-1));
        assertThrows(IllegalArgumentException.class, () -> canvas.restore(Integer.MAX_VALUE - 1));
    }

    @Test
    void negativeTranslationMapsLocalGeometryBackIntoTheBuffer() {
        CellBuffer buffer = filled(2, 3, ".");
        Canvas canvas = new Canvas(buffer);
        canvas.translate(-2, -1);
        assertTrue(canvas.clip(new Rect(2, 1, 3, 2)));

        assertTrue(canvas.put(2, 1, "a", ACCENT));
        assertTrue(canvas.put(4, 2, "b", ACCENT));
        assertFalse(canvas.put(1, 1, "outside", ACCENT));

        assertEquals("a", buffer.get(0, 0).grapheme());
        assertEquals("b", buffer.get(1, 2).grapheme());
    }

    @Test
    void wideAndCombiningGraphemesArePlacedOnlyAsCompleteFootprints() {
        CellBuffer buffer = filled(1, 7, ".");
        Canvas canvas = new Canvas(buffer);
        assertTrue(canvas.clip(1, 0, 5, 1));

        long beforePartialLeft1 = buffer.rowSignature1(0);
        long beforePartialLeft2 = buffer.rowSignature2(0);
        assertFalse(canvas.put(0, 0, "中", ACCENT));
        assertEquals(beforePartialLeft1, buffer.rowSignature1(0));
        assertEquals(beforePartialLeft2, buffer.rowSignature2(0));

        assertTrue(canvas.put(1, 0, "中", ACCENT));
        assertEquals("中", buffer.get(0, 1).grapheme());
        assertEquals(2, buffer.get(0, 1).width());
        assertTrue(buffer.get(0, 2).continuation());
        assertEquals(ACCENT, buffer.get(0, 2).style());

        assertTrue(canvas.put(3, 0, "e\u0301", ACCENT));
        assertEquals("e\u0301", buffer.get(0, 3).grapheme());
        assertTrue(canvas.put(4, 0, "😀", ACCENT));
        assertTrue(buffer.get(0, 5).continuation());

        long beforePartialRight1 = buffer.rowSignature1(0);
        long beforePartialRight2 = buffer.rowSignature2(0);
        assertFalse(canvas.put(5, 0, "界", ACCENT));
        assertEquals(beforePartialRight1, buffer.rowSignature1(0));
        assertEquals(beforePartialRight2, buffer.rowSignature2(0));
        assertEquals(".", buffer.get(0, 6).grapheme());
    }

    @Test
    void existingWidePairCrossingEitherClipEdgeIsAtomicAndProtected() {
        CellBuffer left = filled(1, 6, ".");
        left.set(0, 0, "中", ACCENT);
        Canvas leftCanvas = new Canvas(left);
        assertTrue(leftCanvas.clip(1, 0, 4, 1));
        long leftSignature1 = left.rowSignature1(0);
        long leftSignature2 = left.rowSignature2(0);

        assertFalse(leftCanvas.put(1, 0, "x", ACCENT));
        assertEquals(leftSignature1, left.rowSignature1(0));
        assertEquals(leftSignature2, left.rowSignature2(0));
        assertEquals("中", left.get(0, 0).grapheme());
        assertTrue(left.get(0, 1).continuation());

        CellBuffer right = filled(1, 6, ".");
        right.set(0, 4, "😀", ACCENT);
        Canvas rightCanvas = new Canvas(right);
        assertTrue(rightCanvas.clip(0, 0, 5, 1));
        long rightSignature1 = right.rowSignature1(0);
        long rightSignature2 = right.rowSignature2(0);

        assertFalse(rightCanvas.put(4, 0, "x", ACCENT));
        assertEquals(rightSignature1, right.rowSignature1(0));
        assertEquals(rightSignature2, right.rowSignature2(0));
        assertEquals("😀", right.get(0, 4).grapheme());
        assertTrue(right.get(0, 5).continuation());
    }

    @Test
    void existingWidePairWhollyInsideClipCanBeOverwritten() {
        CellBuffer buffer = filled(1, 5, ".");
        buffer.set(0, 1, "中", ACCENT);
        Canvas canvas = new Canvas(buffer);
        assertTrue(canvas.clip(1, 0, 3, 1));

        assertTrue(canvas.put(2, 0, "x", ACCENT));

        assertEquals(Cell.EMPTY, buffer.get(0, 1));
        assertEquals("x", buffer.get(0, 2).grapheme());
        assertFalse(buffer.get(0, 2).continuation());
    }

    @Test
    void rootCanvasSkipsWideGlyphAtPhysicalEdgeInsteadOfUsingLegacyFallback() {
        CellBuffer canvasBuffer = new CellBuffer(1, 2);
        Canvas canvas = new Canvas(canvasBuffer);

        assertFalse(canvas.put(1, 0, "中", ACCENT));
        assertEquals(Cell.EMPTY, canvasBuffer.get(0, 1));

        CellBuffer legacyBuffer = new CellBuffer(1, 2);
        legacyBuffer.set(0, 1, "中", ACCENT);
        assertEquals("�", legacyBuffer.get(0, 1).grapheme());
    }

    @Test
    void rejectsMultipleGraphemesWithoutMutatingTheTarget() {
        CellBuffer buffer = filled(1, 2, ".");
        Canvas canvas = new Canvas(buffer);
        long signature1 = buffer.rowSignature1(0);
        long signature2 = buffer.rowSignature2(0);

        assertThrows(
            IllegalArgumentException.class,
            () -> canvas.put(0, 0, "ab", ACCENT)
        );

        assertEquals(signature1, buffer.rowSignature1(0));
        assertEquals(signature2, buffer.rowSignature2(0));
        assertEquals(".", buffer.get(0, 0).grapheme());
    }

    @Test
    void immutableCellCanBeReusedAcrossClippedWrites() {
        CellBuffer buffer = filled(1, 4, ".");
        Canvas canvas = new Canvas(buffer);
        assertTrue(canvas.clip(1, 0, 2, 1));
        Cell shared = new Cell("x", ACCENT);

        assertFalse(canvas.put(0, 0, shared));
        assertTrue(canvas.put(1, 0, shared));
        assertTrue(canvas.put(2, 0, shared));
        assertFalse(canvas.put(3, 0, shared));

        assertSame(shared, buffer.get(0, 1));
        assertSame(shared, buffer.get(0, 2));
    }

    @Test
    void fillTreatsItsRectangleAsAStrictWideCellBoundary() {
        CellBuffer buffer = filled(2, 8, ".");
        buffer.set(0, 0, "中", ACCENT);
        buffer.set(0, 6, "界", ACCENT);
        Cell leftLead = buffer.get(0, 0);
        Cell leftContinuation = buffer.get(0, 1);
        Cell rightLead = buffer.get(0, 6);
        Cell rightContinuation = buffer.get(0, 7);
        Canvas canvas = new Canvas(buffer);

        canvas.fill(new Rect(1, 0, 6, 1), ACCENT);

        assertEquals("中", buffer.get(0, 0).grapheme());
        assertTrue(buffer.get(0, 1).continuation());
        assertEquals("界", buffer.get(0, 6).grapheme());
        assertTrue(buffer.get(0, 7).continuation());
        assertSame(leftLead, buffer.get(0, 0));
        assertSame(leftContinuation, buffer.get(0, 1));
        assertSame(rightLead, buffer.get(0, 6));
        assertSame(rightContinuation, buffer.get(0, 7));
        Cell firstBlank = buffer.get(0, 2);
        for (int col = 2; col < 6; col++) {
            assertEquals(" ", buffer.get(0, col).grapheme());
            assertEquals(ACCENT, buffer.get(0, col).style());
            assertSame(firstBlank, buffer.get(0, col));
        }
        for (int col = 0; col < buffer.cols(); col++) {
            assertEquals(".", buffer.get(1, col).grapheme());
        }
        CellBuffer rebuilt = rebuildRow(buffer, 0);
        assertEquals(rebuilt.rowSignature1(0), buffer.rowSignature1(0));
        assertEquals(rebuilt.rowSignature2(0), buffer.rowSignature2(0));
    }

    @Test
    void fillIntersectsTranslationOuterClipAndRequestedGeometryWithoutChangingState() {
        CellBuffer buffer = filled(3, 6, ".");
        Canvas canvas = new Canvas(buffer);
        canvas.translate(1, 1);
        assertTrue(canvas.clip(0, 0, 4, 2));

        canvas.fill(-1, 0, 4, 3, ACCENT);

        for (int row = 1; row < 3; row++) {
            assertEquals(".", buffer.get(row, 0).grapheme());
            for (int col = 1; col < 4; col++) {
                assertEquals(" ", buffer.get(row, col).grapheme());
                assertEquals(ACCENT, buffer.get(row, col).style());
            }
            assertEquals(".", buffer.get(row, 4).grapheme());
        }
        assertTrue(canvas.put(3, 0, "x", ACCENT));
        assertEquals("x", buffer.get(1, 4).grapheme());
        assertThrows(IllegalArgumentException.class, () -> canvas.fill(0, 0, -1, 1, ACCENT));
    }

    @Test
    void fillOverwritesWidePairWhollyInsideItsBoundary() {
        CellBuffer buffer = filled(1, 6, ".");
        buffer.set(0, 2, "中", ACCENT);
        Canvas canvas = new Canvas(buffer);

        canvas.fill(1, 0, 4, 1, ACCENT);

        Cell firstBlank = buffer.get(0, 1);
        for (int col = 1; col < 5; col++) {
            assertEquals(" ", buffer.get(0, col).grapheme());
            assertFalse(buffer.get(0, col).continuation());
            assertSame(firstBlank, buffer.get(0, col));
        }
        CellBuffer rebuilt = rebuildRow(buffer, 0);
        assertEquals(rebuilt.rowSignature1(0), buffer.rowSignature1(0));
        assertEquals(rebuilt.rowSignature2(0), buffer.rowSignature2(0));
    }

    @Test
    void fillUsesLongGeometryForExtremeTranslatedCoordinates() {
        CellBuffer buffer = filled(1, 2, ".");
        Canvas canvas = new Canvas(buffer);
        canvas.translate(Integer.MAX_VALUE, Integer.MIN_VALUE);

        canvas.fill(Integer.MIN_VALUE + 1, Integer.MAX_VALUE, 2, 2, ACCENT);

        assertEquals(" ", buffer.get(0, 0).grapheme());
        assertEquals(" ", buffer.get(0, 1).grapheme());
        long signature1 = buffer.rowSignature1(0);
        long signature2 = buffer.rowSignature2(0);
        canvas.fill(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, null);
        assertEquals(signature1, buffer.rowSignature1(0));
        assertEquals(signature2, buffer.rowSignature2(0));
    }

    @Test
    void immutableCellFillVisitsOnlyVisibleCellsAndProtectsCrossingWidePairs() {
        CellBuffer buffer = filled(2, 6, ".");
        buffer.set(0, 0, "中", ACCENT);
        buffer.set(0, 4, "界", ACCENT);
        Cell leftLead = buffer.get(0, 0);
        Cell leftContinuation = buffer.get(0, 1);
        Cell rightLead = buffer.get(0, 4);
        Cell rightContinuation = buffer.get(0, 5);
        Cell dash = new Cell("-", ACCENT);
        Canvas canvas = new Canvas(buffer);
        assertTrue(canvas.clip(1, 0, 4, 1));

        canvas.fillCell(0, 0, Integer.MAX_VALUE, 1, dash);

        assertSame(leftLead, buffer.get(0, 0));
        assertSame(leftContinuation, buffer.get(0, 1));
        assertSame(dash, buffer.get(0, 2));
        assertSame(dash, buffer.get(0, 3));
        assertSame(rightLead, buffer.get(0, 4));
        assertSame(rightContinuation, buffer.get(0, 5));

        new Canvas(buffer).fillCell(new Rect(2, 1, 2, 1), dash);
        assertSame(dash, buffer.get(1, 2));
        assertSame(dash, buffer.get(1, 3));
    }

    @Test
    void immutableCellFillRejectsWideAndContinuationPatternsWithoutMutation() {
        CellBuffer buffer = filled(1, 3, ".");
        Canvas canvas = new Canvas(buffer);
        long signature1 = buffer.rowSignature1(0);
        long signature2 = buffer.rowSignature2(0);

        assertThrows(
            IllegalArgumentException.class,
            () -> canvas.fillCell(0, 0, 3, 1, new Cell("中", ACCENT))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> canvas.fillCell(0, 0, 3, 1, Cell.continuation(ACCENT))
        );

        assertEquals(signature1, buffer.rowSignature1(0));
        assertEquals(signature2, buffer.rowSignature2(0));
    }

    @Test
    void randomizedClippedWritesPreserveOutsideCellsWidePairsAndSignatures() {
        CellBuffer buffer = filled(4, 12, ".");
        buffer.set(1, 1, "中", ACCENT);
        buffer.set(2, 9, "😀", ACCENT);
        Cell[][] outsideBefore = snapshot(buffer);
        Rect clip = new Rect(2, 1, 8, 2);
        Canvas canvas = new Canvas(buffer);
        assertTrue(canvas.clip(clip));
        String[] graphemes = {"x", "中", "😀", "e\u0301", "\u001B"};
        Random random = new Random(0xCA4A5L);

        for (int iteration = 0; iteration < 1_000; iteration++) {
            int x = random.nextInt(16) - 2;
            int y = random.nextInt(6) - 1;
            canvas.put(x, y, graphemes[random.nextInt(graphemes.length)], ACCENT);
            assertWideInvariants(buffer);
        }

        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                if (!clip.contains(col, row)) {
                    assertEquals(outsideBefore[row][col], buffer.get(row, col), "outside " + col + "," + row);
                }
            }
            CellBuffer rebuilt = rebuildRow(buffer, row);
            assertEquals(rebuilt.rowSignature1(0), buffer.rowSignature1(row), "signature 1 at row " + row);
            assertEquals(rebuilt.rowSignature2(0), buffer.rowSignature2(row), "signature 2 at row " + row);
        }
    }

    private static void assertWideInvariants(CellBuffer buffer) {
        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                Cell cell = buffer.get(row, col);
                if (cell.continuation()) {
                    assertTrue(col > 0, "continuation at first column");
                    Cell lead = buffer.get(row, col - 1);
                    assertEquals(2, lead.width(), "continuation without lead");
                    assertEquals(lead.style(), cell.style(), "wide pair style");
                } else if (cell.width() == 2) {
                    assertTrue(col + 1 < buffer.cols(), "wide lead at last column");
                    Cell continuation = buffer.get(row, col + 1);
                    assertTrue(continuation.continuation(), "wide lead without continuation");
                    assertEquals(cell.style(), continuation.style(), "wide pair style");
                }
            }
        }
    }

    private static CellBuffer rebuildRow(CellBuffer source, int sourceRow) {
        CellBuffer rebuilt = new CellBuffer(1, source.cols());
        for (int col = 0; col < source.cols(); col++) {
            Cell cell = source.get(sourceRow, col);
            if (!cell.continuation()) {
                rebuilt.set(0, col, cell.grapheme(), cell.style());
            }
        }
        return rebuilt;
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

    private static CellBuffer filled(int rows, int cols, String grapheme) {
        CellBuffer buffer = new CellBuffer(rows, cols);
        buffer.fill(new Cell(grapheme, CellStyle.DEFAULT));
        return buffer;
    }
}
