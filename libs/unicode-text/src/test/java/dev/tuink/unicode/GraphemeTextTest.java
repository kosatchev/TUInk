package dev.tuink.unicode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphemeTextTest {
    private static final String COMBINING_E = "e\u0301";
    private static final String FAMILY = "👨‍👩‍👧‍👦";
    private static final String FLAG = "🇷🇺";
    private static final String MODIFIED_EMOJI = "👍🏽";

    @Test
    void frozenSegmentationCorpusPreservesUtf16BoundariesAndMetadata() {
        List<CorpusCase> corpus = List.of(
            new CorpusCase("null", null, true, List.of(0), List.of(), List.of()),
            new CorpusCase("ascii", "A\t ", true, List.of(0, 1, 2, 3),
                List.of(false, true, false), List.of(false, true, true)),
            new CorpusCase("crlf", "\r\n", false, List.of(0, 2),
                List.of(false), List.of(true)),
            new CorpusCase(
                "mixed clusters",
                "A" + COMBINING_E + FAMILY + FLAG + MODIFIED_EMOJI + "中",
                false,
                List.of(0, 1, 3, 14, 18, 22, 23),
                List.of(false, false, false, false, false, false),
                List.of(false, false, false, false, false, false)
            ),
            new CorpusCase("keycap", "1\uFE0F\u20E3", false, List.of(0, 3),
                List.of(false), List.of(false)),
            new CorpusCase("emoji variation", "\u00A9\uFE0F", false, List.of(0, 2),
                List.of(false), List.of(false)),
            new CorpusCase("standalone combining mark", "\u0301", false, List.of(0, 1),
                List.of(false), List.of(false)),
            new CorpusCase("standalone joiner", "\u200D", false, List.of(0, 1),
                List.of(false), List.of(false)),
            new CorpusCase("hangul jamo", "\u1100\u1161", false, List.of(0, 2),
                List.of(false), List.of(false)),
            new CorpusCase("indic spacing mark", "\u0915\u093F", false, List.of(0, 2),
                List.of(false), List.of(false)),
            new CorpusCase("indic conjunct", "\u0915\u094D\u0937", false, List.of(0, 2, 3),
                List.of(false, false), List.of(false, false)),
            new CorpusCase("unsafe ascii control", "\u001B", true, List.of(0, 1),
                List.of(false), List.of(false)),
            new CorpusCase("unpaired high surrogate", "\uD83D", false, List.of(0, 1),
                List.of(false), List.of(false)),
            new CorpusCase("unpaired low surrogate", "\uDC4D", false, List.of(0, 1),
                List.of(false), List.of(false)),
            new CorpusCase("three regional indicators", FLAG + "🇺", false, List.of(0, 4, 6),
                List.of(false, false), List.of(false, false))
        );

        for (CorpusCase sample : corpus) {
            GraphemeText layout = GraphemeText.of(sample.text());

            assertEquals(sample.text() == null ? "" : sample.text(), layout.text(), sample.name());
            assertEquals(sample.simpleAscii(), layout.simpleAscii(), sample.name());
            assertEquals(sample.boundaries().size() - 1, layout.graphemeCount(), sample.name());
            assertEquals(sample.boundaries(), boundaries(layout), sample.name());
            assertEquals(sample.tabs(), flags(layout, true), sample.name());
            assertEquals(sample.whitespace(), flags(layout, false), sample.name());
            assertEquals(
                sample.simpleAscii() ? 1 : sample.boundaries().size() + layout.graphemeCount(),
                layout.storageWeight(),
                sample.name()
            );
        }
    }

    @Test
    void boundaryNavigationClampsOffsetsAndNeverSplitsExtendedClusters() {
        String text = "A" + COMBINING_E + FAMILY + "B";
        GraphemeText layout = GraphemeText.of(text);

        assertEquals(1, layout.floorBoundary(2));
        assertEquals(3, layout.ceilBoundary(2));
        assertEquals(1, layout.previousBoundary(2));
        assertEquals(3, layout.nextBoundary(2));

        assertEquals(3, layout.floorBoundary(8));
        assertEquals(14, layout.ceilBoundary(8));
        assertEquals(3, layout.previousBoundary(8));
        assertEquals(14, layout.nextBoundary(8));

        assertTrue(layout.isBoundary(3));
        assertFalse(layout.isBoundary(8));
        assertEquals(0, layout.floorBoundary(-10));
        assertEquals(0, layout.previousBoundary(-10));
        assertEquals(text.length(), layout.ceilBoundary(text.length() + 10));
        assertEquals(text.length(), layout.nextBoundary(text.length() + 10));
        assertFalse(layout.isBoundary(-10));
        assertFalse(layout.isBoundary(text.length() + 10));
    }

    @Test
    void staticBoundaryOperationsUseTheSamePureSegmentation() {
        String text = "A" + FAMILY + "B";

        assertEquals(1, GraphemeText.floorBoundary(text, 5));
        assertEquals(12, GraphemeText.ceilBoundary(text, 5));
        assertEquals(1, GraphemeText.previousBoundary(text, 5));
        assertEquals(12, GraphemeText.nextBoundary(text, 5));
        assertFalse(GraphemeText.isBoundary(text, 5));
        assertFalse(GraphemeText.isBoundary(null, 10));
    }

    @Test
    void asciiFastPathUsesDirectOffsetsWithoutBoundaryArrays() {
        GraphemeText layout = GraphemeText.of("ab\tc");

        assertTrue(layout.simpleAscii());
        assertEquals(4, layout.graphemeCount());
        assertEquals(2, layout.start(2));
        assertEquals(3, layout.end(2));
        assertTrue(layout.isTab(2));
        assertTrue(layout.isWhitespace(2));
        assertEquals(1, layout.storageWeight());
        assertEquals(1, layout.previousBoundary(2));
        assertEquals(3, layout.nextBoundary(2));
    }

    @Test
    void crAndLfAlwaysUseUnicodeSegmentation() {
        GraphemeText crlf = GraphemeText.of("\r\n");
        GraphemeText separate = GraphemeText.of("\rX\n");

        assertFalse(crlf.simpleAscii());
        assertEquals(1, crlf.graphemeCount());
        assertEquals(0, crlf.start(0));
        assertEquals(2, crlf.end(0));
        assertTrue(crlf.isWhitespace(0));

        assertFalse(separate.simpleAscii());
        assertEquals(List.of(0, 1, 2, 3), boundaries(separate));
    }

    @Test
    void graphemeAccessRejectsIndicesOutsideTheLayout() {
        GraphemeText empty = GraphemeText.of(null);
        GraphemeText one = GraphemeText.of("x");

        assertThrows(IndexOutOfBoundsException.class, () -> empty.start(0));
        assertThrows(IndexOutOfBoundsException.class, () -> empty.end(0));
        assertThrows(IndexOutOfBoundsException.class, () -> empty.isTab(0));
        assertThrows(IndexOutOfBoundsException.class, () -> empty.isWhitespace(0));
        assertThrows(IndexOutOfBoundsException.class, () -> one.start(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> one.end(1));
    }

    private static List<Integer> boundaries(GraphemeText layout) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        for (int index = 0; index < layout.graphemeCount(); index++) {
            assertEquals(boundaries.get(index).intValue(), layout.start(index));
            boundaries.add(layout.end(index));
        }
        return List.copyOf(boundaries);
    }

    private static List<Boolean> flags(GraphemeText layout, boolean tabs) {
        List<Boolean> flags = new ArrayList<>();
        for (int index = 0; index < layout.graphemeCount(); index++) {
            flags.add(tabs ? layout.isTab(index) : layout.isWhitespace(index));
        }
        return List.copyOf(flags);
    }

    private record CorpusCase(
        String name,
        String text,
        boolean simpleAscii,
        List<Integer> boundaries,
        List<Boolean> tabs,
        List<Boolean> whitespace
    ) {
    }

}
