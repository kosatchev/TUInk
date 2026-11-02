package dev.tuiframe.render;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import dev.tuiframe.text.TerminalTextLayoutCache;
import java.util.AbstractList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreparedRowGeometryTest {
    @Test void aFramePinsSharedGeometryAfterBothCachesClose() {
        var budget = RenderMemoryBudget.bounded(100_000);
        var geometryCache = geometry(budget);
        var geometry = geometryCache.acquireMetrics(0, 4);
        long metricBytes = budget.usedBytes();
        var rows = new PreparedRowCache(budget, 2, 100_000);
        var key = new PreparedStyledRow.Key(1, 2, 3, 4, 0, 1, 2, 4, 1);
        var frame = rows.acquire(key, geometry, 1, 3, CellStyle.DEFAULT, List.of());
        long completeBytes = metricBytes + PreparedStyledRow.estimatedBytes(2, 0);
        assertEquals(completeBytes, budget.usedBytes());
        assertEquals("\t界", frame.row().metrics().text());
        assertEquals(3, frame.row().advance(0));
        assertEquals(2, frame.row().advance(1));
        assertEquals(5, frame.row().visualWidth());
        assertEquals(0, rows.metricsBuilds(), "A prepared slice borrows its admitted metric index");
        assertEquals(1, geometryCache.admittedMetricsBuilds());
        geometry.close(); geometryCache.close(); rows.close();
        assertEquals(completeBytes, budget.usedBytes());
        var retained = frame.retain();
        frame.close();
        assertEquals(completeBytes, budget.usedBytes());
        assertEquals("界", retained.row().display(1));
        retained.close();
        assertEquals(0, budget.usedBytes());
    }

    @Test void refusedPreparedAllocationDoesNotLeakGeometryOwnership() {
        long metricBytes = 1024L + 32L * 4;
        var budget = RenderMemoryBudget.bounded(metricBytes);
        try (var geometryCache = geometry(budget); var geometry = geometryCache.acquireMetrics(0, 4);
             var rows = new PreparedRowCache(budget, 2, 100_000)) {
            var key = new PreparedStyledRow.Key(1, 2, 3, 4, 0, 1, 2, 4, 1);
            assertNull(rows.acquire(key, geometry, 1, 3, CellStyle.DEFAULT, List.of()));
            assertEquals(metricBytes, budget.usedBytes());
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void aPreparedValueUsesItsSuppliedGeometryDirectly() {
        var source = TerminalText.metrics("x\t界y", 4);
        var view = source.view(1, 3);
        var row = PreparedStyledRow.prepare(view, 1, CellStyle.DEFAULT, List.of());
        assertSame(view, row.metrics());
        assertEquals(5, row.visualWidth());
    }

    @Test void keyMustMatchTheRealGeometrySliceAndAbsoluteTabPhase() {
        var budget = RenderMemoryBudget.bounded(100_000);
        try (var geometryCache = geometry(budget); var geometry = geometryCache.acquireMetrics(0, 4);
             var rows = new PreparedRowCache(budget, 2, 100_000)) {
            long before = budget.usedBytes();
            var wrongPhase = new PreparedStyledRow.Key(1, 2, 3, 4, 0, 1, 2, 4, 0);
            assertThrows(IllegalArgumentException.class,
                () -> rows.acquire(wrongPhase, geometry, 1, 3, CellStyle.DEFAULT, List.of()));
            assertEquals(before, budget.usedBytes());
        }
    }

    @Test void preparationFailureReleasesItsReservationAndBorrowedGeometryForExceptionsAndErrors() {
        for (Throwable failure : List.of(new IllegalStateException("synthetic producer failure"),
                new OutOfMemoryError("synthetic allocation failure"))) {
            var budget = RenderMemoryBudget.bounded(100_000);
            try (var geometryCache = geometry(budget); var geometry = geometryCache.acquireMetrics(0, 4);
                 var rows = new PreparedRowCache(budget, 2, 100_000)) {
                long before = budget.usedBytes();
                var spans = new AbstractList<PreparedStyledRow.Span>() {
                    @Override public int size() { return 1; }
                    @Override public PreparedStyledRow.Span get(int index) {
                        if (failure instanceof Error error) throw error;
                        throw (RuntimeException) failure;
                    }
                };
                var key = new PreparedStyledRow.Key(1, 2, 3, 4, 0, 1, 2, 4, 1);
                assertSame(failure, assertThrows(failure.getClass(),
                    () -> rows.acquire(key, geometry, 1, 3, CellStyle.DEFAULT, spans)));
                assertEquals(before, budget.usedBytes());
                assertEquals(0, rows.size());
                assertEquals(0, rows.metricsBuilds());
                geometryCache.clear();
                geometry.close();
                assertEquals(0, budget.usedBytes(), "A failed builder must not pin its geometry dependency");
            }
        }
    }

    private static TerminalTextLayoutCache geometry(RenderMemoryBudget budget) {
        return new TerminalTextLayoutCache(new TerminalTextLayoutCache.LineSource() {
            @Override public long cacheIdentity() { return 1; }
            @Override public long revision() { return 1; }
            @Override public long contentEpoch() { return 1; }
            @Override public int lineCount() { return 1; }
            @Override public String line(int index) { return "x\t界y"; }
        }, new TerminalTextLayoutCache.MetricsLimits(10, 100_000), budget);
    }
}
