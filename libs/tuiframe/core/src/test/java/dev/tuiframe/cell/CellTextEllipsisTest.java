package dev.tuiframe.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.text.TerminalText;
import java.util.List;
import org.junit.jupiter.api.Test;

class CellTextEllipsisTest {
    @Test
    void keepsOrdinaryTextAndPreferredTrailingCellsWhenShortening() {
        assertEquals("report.txt", CellText.ellipsizeMiddle("report.txt", 20, 4));
        assertEquals("ab….java", CellText.ellipsizeMiddle("abcdefghij.java", 8, 5));
        assertEquals("….txt", CellText.ellipsizeMiddle("abcdefghij.txt", 5, 4));
        assertEquals("…", CellText.ellipsizeMiddle("long", 1, 4));
        assertEquals("", CellText.ellipsizeMiddle("long", 0, 4));
    }

    @Test
    void completenessUsesSanitizedOrphanMarksAndTheExactTabPhase() {
        assertFalse(CellText.fitMiddle("\u0301abcd", 4, 0, 1).complete());
        assertTrue(CellText.fitMiddle("\u0301abcd", 5, 0, 1).complete());
        assertEquals("◌\u0301abcd", CellText.fitMiddle("\u0301abcd", 5, 0, 1).text());
        assertFalse(CellText.fitMiddle("\t", 2, 0, 1).complete());
        assertTrue(CellText.fitMiddle("\t", 3, 0, 1).complete());
        assertTrue(CellText.fitMiddle("", 0, 0, 0).complete());
        assertFalse(CellText.fitMiddle("a", 0, 0, 0).complete());
    }

    @Test
    void usesWholeVisibleGraphemesAcrossEveryNarrowWidth() {
        String source = "Ae\u0301👩‍💻界-long-name.ext";
        for (int width = 1; width <= 35; width++) {
            String actual = CellText.ellipsizeMiddle(source, width, 4);
            assertTrue(CellText.width(actual) <= width, actual);
            for (TerminalText.Grapheme grapheme : TerminalText.graphemes(actual)) {
                assertFalse(List.of("👩", "💻", "\u200d", "\u0301").contains(grapheme.text()), actual);
            }
            if (width >= 7) assertTrue(actual.endsWith(".ext"), actual);
        }
    }

    @Test
    void rendersTabsAndControlsWithTheOriginalTabPhase() {
        assertEquals("␁  中e\u0301", CellText.ellipsizeMiddle("\u0001\t中e\u0301", 12, 0, 1));
        assertEquals("◌\u0301", CellText.ellipsizeMiddle("\u0301", 1, 0));
    }

    @Test
    void longLabelsRetainOnlyTheVisibleEnds() {
        String result = CellText.ellipsizeMiddle("a".repeat(1_000_000) + "界.ext", 20, 4);
        assertTrue(result.endsWith("界.ext"), result);
        assertEquals(20, CellText.width(result));
    }
}
