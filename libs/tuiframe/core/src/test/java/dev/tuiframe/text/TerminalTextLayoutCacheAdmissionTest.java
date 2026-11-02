package dev.tuiframe.text;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.memory.RenderMemoryBudget;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalTextLayoutCacheAdmissionTest {
    @Test void refusedAdmissionDoesNotCopyOrSegmentTheInput() {
        var source = new Lines();
        source.value = new CharSequence() {
            @Override public int length() { return 2; }
            @Override public char charAt(int index) { fail("refused allocation read text"); return 0; }
            @Override public CharSequence subSequence(int start, int end) { throw new AssertionError(); }
        };
        var budget = RenderMemoryBudget.bounded(1);
        try (var cache = admitted(source, budget, 100_000)) {
            assertNull(cache.acquireMetrics(0, 4));
            assertEquals(0, budget.usedBytes());
            assertEquals(0, cache.admittedMetricsBytes());
            assertEquals(0, cache.admittedMetricsBuilds());
        }
    }

    @Test void sharedOwnersStayChargedAfterEvictionClearAndClosingAdmission() {
        var source = new Lines();
        var budget = RenderMemoryBudget.bounded(100_000);
        var cache = admitted(source, budget, 100_000);
        var first = cache.acquireMetrics(0, 4);
        long charge = budget.usedBytes();
        var same = cache.acquireMetrics(0, 4);
        assertSame(first.metrics(), same.metrics());
        assertEquals(1, cache.admittedMetricsHits());
        assertEquals(1, cache.admittedMetricsBuilds());
        cache.clear();
        budget.close();
        var last = same.retain();
        first.close(); same.close(); cache.close();
        assertEquals(charge, budget.usedBytes());
        assertEquals("界\tx", last.metrics().text());
        last.close();
        assertEquals(0, budget.usedBytes());
        assertThrows(IllegalStateException.class, last::metrics);
        assertThrows(IllegalStateException.class, () -> cache.acquireMetrics(0, 4));
    }

    @Test void revisionsEpochAndTabWidthInvalidateAnAdmittedEntry() {
        var source = new Lines();
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var cache = admitted(source, budget, 100_000)) {
            cache.acquireMetrics(0, 4).close();
            source.revision++;
            source.value = "new";
            try (var current = cache.acquireMetrics(0, 4)) { assertEquals("new", current.metrics().text()); }
            source.epoch++;
            cache.acquireMetrics(0, 4).close();
            cache.acquireMetrics(0, 2).close();
            assertEquals(4, cache.admittedMetricsMisses());
            assertEquals(4, cache.admittedMetricsBuilds());
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void oversizedEntryIsTransientAndFailureReleasesItsReservation() {
        var source = new Lines();
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var cache = admitted(source, budget, 1)) {
            var temporary = cache.acquireMetrics(0, 4);
            assertNotNull(temporary);
            assertEquals(0, cache.admittedMetricsBytes());
            assertTrue(budget.usedBytes() > 0);
            temporary.close();
            assertEquals(0, budget.usedBytes());
        }
        source.value = new CharSequence() {
            @Override public int length() { return 1; }
            @Override public char charAt(int index) { throw new IllegalStateException("broken source"); }
            @Override public CharSequence subSequence(int start, int end) { throw new AssertionError(); }
        };
        try (var cache = admitted(source, budget, 100_000)) {
            assertThrows(IllegalStateException.class, () -> cache.acquireMetrics(0, 4));
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test void admittedModeRejectsUnownedReturnsWhileDefaultEditorCacheStillWorks() {
        var source = new Lines();
        try (var cache = admitted(source, RenderMemoryBudget.bounded(100_000), 100_000)) {
            assertThrows(IllegalStateException.class, () -> cache.metrics(0, 4));
            assertThrows(IllegalStateException.class, () -> cache.wrapStarts(0, 10, false, 4));
        }
        try (var editor = new TerminalTextLayoutCache(source)) {
            assertEquals(5, editor.metrics(0, 4).visualWidth());
            assertFalse(editor.wrapStarts(0, 3, false, 4).isEmpty());
        }
        for (var field : TerminalTextLayoutCache.MetricsHandle.class.getDeclaredFields()) {
            assertNotEquals(TerminalTextLayoutCache.class, field.getType());
            assertNotEquals(TerminalTextLayoutCache.LineSource.class, field.getType());
        }
    }

    @Test void producerErrorsReleaseAdmissionAndDoNotPoisonTheNextBuild() {
        var source = new Lines();
        var failure = new OutOfMemoryError("synthetic allocation failure");
        source.value = new CharSequence() {
            @Override public int length() { return 1; }
            @Override public char charAt(int index) { throw failure; }
            @Override public CharSequence subSequence(int start, int end) { throw new AssertionError(); }
        };
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var cache = admitted(source, budget, 100_000)) {
            assertSame(failure, assertThrows(OutOfMemoryError.class, () -> cache.acquireMetrics(0, 4)));
            assertEquals(0, budget.usedBytes());
            assertEquals(0, cache.admittedMetricsBytes());
            source.value = "recovered";
            try (var handle = cache.acquireMetrics(0, 4)) {
                assertNotNull(handle);
                assertEquals("recovered", handle.metrics().text());
                assertEquals(1, cache.admittedMetricsBuilds());
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    private static TerminalTextLayoutCache admitted(Lines source, RenderMemoryBudget budget, long bytes) {
        return new TerminalTextLayoutCache(source, new TerminalTextLayoutCache.MetricsLimits(2, bytes), budget);
    }
    private static final class Lines implements TerminalTextLayoutCache.LineSource {
        CharSequence value = "界\tx"; long revision = 1, epoch = 1;
        @Override public long cacheIdentity() { return 1; }
        @Override public long revision() { return revision; }
        @Override public long contentEpoch() { return epoch; }
        @Override public int lineCount() { return 1; }
        @Override public String line(int index) { return value.toString(); }
        @Override public CharSequence lineSequence(int index) { return value; }
        @Override public List<TerminalTextLayoutCache.LineChange> changesSince(long previous) { return null; }
    }
}
