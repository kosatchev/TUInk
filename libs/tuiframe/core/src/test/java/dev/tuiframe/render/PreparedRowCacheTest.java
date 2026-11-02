package dev.tuiframe.render;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.AbstractList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreparedRowCacheTest {
    private static final CellStyle BODY = CellStyle.DEFAULT;
    private static final CellStyle ACCENT = new CellStyle(CellColor.CYAN, CellColor.DEFAULT, true, false);

    @Test void refusalHappensBeforeReadingOrSegmentingTheText() {
        var budget = RenderMemoryBudget.bounded(1);
        var text = new CountingText("界x");
        try (var cache = new PreparedRowCache(budget, 2, 10_000)) {
            assertNull(cache.acquire(key(0, text.length()), text, BODY, List.of()));
            assertEquals(0, text.reads);
            assertEquals(0, budget.usedBytes());
            assertEquals(0, cache.size());
            assertEquals(0, cache.metricsBuilds());
        }
    }

    @Test void unchangedRowReusesPreparedValueWithoutResegmenting() {
        var budget = RenderMemoryBudget.bounded(10_000);
        var text = new CountingText("界x");
        try (var cache = new PreparedRowCache(budget, 2, 10_000);
             var first = cache.acquire(key(0, text.length()), text, BODY, List.of())) {
            int reads = text.reads;
            try (var second = cache.acquire(first.key(), text, BODY, List.of())) {
                assertSame(first.row(), second.row());
                assertEquals(reads, text.reads);
                assertEquals(1, cache.hits());
                assertEquals(1, cache.misses());
                assertEquals(1, cache.metricsBuilds());
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void anEvictedRowStaysChargedWhileAFrameRetainsIt() {
        long bytes = PreparedStyledRow.estimatedBytes(1, 0);
        var budget = RenderMemoryBudget.bounded(bytes * 2);
        var cache = new PreparedRowCache(budget, 1, bytes);
        var firstFrame = cache.acquire(key(0, 1), "a", BODY, List.of());
        var secondFrame = cache.acquire(key(1, 1), "b", BODY, List.of());
        assertEquals(1, cache.size());
        assertEquals(bytes, cache.retainedBytes());
        assertEquals(bytes * 2, budget.usedBytes());
        assertEquals("a", firstFrame.row().display(0));
        cache.clear();
        assertEquals(0, cache.retainedBytes());
        assertEquals(bytes * 2, budget.usedBytes());
        firstFrame.close();
        assertEquals(bytes, budget.usedBytes());
        secondFrame.close();
        assertEquals(0, budget.usedBytes());
        cache.close();
    }

    @Test void frameBorrowAfterCacheCloseRemainsValidUntilItsLastHandleCloses() {
        long bytes = PreparedStyledRow.estimatedBytes(1, 0);
        var budget = RenderMemoryBudget.bounded(bytes);
        var cache = new PreparedRowCache(budget, 1, bytes);
        var frame = cache.acquire(key(0, 1), "a", BODY, List.of());
        var retained = frame.retain();
        cache.close();
        budget.close();
        var lateBorrow = retained.retain();
        frame.close();
        assertEquals(bytes, budget.usedBytes());
        assertEquals("a", retained.row().display(0));
        assertThrows(IllegalStateException.class, frame::row);
        assertThrows(IllegalStateException.class, frame::retain);
        retained.close();
        retained.close();
        assertEquals(bytes, budget.usedBytes());
        assertEquals("a", lateBorrow.row().display(0));
        lateBorrow.close();
        assertEquals(0, budget.usedBytes());
        assertThrows(IllegalStateException.class, () -> cache.acquire(key(0, 1), "a", BODY, List.of()));
    }

    @Test void activeFrameCanPreventAdmissionEvenAfterCacheEviction() {
        long bytes = PreparedStyledRow.estimatedBytes(1, 0);
        var budget = RenderMemoryBudget.bounded(bytes);
        try (var cache = new PreparedRowCache(budget, 1, bytes)) {
            var first = cache.acquire(key(0, 1), "a", BODY, List.of());
            assertNull(cache.acquire(key(1, 1), "b", BODY, List.of()));
            assertEquals(0, cache.size());
            assertEquals(bytes, budget.usedBytes());
            first.close();
            try (var second = cache.acquire(key(1, 1), "b", BODY, List.of())) {
                assertNotNull(second);
                assertEquals("b", second.row().display(0));
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void overCacheLimitRowCanBeAdmittedOnlyAsATransientOwnedValue() {
        long bytes = PreparedStyledRow.estimatedBytes(1, 0);
        var budget = RenderMemoryBudget.bounded(bytes);
        try (var cache = new PreparedRowCache(budget, 1, bytes - 1)) {
            var frame = cache.acquire(key(0, 1), "a", BODY, List.of());
            assertNotNull(frame);
            assertEquals(0, cache.size());
            assertEquals(bytes, budget.usedBytes());
            frame.close();
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test void differentConsumersShareOneExternalBudgetAndClosingOneDoesNotCloseTheBudget() {
        long bytes = PreparedStyledRow.estimatedBytes(1, 0);
        var budget = RenderMemoryBudget.bounded(bytes);
        var first = new PreparedRowCache(budget, 1, bytes);
        try (var second = new PreparedRowCache(budget, 1, bytes)) {
            try (var frame = first.acquire(key(0, 1), "a", BODY, List.of())) {
                assertNull(second.acquire(key(1, 1), "b", BODY, List.of()));
            }
            first.close();
            try (var frame = second.acquire(key(1, 1), "b", BODY, List.of())) {
                assertNotNull(frame);
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void failedPreparationReleasesItsReservation() {
        var budget = RenderMemoryBudget.bounded(10_000);
        var broken = new CharSequence() {
            @Override public int length() { return 1; }
            @Override public char charAt(int index) { throw new IllegalStateException("cannot read"); }
            @Override public CharSequence subSequence(int start, int end) { throw new AssertionError(); }
        };
        try (var cache = new PreparedRowCache(budget, 2, 10_000)) {
            assertThrows(IllegalStateException.class, () -> cache.acquire(key(0, 1), broken, BODY, List.of()));
            assertEquals(0, budget.usedBytes());
            assertEquals(0, cache.size());
            assertEquals(0, cache.metricsBuilds());
        }
    }

    @Test void successfulGeometryIsCountedEvenWhenTheLaterStyleProducerFails() {
        var budget = RenderMemoryBudget.bounded(10_000);
        var spans = new AbstractList<PreparedStyledRow.Span>() {
            @Override public int size() { return 1; }
            @Override public PreparedStyledRow.Span get(int index) { throw new IllegalStateException("broken style"); }
        };
        try (var cache = new PreparedRowCache(budget, 2, 10_000)) {
            assertThrows(IllegalStateException.class, () -> cache.acquire(key(0, 1), "a", BODY, spans));
            assertEquals(1, cache.metricsBuilds());
            assertEquals(0, cache.size());
            assertEquals(0, budget.usedBytes());
            try (var recovered = cache.acquire(key(0, 1), "a", BODY, List.of())) {
                assertNotNull(recovered);
                assertEquals(2, cache.metricsBuilds());
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void fullIdentityDistinguishesHashCollisionsContentStylesAndSlices() {
        var first = new PreparedStyledRow.Key(1, 1, 1, 1, 0, 0, 1, 4, 0);
        var collision = new PreparedStyledRow.Key(1, 1, 1L << 32, 1, 0, 0, 1, 4, 0);
        assertEquals(first.hashCode(), collision.hashCode());
        assertNotEquals(first, collision);
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var cache = new PreparedRowCache(budget, 10, 100_000);
             var a = cache.acquire(first, "a", BODY, List.of());
             var b = cache.acquire(collision, "b", BODY, List.of());
             var styled = cache.acquire(new PreparedStyledRow.Key(1, 1, 1, 2, 0, 0, 1, 4, 0),
                 "a", ACCENT, List.of());
             var slice = cache.acquire(new PreparedStyledRow.Key(1, 1, 1, 1, 0, 1, 1, 4, 0),
                 "b", BODY, List.of())) {
            assertEquals("a", a.row().display(0));
            assertEquals("b", b.row().display(0));
            assertEquals(ACCENT, styled.row().baseStyle(0));
            assertNotSame(a.row(), slice.row());
            assertEquals(4, cache.misses());
        }
    }

    @Test void lruEvictsTheLeastRecentlyUsedEntryBeforeAdmission() {
        long bytes = PreparedStyledRow.estimatedBytes(1, 0);
        var budget = RenderMemoryBudget.bounded(bytes * 2);
        try (var cache = new PreparedRowCache(budget, 2, bytes * 2)) {
            cache.acquire(key(0, 1), "a", BODY, List.of()).close();
            cache.acquire(key(1, 1), "b", BODY, List.of()).close();
            cache.acquire(key(0, 1), "a", BODY, List.of()).close();
            cache.acquire(key(2, 1), "c", BODY, List.of()).close();
            assertEquals(2, cache.size());
            assertEquals(3, cache.misses());
            cache.acquire(key(0, 1), "a", BODY, List.of()).close();
            assertEquals(3, cache.misses());
            cache.acquire(key(1, 1), "b", BODY, List.of()).close();
            assertEquals(4, cache.misses());
        }
        assertEquals(0, budget.usedBytes());
    }

    private static PreparedStyledRow.Key key(int row, int length) {
        return new PreparedStyledRow.Key(1, 2, 3, 4, row, 0, length, 4, 0);
    }

    private static final class CountingText implements CharSequence {
        private final String text;
        int reads;
        CountingText(String text) { this.text = text; }
        @Override public int length() { return text.length(); }
        @Override public char charAt(int index) { reads++; return text.charAt(index); }
        @Override public CharSequence subSequence(int start, int end) { reads++; return text.subSequence(start, end); }
    }
}
