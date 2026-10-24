package dev.tuiframe.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TerminalTextIncrementalTest {
    @Test
    void incrementalSegmentationAndAbsoluteTabStopsMatchFreshMetrics() {
        Random random = new Random(732_934L);
        List<String> insertions = List.of("a", "я", "中", "\t", " ", "e\u0301", "\u0301", "\u200D",
            "👩", "💻", "🇦", "🇧", "क्", "\u0600", "\r\n", "");
        for (int tabWidth : List.of(1, 3, 4, 8)) {
            String text = "a\t🇦🇧🇨🇩 é👩‍💻\tкакой-то хвост";
            TerminalText.Metrics actual = TerminalText.metrics(new Sequence(text, new AtomicInteger()), tabWidth);
            for (int attempt = 0; attempt < 200; attempt++) {
                int start = codePointBoundary(text, random.nextInt(text.length() + 1));
                int end = codePointBoundary(text, start + random.nextInt(text.length() - start + 1));
                String inserted = insertions.get(random.nextInt(insertions.size()));
                String next = text.substring(0, start) + inserted + text.substring(end);
                Sequence sequence = new Sequence(next, new AtomicInteger());
                actual = actual.updated(sequence, start, end - start, inserted.length());
                TerminalText.Metrics expected = TerminalText.metrics(next, tabWidth);
                assertSame(sequence, actual.sequence());
                assertEquals(expected.graphemeCount(), actual.graphemeCount(), "attempt " + attempt);
                assertEquals(expected.visualWidth(), actual.visualWidth(), "width " + attempt);
                for (int index = 0; index < expected.graphemeCount(); index++) {
                    assertEquals(expected.start(index), actual.start(index));
                    assertEquals(expected.end(index), actual.end(index));
                    assertEquals(expected.width(index), actual.width(index));
                    assertEquals(expected.isTab(index), actual.isTab(index));
                    assertEquals(expected.isWhitespace(index), actual.isWhitespace(index));
                    assertEquals(expected.visualColumnAtBoundary(index), actual.visualColumnAtBoundary(index));
                }
                for (int column = 0; column <= expected.visualWidth(); column++) {
                    for (TerminalText.Bias bias : TerminalText.Bias.values()) {
                        assertEquals(expected.offsetForVisualColumn(column, bias), actual.offsetForVisualColumn(column, bias),
                            "column " + column + ", attempt " + attempt);
                    }
                }
                text = next;
            }
        }
    }

    @Test
    void localUnicodeEditsAndVirtualWrapRowsDoNotRescanOrMaterializeTheLine() {
        for (String character : List.of("a", "я", "😀")) {
            String text = character.repeat(100_000);
            AtomicInteger reads = new AtomicInteger();
            TerminalText.Metrics metrics = TerminalText.metrics(new Sequence(text, reads), 4);
            int start = 50_000 * character.length();
            String next = text.substring(0, start) + character + text.substring(start);
            reads.set(0);

            TerminalText.Metrics changed = metrics.updated(new Sequence(next, reads), start, 0, character.length());
            List<Integer> starts = TerminalLineLayout.wrapStarts(changed, 20, true, true);

            int cells = character.equals("😀") ? 2 : 1;
            int perRow = 20 / cells;
            assertEquals((100_001 - 1) / perRow + 1, starts.size());
            assertEquals(100_000 * character.length(), starts.get(starts.size() - 1));
            assertTrue(reads.get() < 400, character + " reads: " + reads);
            assertTrue(TerminalLineLayout.retainedWeight(starts) <= changed.storageWeight() + 8,
                "Virtual row offsets must add only a bounded index for " + character);
        }
    }

    @Test
    void versionedLayoutCacheUsesExactLineEditsWithoutRequestingAString() {
        AtomicInteger reads = new AtomicInteger();
        final class Source implements TerminalTextLayoutCache.LineSource {
            CharSequence sequence = new Sequence("я".repeat(100_000), reads);
            long revision;
            TerminalTextLayoutCache.TextEdit edit;
            @Override public long cacheIdentity() { return 1; }
            @Override public long revision() { return revision; }
            @Override public long contentEpoch() { return 0; }
            @Override public int lineCount() { return 1; }
            @Override public String line(int line) { throw new AssertionError("A String was requested"); }
            @Override public CharSequence lineSequence(int line) { return sequence; }
            @Override public long lineIdentity(int line) { return revision + 1; }
            @Override public TerminalTextLayoutCache.TextEdit textEdit(int line) { return edit; }
            @Override public List<TerminalTextLayoutCache.LineChange> changesSince(long revision) {
                return List.of(new TerminalTextLayoutCache.LineChange(0, 1, 1));
            }
        }
        Source source = new Source();
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source, true);
        assertEquals(100_000, cache.maxVisualLineWidth(4));
        assertEquals(5_001, cache.totalWrappedRows(20, true, 4));
        source.sequence = new Sequence("я".repeat(100_001), reads);
        source.edit = new TerminalTextLayoutCache.TextEdit(1, 50_000, 0, 1);
        source.revision++;
        reads.set(0);

        assertEquals(100_001, cache.maxVisualLineWidth(4));
        assertEquals(5_001, cache.totalWrappedRows(20, true, 4));
        assertEquals(100_000, cache.wrapStarts(0, 20, true, 4).get(5_000));
        assertTrue(reads.get() < 500, "The adapter must keep changed-range metric reuse: " + reads);
    }

    @Test
    void repeatedTypingCompactsMetricPiecesAndKeepsVirtualWrappingBounded() {
        AtomicInteger reads = new AtomicInteger();
        TerminalText.Metrics metrics = TerminalText.metrics(new UniformSequence(100_000, reads), 4);
        reads.set(0);
        for (int typed = 1; typed <= 5_000; typed++) {
            metrics = metrics.updated(new UniformSequence(100_000 + typed, reads), 50_000 + typed - 1, 0, 1);
        }
        assertEquals(105_000, metrics.visualWidth());
        assertEquals(70_000, metrics.offsetForVisualColumn(70_000, TerminalText.Bias.FLOOR));
        assertTrue(metrics.storageWeight() < 2_000, "Small metric blocks must coalesce: " + metrics.storageWeight());
        assertTrue(reads.get() < 500_000, "Compaction must not read unchanged characters: " + reads);
        assertEquals(5_251, TerminalLineLayout.wrapStarts(metrics, 20, true, true).size());
    }

    @Test
    void displaySanitizationCopiesOnlyTheBoundedGlyphWithoutSlicingItsSource() {
        String source = "я".repeat(100_000) + "é😀" + "я".repeat(100_000);
        AtomicInteger reads = new AtomicInteger();
        CharSequence sequence = new CharSequence() {
            @Override public int length() { return source.length(); }
            @Override public char charAt(int index) { reads.incrementAndGet(); return source.charAt(index); }
            @Override public CharSequence subSequence(int from, int to) {
                throw new AssertionError("Drawing one glyph must not slice its source storage blocks");
            }
            @Override public String toString() { throw new AssertionError("The source line was materialized"); }
        };

        assertEquals("é", TerminalText.sanitizeGrapheme(sequence, 100_000, 100_002));
        assertEquals("😀", TerminalText.sanitizeGrapheme(sequence, 100_002, 100_004));
        assertEquals(4, reads.get(), "Only the requested display payload may be copied");
    }

    @Test
    void aRetainedSliceLargerThanOneBlockBudgetsAllBackingMetricArrays() {
        String original = "中".repeat(100_000);
        TerminalText.Metrics metrics = TerminalText.metrics(original, 4);
        String retained = "中".repeat(257);

        TerminalText.Metrics changed = metrics.updated(retained, 0, original.length() - retained.length(), 0);

        assertEquals(257, changed.graphemeCount());
        assertEquals(514, changed.visualWidth());
        assertTrue(changed.storageWeight() >= metrics.storageWeight(),
            "A retained slice must not budget only its proportional share of dense arrays");
    }

    private static int codePointBoundary(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
            && Character.isLowSurrogate(text.charAt(offset)) ? offset - 1 : offset;
    }

    private record Sequence(String value, AtomicInteger reads) implements CharSequence {
        @Override public int length() { return value.length(); }
        @Override public char charAt(int index) { reads.incrementAndGet(); return value.charAt(index); }
        @Override public CharSequence subSequence(int from, int to) { return new Sequence(value.substring(from, to), reads); }
        @Override public String toString() { throw new AssertionError("A line was materialized"); }
    }

    private record UniformSequence(int length, AtomicInteger reads) implements CharSequence {
        @Override public char charAt(int index) {
            java.util.Objects.checkIndex(index, length);
            reads.incrementAndGet();
            return 'x';
        }
        @Override public CharSequence subSequence(int from, int to) {
            java.util.Objects.checkFromToIndex(from, to, length);
            return new UniformSequence(to - from, reads);
        }
        @Override public String toString() { throw new AssertionError("A line was materialized"); }
    }
}
