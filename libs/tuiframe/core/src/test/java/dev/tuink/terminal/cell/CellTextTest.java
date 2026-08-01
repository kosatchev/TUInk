package dev.tuink.terminal.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.style.CellStyle;
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
    void drawExpandsTabsAndKeepsWideCellFootprintsIntact() {
        CellBuffer buffer = new CellBuffer(1, 8);

        int end = CellText.draw(buffer, 0, 0, "a\t中", CellStyle.DEFAULT, 8);

        assertEquals(6, end);
        assertEquals("a", buffer.get(0, 0).grapheme());
        assertEquals(" ", buffer.get(0, 3).grapheme());
        assertEquals("中", buffer.get(0, 4).grapheme());
        assertTrue(buffer.get(0, 5).continuation());
    }

    @Test
    void drawDoesNotEmitHalfOfAWideGrapheme() {
        CellBuffer buffer = new CellBuffer(1, 2);

        int end = CellText.draw(buffer, 0, 1, "中", CellStyle.DEFAULT, 2);

        assertEquals(1, end);
        assertEquals(Cell.EMPTY, buffer.get(0, 1));
    }
}
