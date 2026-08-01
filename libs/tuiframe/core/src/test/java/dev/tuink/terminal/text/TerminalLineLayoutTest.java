package dev.tuink.terminal.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalLineLayoutTest {
    private static final String FAMILY = "👨‍👩‍👧‍👦";

    @Test
    void wrapsOnlyAtGraphemeBoundariesAndKeepsAbsoluteTabStops() {
        String graphemeText = "a" + FAMILY + "bc";

        assertEquals(
            List.of(0, 1, 12),
            TerminalLineLayout.wrapStarts(TerminalText.metrics(graphemeText, 4), 2, false)
        );
        assertEquals(
            List.of(0, 1),
            TerminalLineLayout.wrapStarts(TerminalText.metrics("\t中ab", 4), 4, false)
        );
    }

    @Test
    void wordWrappingUsesTheLastWhitespaceWithoutDroppingContent() {
        TerminalText.Metrics metrics = TerminalText.metrics("ab cde", 4);

        assertEquals(List.of(0, 4), TerminalLineLayout.wrapStarts(metrics, 4, false));
        assertEquals(List.of(0, 3), TerminalLineLayout.wrapStarts(metrics, 4, true));
    }

    @Test
    void oneOversizedGraphemeOccupiesItsOwnVisualRow() {
        assertEquals(
            List.of(0, 1),
            TerminalLineLayout.wrapStarts(TerminalText.metrics("中a", 4), 1, false)
        );
        assertEquals(
            List.of(0, FAMILY.length()),
            TerminalLineLayout.wrapStarts(TerminalText.metrics(FAMILY + "a", 4), 1, false)
        );
    }

    @Test
    void emptyTextAndNonPositiveWidthsHaveOneStartAndResultsAreImmutable() {
        assertEquals(
            List.of(0),
            TerminalLineLayout.wrapStarts(TerminalText.metrics("", 4), 10, false)
        );
        List<Integer> zeroWidth = TerminalLineLayout.wrapStarts(
            TerminalText.metrics("abcdef", 4),
            0,
            false
        );
        assertEquals(List.of(0), zeroWidth);
        assertEquals(
            List.of(0),
            TerminalLineLayout.wrapStarts(TerminalText.metrics("abcdef", 4), -10, true)
        );
        assertThrows(UnsupportedOperationException.class, () -> zeroWidth.add(1));
    }

    @Test
    void metricsAreRequired() {
        assertThrows(NullPointerException.class, () -> TerminalLineLayout.wrapStarts(null, 10, false));
    }
}
