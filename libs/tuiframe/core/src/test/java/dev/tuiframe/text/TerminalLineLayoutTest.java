package dev.tuiframe.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.RandomAccess;
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
    void summaryMatchesMaterializedRowsForUnicodeTabsWordsAndWidthBoundaries() {
        for (String text : List.of("", "abcdef", "ab cde", "a\t中b", "\t中ab", "中", "中a",
            FAMILY, FAMILY + "a", "😀e\u0301x", "a\u0301 text  ", " \t ")) {
            for (int tabWidth : new int[]{0, 1, 2, 4, 8}) {
                TerminalText.Metrics metrics = TerminalText.metrics(text, tabWidth);
                for (int width : new int[]{-1, 0, 1, 2, 3, 4, 7, 80}) {
                    for (boolean words : new boolean[]{false, true}) {
                        List<Integer> starts = TerminalLineLayout.wrapStarts(metrics, width, words);
                        TerminalLineLayout.WrapSummary summary = TerminalLineLayout.wrapSummary(metrics, width, words);
                        String context = "text=" + text + ", width=" + width + ", words=" + words + ", tabs=" + tabWidth;

                        assertEquals(starts.size(), summary.rowCount(), context);
                        assertEquals(starts.getLast().intValue(), summary.lastStartOffset(), context);
                    }
                }
            }
        }
    }

    @Test
    void summaryCountsManyRowsFromASmallInputWithoutRequestingTheirOffsets() {
        String text = "a".repeat(250_000);
        TerminalLineLayout.WrapSummary summary = TerminalLineLayout.wrapSummary(TerminalText.metrics(text, 4), 1, false);

        assertEquals(250_000, summary.rowCount());
        assertEquals(249_999, summary.lastStartOffset());
    }

    @Test
    void metricsAreRequired() {
        assertThrows(NullPointerException.class, () -> TerminalLineLayout.wrapStarts(null, 10, false));
        assertThrows(NullPointerException.class, () -> TerminalLineLayout.wrapSummary(null, 10, false));
    }

    @Test
    void sparseRowsMatchThePreviousEagerLayoutForWordsTabsAndUnicode() {
        for (String text : List.of("a".repeat(20_000), "ab \t中" + FAMILY + " e\u0301  x\t".repeat(900),
            ("ab \t中" + FAMILY + " e\u0301  x\t").repeat(900))) {
            for (int width : new int[] {1, 2, 3, 7}) {
                for (boolean words : new boolean[] {false, true}) {
                    TerminalText.Metrics metrics = TerminalText.metrics(text, 4);
                    List<Integer> expected = eagerReference(metrics, width, words);
                    List<Integer> actual = TerminalLineLayout.wrapStarts(metrics, width, words);
                    assertEquals(expected, actual, "width=" + width + ", words=" + words);
                    assertTrue(actual instanceof RandomAccess);
                    assertThrows(UnsupportedOperationException.class, () -> actual.set(0, 1));
                    Random random = new Random(804021);
                    for (int sample = 0; sample < 100; sample++) {
                        int offset = random.nextInt(text.length() + 1);
                        assertEquals(Collections.binarySearch(expected, offset), Collections.binarySearch(actual, offset));
                    }
                }
            }
        }
    }

    @Test
    void sparseRetainedWeightAndRandomAccessStayBoundedAfterReadingDistantPages() {
        TerminalText.Metrics metrics = TerminalText.metrics("a".repeat(1_000_000), 4);
        List<Integer> starts = TerminalLineLayout.wrapStarts(metrics, 1, false);
        assertEquals(1_000_000, starts.size());
        assertTrue(starts instanceof RandomAccess);
        int weight = TerminalLineLayout.retainedWeight(starts);
        assertTrue(weight <= metrics.storageWeight() + 4096 + 256);
        Random random = new Random(101805);
        for (int sample = 0; sample < 1000; sample++) {
            int row = random.nextInt(starts.size());
            assertEquals(row, starts.get(row).intValue());
            assertEquals(row, Collections.binarySearch(starts, row));
        }
        assertEquals(weight, TerminalLineLayout.retainedWeight(starts));
        assertEquals(999_999, starts.getLast().intValue());
        assertThrows(IndexOutOfBoundsException.class, () -> starts.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> starts.get(starts.size()));
    }

    @Test
    void reservingTheInsertionRowDoesNotMaterializeSparseOffsets() {
        TerminalText.Metrics metrics = TerminalText.metrics("a".repeat(1_000_000), 4);
        List<Integer> starts = TerminalLineLayout.wrapStarts(metrics, 1, false, true);
        assertEquals(1_000_001, starts.size());
        assertEquals(1_000_000, starts.getLast().intValue());
        assertEquals(999_999, starts.get(starts.size() - 2).intValue());
        assertEquals(1_000_000, Collections.binarySearch(starts, 1_000_000));
        assertTrue(TerminalLineLayout.retainedWeight(starts) <= metrics.storageWeight() + 4096 + 256 + 1);
        assertThrows(UnsupportedOperationException.class, () -> starts.add(1));

        assertEquals(List.of(0, 4), TerminalLineLayout.wrapStarts(TerminalText.metrics("abcd", 4), 4, false, true));
        assertEquals(List.of(0, 4), TerminalLineLayout.wrapStarts(TerminalText.metrics("abcde", 4), 4, false, true));
        assertEquals(List.of(0, 1), TerminalLineLayout.wrapStarts(TerminalText.metrics("中", 4), 1, false, true));
        assertEquals(List.of(0), TerminalLineLayout.wrapStarts(TerminalText.metrics("", 4), 4, false, true));
        assertEquals(List.of(0), TerminalLineLayout.wrapStarts(TerminalText.metrics("abcdef", 4), 0, false, true));
        assertEquals(2, TerminalLineLayout.retainedWeight(
            TerminalLineLayout.wrapStarts(TerminalText.metrics("中ab", 4), 2, false)));
    }

    /** The original eager scanner is the compatibility oracle for sparse lookup. */
    private static List<Integer> eagerReference(TerminalText.Metrics metrics, int width, boolean words) {
        if (metrics.text().isEmpty() || width <= 0) return List.of(0);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int segmentStart = 0;
        int startVisual = 0;
        int visual = 0;
        int whitespaceEnd = -1;
        int index = 0;
        while (index < metrics.graphemeCount()) {
            int advance = metrics.visualColumnAtBoundary(index + 1) - metrics.visualColumnAtBoundary(index);
            if (visual + advance - startVisual > width && index > segmentStart) {
                int boundary = words && whitespaceEnd > segmentStart ? whitespaceEnd : index;
                starts.add(metrics.start(boundary));
                segmentStart = boundary;
                startVisual = metrics.visualColumnAtBoundary(boundary);
                visual = startVisual;
                whitespaceEnd = -1;
                index = boundary;
                continue;
            }
            visual += advance;
            index++;
            if (metrics.isWhitespace(index - 1)) whitespaceEnd = index;
            if (visual - startVisual > width && index == segmentStart + 1) {
                if (index < metrics.graphemeCount()) starts.add(metrics.start(index));
                segmentStart = index;
                startVisual = visual;
                whitespaceEnd = -1;
            }
        }
        return starts;
    }
}
