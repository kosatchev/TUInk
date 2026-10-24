package dev.tuink.unicode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GraphemeTextIncrementalTest {
    @Test
    void immutableSequenceUpdatesMatchFullSegmentationAcrossUnicodeContextChanges() {
        Random random = new Random(283_923L);
        List<String> insertions = List.of("a", "я", "\t", "e\u0301", "\u0301", "\u200D", "👩", "💻",
            "🇦", "🇧", "क्", "\u0600", "\r\n", "");
        String text = "á🇦🇧🇨🇩👩‍💻क्‍क tail";
        GraphemeText actual = GraphemeText.of(new Sequence(text, new AtomicInteger()));
        for (int attempt = 0; attempt < 300; attempt++) {
            int start = codePointBoundary(text, random.nextInt(text.length() + 1));
            int end = codePointBoundary(text, start + random.nextInt(text.length() - start + 1));
            String inserted = insertions.get(random.nextInt(insertions.size()));
            String next = text.substring(0, start) + inserted + text.substring(end);
            Sequence sequence = new Sequence(next, new AtomicInteger());
            actual = actual.updated(sequence, start, end - start, inserted.length());
            GraphemeText expected = GraphemeText.of(next);
            assertSame(sequence, actual.sequence());
            assertEquals(expected.graphemeCount(), actual.graphemeCount(), "attempt " + attempt);
            for (int offset = 0; offset <= next.length(); offset++) {
                assertEquals(expected.floorBoundary(offset), actual.floorBoundary(offset), "floor " + attempt);
                assertEquals(expected.ceilBoundary(offset), actual.ceilBoundary(offset), "ceil " + attempt);
                assertEquals(expected.isBoundary(offset), actual.isBoundary(offset), "boundary " + attempt);
            }
            for (int index = 0; index < expected.graphemeCount(); index++) {
                assertEquals(expected.start(index), actual.start(index));
                assertEquals(expected.end(index), actual.end(index));
                assertEquals(expected.isWhitespace(index), actual.isWhitespace(index));
            }
            text = next;
        }
    }

    @Test
    void aLocalEditDoesNotReadTheUnchangedUnicodePrefixOrSuffix() {
        String text = "я".repeat(100_000) + "é" + "я".repeat(100_000);
        AtomicInteger reads = new AtomicInteger();
        GraphemeText metrics = GraphemeText.of(new Sequence(text, reads));
        int start = 100_002;
        String next = text.substring(0, start) + "x" + text.substring(start);
        reads.set(0);

        GraphemeText changed = metrics.updated(new Sequence(next, reads), start, 0, 1);

        assertTrue(reads.get() < 200, "Unchanged text must retain its boundary index: " + reads);
        assertEquals(metrics.graphemeCount() + 1, changed.graphemeCount());
        assertEquals(start + 1, changed.ceilBoundary(start + 1));
        assertEquals(next.length(), changed.ceilBoundary(next.length()));
    }

    @Test
    void longTypingSessionsCompactSmallIndexPiecesWithoutReadingTheWholeSource() {
        AtomicInteger reads = new AtomicInteger();
        GraphemeText metrics = GraphemeText.of(new UniformSequence(100_000, reads));
        reads.set(0);
        for (int typed = 1; typed <= 5_000; typed++) {
            metrics = metrics.updated(new UniformSequence(100_000 + typed, reads), 50_000 + typed - 1, 0, 1);
        }
        assertEquals(105_000, metrics.graphemeCount());
        assertEquals(70_000, metrics.floorBoundary(70_000));
        assertTrue(metrics.storageWeight() < 1_500, "Small blocks must coalesce: " + metrics.storageWeight());
        assertTrue(reads.get() < 500_000, "Index compaction must not read unchanged characters: " + reads);
    }

    @Test
    void aRetainedSliceLargerThanOneBlockAccountsForItsEntireBackingArray() {
        String original = "é".repeat(100_000);
        GraphemeText metrics = GraphemeText.of(original);
        String retained = "é".repeat(257);

        GraphemeText changed = metrics.updated(retained, 0, original.length() - retained.length(), 0);

        assertEquals(257, changed.graphemeCount());
        assertEquals(retained.length(), changed.ceilBoundary(retained.length()));
        assertTrue(changed.storageWeight() >= metrics.storageWeight(),
            "The small slice still retains the original dense boundary array");
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
