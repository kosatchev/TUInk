package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DisplayTextTest {
    private static final String COMBINING_E = "e\u0301";
    private static final String FAMILY = "👨‍👩‍👧‍👦";
    private static final String FLAG = "🇷🇺";
    private static final String MODIFIED_EMOJI = "👍🏽";

    @Test
    void segmentsExtendedGraphemeClustersAtUtf16Boundaries() {
        String text = "A" + COMBINING_E + FAMILY + FLAG + MODIFIED_EMOJI + "中";

        List<DisplayText.Grapheme> graphemes = DisplayText.graphemes(text);

        assertEquals(
            List.of("A", COMBINING_E, FAMILY, FLAG, MODIFIED_EMOJI, "中"),
            graphemes.stream().map(DisplayText.Grapheme::text).toList()
        );
        assertEquals(List.of(0, 1, 3, 14, 18, 22), graphemes.stream().map(DisplayText.Grapheme::start).toList());
        assertEquals(List.of(1, 3, 14, 18, 22, 23), graphemes.stream().map(DisplayText.Grapheme::end).toList());
    }

    @Test
    void floorsAndCeilsOffsetsInsideCombiningAndEmojiClusters() {
        String text = "A" + COMBINING_E + FAMILY + "B";

        assertEquals(1, DisplayText.floorBoundary(text, 2));
        assertEquals(3, DisplayText.ceilBoundary(text, 2));
        assertEquals(1, DisplayText.previousBoundary(text, 2));
        assertEquals(3, DisplayText.nextBoundary(text, 2));

        assertEquals(3, DisplayText.floorBoundary(text, 8));
        assertEquals(14, DisplayText.ceilBoundary(text, 8));
        assertEquals(3, DisplayText.previousBoundary(text, 8));
        assertEquals(14, DisplayText.nextBoundary(text, 8));

        assertTrue(DisplayText.isBoundary(text, 3));
        assertFalse(DisplayText.isBoundary(text, 8));
        assertEquals(0, DisplayText.floorBoundary(text, -10));
        assertEquals(text.length(), DisplayText.ceilBoundary(text, text.length() + 10));
        assertTrue(DisplayText.isBoundary(text, -10));
        assertTrue(DisplayText.isBoundary(text, text.length() + 10));
    }

    @Test
    void calculatesTerminalCellWidthsForTextAndEmojiClusters() {
        assertEquals(1, DisplayText.graphemeWidth("A"));
        assertEquals(1, DisplayText.graphemeWidth(COMBINING_E));
        assertEquals(2, DisplayText.graphemeWidth("中"));
        assertEquals(2, DisplayText.graphemeWidth(FAMILY));
        assertEquals(2, DisplayText.graphemeWidth(FLAG));
        assertEquals(2, DisplayText.graphemeWidth(MODIFIED_EMOJI));
        assertEquals(0, DisplayText.graphemeWidth("\u0301"));
        assertEquals(0, DisplayText.graphemeWidth("\u200D"));

        assertEquals(
            List.of(1, 1, 2, 2, 2, 2),
            DisplayText.graphemes("A" + COMBINING_E + FAMILY + FLAG + MODIFIED_EMOJI + "中")
                .stream()
                .map(DisplayText.Grapheme::width)
                .toList()
        );
    }

    @Test
    void sanitizesC0C1DelUnpairedSurrogatesAndStandaloneZeroWidthClusters() {
        assertEquals("␀", DisplayText.sanitizeGrapheme("\u0000"));
        assertEquals("␉", DisplayText.sanitizeGrapheme("\t"));
        assertEquals("␛", DisplayText.sanitizeGrapheme("\u001B"));
        assertEquals("␡", DisplayText.sanitizeGrapheme("\u007F"));
        assertEquals("�", DisplayText.sanitizeGrapheme("\u0085"));
        assertEquals("�", DisplayText.sanitizeGrapheme("\u009B"));
        assertEquals("�", DisplayText.sanitizeGrapheme("\uD83D"));
        assertEquals("�", DisplayText.sanitizeGrapheme("\uDC4D"));
        assertEquals("◌\u0301", DisplayText.sanitizeGrapheme("\u0301"));
        assertEquals("�", DisplayText.sanitizeGrapheme("\u200D"));
        assertEquals("�", DisplayText.sanitizeGrapheme("\uFE0F"));
        assertEquals("�", DisplayText.sanitizeGrapheme("\uD83C\uDFFB"));
        assertEquals(FAMILY, DisplayText.sanitizeGrapheme(FAMILY));

        assertTrue(DisplayText.containsUnsafeControl("safe\u001B[31m"));
        assertTrue(DisplayText.containsUnsafeControl("safe\u009B31m"));
        assertFalse(DisplayText.containsUnsafeControl("safe " + FAMILY));
    }

    @Test
    void mapsUtf16OffsetsToVisualColumnsUsingAbsoluteTabStops() {
        String text = "a\t中" + COMBINING_E;

        assertEquals(0, DisplayText.visualColumnAt(text, 0, 4));
        assertEquals(1, DisplayText.visualColumnAt(text, 1, 4));
        assertEquals(4, DisplayText.visualColumnAt(text, 2, 4));
        assertEquals(6, DisplayText.visualColumnAt(text, 3, 4));
        assertEquals(6, DisplayText.visualColumnAt(text, 4, 4));
        assertEquals(7, DisplayText.visualColumnAt(text, text.length(), 4));
        assertEquals(7, DisplayText.visualWidth(text, 4));

        assertEquals(1, DisplayText.offsetForVisualColumn(text, 2, 4, DisplayText.Bias.FLOOR));
        assertEquals(2, DisplayText.offsetForVisualColumn(text, 2, 4, DisplayText.Bias.CEIL));
        assertEquals(1, DisplayText.offsetForVisualColumn(text, 2, 4, DisplayText.Bias.NEAREST));
        assertEquals(2, DisplayText.offsetForVisualColumn(text, 3, 4, DisplayText.Bias.NEAREST));
        assertEquals(2, DisplayText.offsetForVisualColumn(text, 5, 4, DisplayText.Bias.FLOOR));
        assertEquals(3, DisplayText.offsetForVisualColumn(text, 5, 4, DisplayText.Bias.CEIL));
        assertEquals(2, DisplayText.offsetForVisualColumn(text, 5, 4, DisplayText.Bias.NEAREST));
    }

    @Test
    void metricsReuseOneUnicodeLayoutForBoundaryAndVisualQueries() {
        String text = "A\t" + FAMILY + "中B";
        DisplayText.Metrics metrics = DisplayText.metrics(text, 4);

        assertAll(
            () -> assertEquals(5, metrics.graphemeCount()),
            () -> assertEquals(2, metrics.floorBoundary(7)),
            () -> assertEquals(13, metrics.ceilBoundary(7)),
            () -> assertEquals(4, metrics.visualColumnAt(7)),
            () -> assertEquals(9, metrics.visualWidth()),
            () -> assertEquals(1, metrics.offsetForVisualColumn(2, DisplayText.Bias.FLOOR)),
            () -> assertEquals(2, metrics.offsetForVisualColumn(2, DisplayText.Bias.CEIL)),
            () -> assertEquals(1, metrics.offsetForVisualColumn(2, DisplayText.Bias.NEAREST)),
            () -> assertEquals(2, metrics.offsetForVisualColumn(5, DisplayText.Bias.FLOOR)),
            () -> assertEquals(13, metrics.offsetForVisualColumn(5, DisplayText.Bias.CEIL)),
            () -> assertEquals(text.length(), metrics.offsetForVisualColumn(Integer.MAX_VALUE, DisplayText.Bias.CEIL))
        );
    }

    @Test
    void asciiMetricsKeepDirectBoundariesAndAbsoluteTabStops() {
        DisplayText.Metrics plain = DisplayText.metrics("abcdef", 4);
        DisplayText.Metrics tabbed = DisplayText.metrics("a\tb", 4);

        assertAll(
            () -> assertEquals(3, plain.previousBoundary(4)),
            () -> assertEquals(5, plain.nextBoundary(4)),
            () -> assertEquals(4, plain.visualColumnAt(4)),
            () -> assertEquals(4, tabbed.visualColumnAt(2)),
            () -> assertEquals(5, tabbed.visualWidth()),
            () -> assertEquals(1, tabbed.offsetForVisualColumn(2, DisplayText.Bias.FLOOR)),
            () -> assertEquals(2, tabbed.offsetForVisualColumn(2, DisplayText.Bias.CEIL))
        );
    }

    @Test
    void wrapStartsNeverSplitGraphemeClustersAndKeepTabStopsAbsolute() {
        String graphemeText = "a" + FAMILY + "bc";

        assertEquals(List.of(0, 1, 12), ViewportMetrics.wrapStarts(graphemeText, 2, false, 4));
        assertTrue(
            ViewportMetrics.wrapStarts(graphemeText, 2, false, 4)
                .stream()
                .allMatch(offset -> DisplayText.isBoundary(graphemeText, offset))
        );

        String tabbedText = "\t中ab";
        assertEquals(List.of(0, 1), ViewportMetrics.wrapStarts(tabbedText, 4, false, 4));
        assertEquals(4, DisplayText.visualColumnAt(tabbedText, 1, 4));
        assertEquals(8, DisplayText.visualWidth(tabbedText, 4));
    }
}
