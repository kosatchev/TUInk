package dev.tuiframe.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.text.TerminalText;
import dev.tuiframe.style.CellStyle;
import org.junit.jupiter.api.Test;

class CellBufferUnicodeTest {
    @Test
    void oversizedClustersUseBoundedPlaceholdersWithoutMovingFollowingText() {
        String marks = "\u0301".repeat(1_000_000);
        for (String base : new String[] {"a", "中", "😀"}) {
            String cluster = base + marks;
            int width = TerminalText.graphemeWidth(cluster);
            CellBuffer buffer = new CellBuffer(1, 4);

            buffer.set(0, 0, cluster, CellStyle.DEFAULT);
            buffer.set(0, width, "B", CellStyle.DEFAULT);

            assertTrue(buffer.get(0, 0).grapheme().length() <= TerminalText.MAX_DISPLAY_GRAPHEME_LENGTH);
            assertEquals(width, buffer.get(0, 0).width());
            assertEquals("B", buffer.get(0, width).grapheme());
            if (width == 2) assertTrue(buffer.get(0, 1).continuation());
        }
    }

    @Test
    void addingADottedCircleCannotExceedTheCellPayloadBudget() {
        Cell cell = new Cell("\u0301".repeat(TerminalText.MAX_DISPLAY_GRAPHEME_LENGTH), CellStyle.DEFAULT);
        assertTrue(cell.grapheme().length() <= TerminalText.MAX_DISPLAY_GRAPHEME_LENGTH);
        assertEquals(1, cell.width());
    }

    @Test
    void oversizedOrdinaryTextStillCannotBeStoredAsOneCell() {
        assertThrows(IllegalArgumentException.class,
            () -> new Cell("a".repeat(1000), CellStyle.DEFAULT));
    }

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
