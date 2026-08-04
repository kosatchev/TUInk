package dev.tuink.terminal.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.text.TerminalText;
import dev.tuink.terminal.style.CellStyle;
import org.junit.jupiter.api.Test;

class CellBufferUnicodeTest {
    @Test
    void wideCellOwnsAContinuationAndOverwriteClearsTheWholeFootprint() {
        CellBuffer buffer = new CellBuffer(1, 4);
        buffer.set(0, 1, "中", CellStyle.DEFAULT);

        assertEquals(2, buffer.get(0, 1).width());
        assertTrue(buffer.get(0, 2).continuation());

        buffer.set(0, 2, "x", CellStyle.DEFAULT);

        assertEquals(" ", buffer.get(0, 1).grapheme());
        assertFalse(buffer.get(0, 1).continuation());
        assertEquals("x", buffer.get(0, 2).grapheme());
    }

    @Test
    void replacingAsciiWithWideCellClearsBothDestinationCells() {
        CellBuffer buffer = new CellBuffer(1, 4);
        buffer.set(0, 1, "a", CellStyle.DEFAULT);
        buffer.set(0, 2, "b", CellStyle.DEFAULT);

        buffer.set(0, 1, "😀", CellStyle.DEFAULT);

        assertEquals("😀", buffer.get(0, 1).grapheme());
        assertEquals(2, buffer.get(0, 1).width());
        assertTrue(buffer.get(0, 2).continuation());
    }

    @Test
    void controlPayloadsAndUnpairedSurrogatesNeverEnterCells() {
        CellBuffer buffer = new CellBuffer(1, 5);
        buffer.set(0, 0, "\u001B", CellStyle.DEFAULT);
        buffer.set(0, 1, "\u0007", CellStyle.DEFAULT);
        buffer.set(0, 2, "\u009B", CellStyle.DEFAULT);
        buffer.set(0, 3, "\uD83D", CellStyle.DEFAULT);

        assertEquals("␛", buffer.get(0, 0).grapheme());
        assertEquals("␇", buffer.get(0, 1).grapheme());
        assertEquals("�", buffer.get(0, 2).grapheme());
        assertEquals("�", buffer.get(0, 3).grapheme());
        for (int col = 0; col < buffer.cols(); col++) {
            assertFalse(TerminalText.containsUnsafeControl(buffer.get(0, col).grapheme()));
        }
    }

    @Test
    void rejectsMultipleGraphemesAsOnePhysicalCell() {
        CellBuffer buffer = new CellBuffer(1, 2);

        assertThrows(
            IllegalArgumentException.class,
            () -> buffer.set(0, 0, "ab", CellStyle.DEFAULT)
        );
        assertEquals(Cell.EMPTY, buffer.get(0, 0));
        assertEquals(Cell.EMPTY, buffer.get(0, 1));
    }

    @Test
    void wideFillPreservesLeadContinuationInvariantAtOddWidth() {
        CellBuffer buffer = new CellBuffer(1, 5);
        buffer.fill(new Cell("界", CellStyle.DEFAULT));

        assertEquals(2, buffer.get(0, 0).width());
        assertTrue(buffer.get(0, 1).continuation());
        assertEquals(2, buffer.get(0, 2).width());
        assertTrue(buffer.get(0, 3).continuation());
        assertEquals("�", buffer.get(0, 4).grapheme());
        assertEquals(1, buffer.get(0, 4).width());
    }
}
