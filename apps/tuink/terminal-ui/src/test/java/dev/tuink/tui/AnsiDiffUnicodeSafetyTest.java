package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.render.Style;
import dev.tuink.tui.render.CellBuffer;
import org.junit.jupiter.api.Test;

class AnsiDiffUnicodeSafetyTest {
    @Test
    void emitsWideGraphemeOnceAndNeverEmitsContinuationPayload() {
        CellBuffer current = new CellBuffer(1, 4);
        current.set(0, 1, "中", Style.DEFAULT);

        String ansi = AnsiDiff.diff(null, current, false);

        assertEquals(1, occurrences(ansi, "中"));
    }

    @Test
    void documentControlPayloadIsRenderedVisiblyInsteadOfExecuted() {
        CellBuffer current = new CellBuffer(1, 8);
        current.set(0, 0, "\u001B]52;c;secret\u0007", Style.DEFAULT);

        String ansi = AnsiDiff.diff(null, current, false);

        assertFalse(ansi.contains("]52;c;secret"));
        assertFalse(ansi.contains("\u0007"));
        assertTrue(ansi.contains("�"));
    }

    @Test
    void changingWideRowToAsciiRedrawsACompleteSafeRow() {
        CellBuffer previous = new CellBuffer(1, 4);
        previous.set(0, 0, "中", Style.DEFAULT);
        CellBuffer current = new CellBuffer(1, 4);
        current.set(0, 0, "a", Style.DEFAULT);
        current.set(0, 1, "b", Style.DEFAULT);

        String ansi = AnsiDiff.diff(previous, current, false);

        assertTrue(ansi.contains("ab"));
        assertFalse(ansi.contains("中"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
