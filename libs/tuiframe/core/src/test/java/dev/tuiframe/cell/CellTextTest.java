package dev.tuiframe.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.style.CellStyle;
import java.util.List;
import org.junit.jupiter.api.Test;

class CellTextTest {
    @Test
    void measuresClipsAndFitsCompleteGraphemes() {
        assertEquals(6, CellText.width("A😀中e\u0301"));
        assertEquals("A😀 ", CellText.fit("A😀中", 4));
        assertEquals(" 中", CellText.clip("A😀中", 2, 3));
        assertEquals("  中", CellText.alignRight("中", 4));
    }

    @Test
    void clippingPreparesOrphanGraphemesBeforeTheyCanJoinExpandedTabs() {
        assertEquals("    ◌\u0301X", CellText.clip("\t\u0301X", 0, 8));
        assertEquals("    �X", CellText.clip("\t\u200DX", 0, 8));
        assertEquals(" ◌\u0301X", CellText.clip("\t\u0301X", 3, 3));
        assertEquals("  ◌\u0301X", CellText.clip("A😀\t\u0301X", 2, 4));
        assertEquals("◌\u0301X", CellText.fit("\u0301X", 2));
        assertEquals(" ◌\u0301X", CellText.alignRight("\u0301X", 3));
        assertEquals("         X", CellText.alignRight("\tX", 10));
    }

    @Test
    void redrawingEveryClippedSliceMatchesDrawingTheOriginalDisplayCells() {
        for (String source : List.of("\t\u0301X", "\t\u200DX", "\t\uFE0FX", "\t🏽X",
            "\u0301X", "\u200DX", "A😀\t\u0301X", "e\u0301\t👩‍💻X")) {
            int width = CellText.width(source);
            assertEquals(width, CellText.width(CellText.clip(source, 0, width + 1)), source);
            for (int left = 0; left <= width; left++) {
                for (int cells = 1; cells <= width + 1; cells++) {
                    CellBuffer expected = new CellBuffer(1, cells);
                    CellText.draw(new Canvas(expected), -left, 0, source, CellStyle.DEFAULT);
                    CellBuffer actual = new CellBuffer(1, cells);
                    CellText.draw(new Canvas(actual), 0, 0, CellText.clip(source, left, cells), CellStyle.DEFAULT);
                    for (int column = 0; column < cells; column++) {
                        assertEquals(expected.get(0, column), actual.get(0, column),
                            "left=" + left + ", cells=" + cells + ", column=" + column + ", source=" + source);
                    }
                }
            }
        }
    }

    @Test
    void drawExpandsTabsAndKeepsWideCellFootprintsIntact() {
        CellBuffer buffer = new CellBuffer(1, 8);

        int end = CellText.draw(new Canvas(buffer), 0, 0, "a\t中", CellStyle.DEFAULT);

        assertEquals(6, end);
        assertEquals("a", buffer.get(0, 0).grapheme());
        assertEquals(" ", buffer.get(0, 3).grapheme());
        assertEquals("中", buffer.get(0, 4).grapheme());
        assertTrue(buffer.get(0, 5).continuation());
    }

    @Test
    void drawDoesNotEmitHalfOfAWideGrapheme() {
        CellBuffer buffer = new CellBuffer(1, 2);

        int end = CellText.draw(new Canvas(buffer), 1, 0, "中", CellStyle.DEFAULT);

        assertEquals(3, end);
        assertEquals(Cell.EMPTY, buffer.get(0, 1));
    }

    @Test
    void canvasDrawKeepsTextRelativeTabPhaseAcrossTranslationAndLeftClip() {
        CellBuffer buffer = new CellBuffer(2, 10);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        Canvas canvas = new Canvas(buffer);
        canvas.translate(2, 1);
        assertTrue(canvas.clip(1, 0, 6, 1));

        int end = CellText.draw(canvas, -1, 0, "a\t中Z", CellStyle.DEFAULT);

        assertEquals(-1 + CellText.width("a\t中Z"), end);
        assertEquals(".", buffer.get(1, 2).grapheme());
        assertEquals(" ", buffer.get(1, 3).grapheme());
        assertEquals(" ", buffer.get(1, 4).grapheme());
        assertEquals("中", buffer.get(1, 5).grapheme());
        assertTrue(buffer.get(1, 6).continuation());
        assertEquals("Z", buffer.get(1, 7).grapheme());
        assertEquals(".", buffer.get(0, 5).grapheme());
    }

    @Test
    void canvasDrawSkipsPartialWideGlyphAndContinuesAtItsLogicalPosition() {
        CellBuffer buffer = new CellBuffer(1, 5);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        Canvas canvas = new Canvas(buffer);
        assertTrue(canvas.clip(1, 0, 2, 1));

        int end = CellText.draw(canvas, 0, 0, "中B", CellStyle.DEFAULT);

        assertEquals(3, end);
        assertEquals(".", buffer.get(0, 0).grapheme());
        assertEquals(".", buffer.get(0, 1).grapheme());
        assertEquals("B", buffer.get(0, 2).grapheme());
        assertEquals(".", buffer.get(0, 3).grapheme());
    }

    @Test
    void canvasDrawPaintsVisiblePartOfTabWithoutChangingLogicalEnd() {
        CellBuffer buffer = new CellBuffer(1, 6);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        Canvas canvas = new Canvas(buffer);
        assertTrue(canvas.clip(3, 0, 2, 1));

        int end = CellText.draw(canvas, 1, 0, "a\tb", CellStyle.DEFAULT);

        assertEquals(6, end);
        assertEquals(".", buffer.get(0, 2).grapheme());
        assertEquals(" ", buffer.get(0, 3).grapheme());
        assertEquals(" ", buffer.get(0, 4).grapheme());
        assertEquals(".", buffer.get(0, 5).grapheme());
    }

    @Test
    void canvasDrawChecksLogicalOverflowBeforeMutatingGrapheme() {
        CellBuffer buffer = new CellBuffer(1, 2);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(-Integer.MAX_VALUE, 0);
        long signature1 = buffer.rowSignature1(0);
        long signature2 = buffer.rowSignature2(0);

        assertThrows(
            ArithmeticException.class,
            () -> CellText.draw(
                canvas,
                Integer.MAX_VALUE,
                0,
                "a",
                CellStyle.DEFAULT
            )
        );

        assertEquals(signature1, buffer.rowSignature1(0));
        assertEquals(signature2, buffer.rowSignature2(0));
        assertEquals(Cell.EMPTY, buffer.get(0, 0));
    }

    @Test
    void clippedCanvasDrawProcessesOnlyTheVisiblePrefixAndKeepsTabAndWideEdgesAtomic() {
        CellBuffer buffer = new CellBuffer(1, 5);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        Canvas canvas = new Canvas(buffer);

        CellText.drawClipped(
            canvas,
            0,
            0,
            "a\t中" + "tail".repeat(10_000),
            CellStyle.DEFAULT,
            3
        );

        assertEquals("a", buffer.get(0, 0).grapheme());
        assertEquals(" ", buffer.get(0, 1).grapheme());
        assertEquals(" ", buffer.get(0, 2).grapheme());
        assertEquals(".", buffer.get(0, 3).grapheme());

        CellBuffer wide = new CellBuffer(1, 2);
        wide.fill(new Cell(".", CellStyle.DEFAULT));
        CellText.drawClipped(new Canvas(wide), 0, 0, "中tail", CellStyle.DEFAULT, 1);
        assertEquals(".", wide.get(0, 0).grapheme());
        assertEquals(".", wide.get(0, 1).grapheme());

        CellBuffer extreme = new CellBuffer(1, 1);
        Canvas translated = new Canvas(extreme);
        translated.translate(-(Integer.MAX_VALUE - 1), 0);
        CellText.drawClipped(
            translated,
            Integer.MAX_VALUE - 1,
            0,
            "中",
            CellStyle.DEFAULT,
            1
        );
        assertEquals(Cell.EMPTY, extreme.get(0, 0));
    }
}
