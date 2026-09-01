package dev.tuink.terminal.text;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalTextTest {
    private static final String COMBINING_E = "e\u0301";
    private static final String FAMILY = "👨‍👩‍👧‍👦";
    private static final String FLAG = "🇷🇺";
    private static final String MODIFIED_EMOJI = "👍🏽";

    @Test
    void segmentsExtendedGraphemeClustersAtUtf16Boundaries() {
        String text = "A" + COMBINING_E + FAMILY + FLAG + MODIFIED_EMOJI + "中";

        List<TerminalText.Grapheme> graphemes = TerminalText.graphemes(text);

        assertEquals(
            List.of("A", COMBINING_E, FAMILY, FLAG, MODIFIED_EMOJI, "中"),
            graphemes.stream().map(TerminalText.Grapheme::text).toList()
        );
        assertEquals(List.of(0, 1, 3, 14, 18, 22), graphemes.stream().map(TerminalText.Grapheme::start).toList());
        assertEquals(List.of(1, 3, 14, 18, 22, 23), graphemes.stream().map(TerminalText.Grapheme::end).toList());
    }

    @Test
    void floorsAndCeilsOffsetsInsideCombiningAndEmojiClusters() {
        String text = "A" + COMBINING_E + FAMILY + "B";

        assertEquals(1, TerminalText.floorBoundary(text, 2));
        assertEquals(3, TerminalText.ceilBoundary(text, 2));
        assertEquals(1, TerminalText.previousBoundary(text, 2));
        assertEquals(3, TerminalText.nextBoundary(text, 2));

        assertEquals(3, TerminalText.floorBoundary(text, 8));
        assertEquals(14, TerminalText.ceilBoundary(text, 8));
        assertEquals(3, TerminalText.previousBoundary(text, 8));
        assertEquals(14, TerminalText.nextBoundary(text, 8));

        assertTrue(TerminalText.isBoundary(text, 3));
        assertFalse(TerminalText.isBoundary(text, 8));
        assertEquals(0, TerminalText.floorBoundary(text, -10));
        assertEquals(text.length(), TerminalText.ceilBoundary(text, text.length() + 10));
        assertTrue(TerminalText.isBoundary(text, -10));
        assertTrue(TerminalText.isBoundary(text, text.length() + 10));
    }

    @Test
    void calculatesTerminalCellWidthsForTextAndEmojiClusters() {
        assertEquals(1, TerminalText.graphemeWidth("A"));
        assertEquals(1, TerminalText.graphemeWidth(COMBINING_E));
        assertEquals(2, TerminalText.graphemeWidth("中"));
        assertEquals(2, TerminalText.graphemeWidth(FAMILY));
        assertEquals(2, TerminalText.graphemeWidth(FLAG));
        assertEquals(2, TerminalText.graphemeWidth(MODIFIED_EMOJI));
        assertEquals(0, TerminalText.graphemeWidth("\u0301"));
        assertEquals(0, TerminalText.graphemeWidth("\u200D"));

        assertEquals(
            List.of(1, 1, 2, 2, 2, 2),
            TerminalText.graphemes("A" + COMBINING_E + FAMILY + FLAG + MODIFIED_EMOJI + "中")
                .stream()
                .map(TerminalText.Grapheme::width)
                .toList()
        );
    }

    @Test
    void recognizesExactlyOneExtendedGraphemeWithoutSplittingEmoji() {
        assertTrue(TerminalText.isSingleGrapheme("A"));
        assertTrue(TerminalText.isSingleGrapheme("中"));
        assertTrue(TerminalText.isSingleGrapheme(COMBINING_E));
        assertTrue(TerminalText.isSingleGrapheme(FAMILY));
        assertFalse(TerminalText.isSingleGrapheme("ab"));
        assertFalse(TerminalText.isSingleGrapheme(FAMILY + "x"));
        assertFalse(TerminalText.isSingleGrapheme(""));
        assertFalse(TerminalText.isSingleGrapheme(null));
    }

    @Test
    void sanitizesC0C1DelUnpairedSurrogatesAndStandaloneZeroWidthClusters() {
        assertEquals("␀", TerminalText.sanitizeGrapheme("\u0000"));
        assertEquals("␉", TerminalText.sanitizeGrapheme("\t"));
        assertEquals("␛", TerminalText.sanitizeGrapheme("\u001B"));
        assertEquals("␡", TerminalText.sanitizeGrapheme("\u007F"));
        assertEquals("�", TerminalText.sanitizeGrapheme("\u0085"));
        assertEquals("�", TerminalText.sanitizeGrapheme("\u009B"));
        assertEquals("�", TerminalText.sanitizeGrapheme("\uD83D"));
        assertEquals("�", TerminalText.sanitizeGrapheme("\uDC4D"));
        assertEquals("◌\u0301", TerminalText.sanitizeGrapheme("\u0301"));
        assertEquals("�", TerminalText.sanitizeGrapheme("\u200D"));
        assertEquals("�", TerminalText.sanitizeGrapheme("\uFE0F"));
        assertEquals("�", TerminalText.sanitizeGrapheme("\uD83C\uDFFB"));
        assertEquals(FAMILY, TerminalText.sanitizeGrapheme(FAMILY));

        assertTrue(TerminalText.containsUnsafeControl("safe\u001B[31m"));
        assertTrue(TerminalText.containsUnsafeControl("safe\u009B31m"));
        assertFalse(TerminalText.containsUnsafeControl("safe " + FAMILY));
    }

    @Test
    void mapsUtf16OffsetsToVisualColumnsUsingAbsoluteTabStops() {
        String text = "a\t中" + COMBINING_E;

        assertEquals(0, TerminalText.visualColumnAt(text, 0, 4));
        assertEquals(1, TerminalText.visualColumnAt(text, 1, 4));
        assertEquals(4, TerminalText.visualColumnAt(text, 2, 4));
        assertEquals(6, TerminalText.visualColumnAt(text, 3, 4));
        assertEquals(6, TerminalText.visualColumnAt(text, 4, 4));
        assertEquals(7, TerminalText.visualColumnAt(text, text.length(), 4));
        assertEquals(7, TerminalText.visualWidth(text, 4));

        assertEquals(1, TerminalText.offsetForVisualColumn(text, 2, 4, TerminalText.Bias.FLOOR));
        assertEquals(2, TerminalText.offsetForVisualColumn(text, 2, 4, TerminalText.Bias.CEIL));
        assertEquals(1, TerminalText.offsetForVisualColumn(text, 2, 4, TerminalText.Bias.NEAREST));
        assertEquals(2, TerminalText.offsetForVisualColumn(text, 3, 4, TerminalText.Bias.NEAREST));
        assertEquals(2, TerminalText.offsetForVisualColumn(text, 5, 4, TerminalText.Bias.FLOOR));
        assertEquals(3, TerminalText.offsetForVisualColumn(text, 5, 4, TerminalText.Bias.CEIL));
        assertEquals(2, TerminalText.offsetForVisualColumn(text, 5, 4, TerminalText.Bias.NEAREST));
    }

    @Test
    void metricsReuseOneUnicodeLayoutForBoundaryAndVisualQueries() {
        String text = "A\t" + FAMILY + "中B";
        TerminalText.Metrics metrics = TerminalText.metrics(text, 4);

        assertAll(
            () -> assertEquals(5, metrics.graphemeCount()),
            () -> assertEquals(2, metrics.floorBoundary(7)),
            () -> assertEquals(13, metrics.ceilBoundary(7)),
            () -> assertEquals(4, metrics.visualColumnAt(7)),
            () -> assertEquals(9, metrics.visualWidth()),
            () -> assertEquals(1, metrics.offsetForVisualColumn(2, TerminalText.Bias.FLOOR)),
            () -> assertEquals(2, metrics.offsetForVisualColumn(2, TerminalText.Bias.CEIL)),
            () -> assertEquals(1, metrics.offsetForVisualColumn(2, TerminalText.Bias.NEAREST)),
            () -> assertEquals(2, metrics.offsetForVisualColumn(5, TerminalText.Bias.FLOOR)),
            () -> assertEquals(13, metrics.offsetForVisualColumn(5, TerminalText.Bias.CEIL)),
            () -> assertEquals(text.length(), metrics.offsetForVisualColumn(Integer.MAX_VALUE, TerminalText.Bias.CEIL))
        );
    }

    @Test
    void asciiMetricsKeepDirectBoundariesAndAbsoluteTabStops() {
        TerminalText.Metrics plain = TerminalText.metrics("abcdef", 4);
        TerminalText.Metrics tabbed = TerminalText.metrics("a\tb", 4);

        assertAll(
            () -> assertEquals(3, plain.previousBoundary(4)),
            () -> assertEquals(5, plain.nextBoundary(4)),
            () -> assertEquals(4, plain.visualColumnAt(4)),
            () -> assertEquals(4, tabbed.visualColumnAt(2)),
            () -> assertEquals(5, tabbed.visualWidth()),
            () -> assertEquals(1, tabbed.offsetForVisualColumn(2, TerminalText.Bias.FLOOR)),
            () -> assertEquals(2, tabbed.offsetForVisualColumn(2, TerminalText.Bias.CEIL)),
            () -> assertEquals(1, plain.storageWeight()),
            () -> assertEquals(4, tabbed.storageWeight()),
            () -> assertEquals(6, TerminalText.metrics("中", 4).storageWeight())
        );
    }

    @Test
    void visualPrefixStopsAfterTheFirstGraphemeCrossingTheViewport() {
        assertEquals("abc", TerminalText.prefixForVisualWidth("abcdef", 3, 4));
        assertEquals("a\t", TerminalText.prefixForVisualWidth("a\tb-tail", 3, 4));
        assertEquals("中", TerminalText.prefixForVisualWidth("中-tail", 1, 4));
        assertEquals(
            "e\u0301中",
            TerminalText.prefixForVisualWidth("e\u0301中-tail", 2, 4)
        );
        assertEquals("", TerminalText.prefixForVisualWidth("value", 0, 4));
        assertEquals(
            "中\t中",
            TerminalText.prefixForVisualWidth(
                "中\t中tail",
                Integer.MAX_VALUE,
                Integer.MAX_VALUE - 1
            )
        );
    }

}
