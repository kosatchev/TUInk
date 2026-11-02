package dev.tuiframe.render;

import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalTextLayoutCache.MetricsHandle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Owner-thread LRU for prepared rows. Frames borrow handles, never unaccounted raw cache values. */
public final class PreparedRowCache implements AutoCloseable {
    private final RenderMemoryBudget budget;
    private final int maxEntries;
    private final long maxRetainedBytes;
    private final LinkedHashMap<PreparedStyledRow.Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long retainedBytes;
    private long hits;
    private long misses;
    private long metricsBuilds;
    private boolean closed;

    public PreparedRowCache(RenderMemoryBudget budget, int maxEntries, long maxRetainedBytes) {
        this.budget = Objects.requireNonNull(budget, "budget");
        if (maxEntries < 1 || maxRetainedBytes < 1) throw new IllegalArgumentException("Cache limits must be positive");
        this.maxEntries = maxEntries;
        this.maxRetainedBytes = maxRetainedBytes;
    }

    /**
     * Returns null on refused admission, before segmentation/preparation. Callers can then use their
     * bounded streaming painter. The key must identify all immutable text/base-style inputs.
     * Oversized rows may be admitted transiently but are never retained by this cache.
     */
    public Handle acquire(PreparedStyledRow.Key key, CharSequence text, CellStyle defaultStyle,
                          List<PreparedStyledRow.Span> spans) {
        return acquire(key, text, null, 0, 0, defaultStyle, spans);
    }

    /** Reuses one admitted full-row index, pinning it for every cache/frame owner of the visible slice. */
    public Handle acquire(PreparedStyledRow.Key key, MetricsHandle geometry, int start, int end,
                          CellStyle defaultStyle, List<PreparedStyledRow.Span> spans) {
        Objects.requireNonNull(geometry, "geometry");
        var metrics = geometry.metrics();
        if (start < 0 || end < start || end > metrics.sequence().length() || !metrics.isBoundary(start) || !metrics.isBoundary(end)
            || key.sliceStart() != start || key.sliceLength() != end - start || key.tabWidth() != metrics.tabWidth()
            || key.tabPhase() != metrics.visualColumnAt(start) % metrics.tabWidth())
            throw new IllegalArgumentException("Row identity must describe its admitted geometry slice");
        return acquire(key, null, geometry, start, end, defaultStyle, spans);
    }

    private Handle acquire(PreparedStyledRow.Key key, CharSequence text, MetricsHandle geometry, int start, int end,
                           CellStyle defaultStyle, List<PreparedStyledRow.Span> spans) {
        if (closed) throw new IllegalStateException("Prepared row cache is closed");
        Objects.requireNonNull(key, "key");
        if (geometry == null) Objects.requireNonNull(text, "text");
        Objects.requireNonNull(defaultStyle, "defaultStyle");
        Objects.requireNonNull(spans, "spans");
        int length = geometry == null ? text.length() : end - start;
        if (key.sliceLength() != length) throw new IllegalArgumentException("Row identity must describe its exact slice");
        Entry cached = entries.get(key);
        if (cached != null) { hits++; return cached.borrow(key); }
        misses++;
        long bytes = PreparedStyledRow.estimatedBytes(length, spans.size());
        boolean cacheable = bytes <= maxRetainedBytes;
        if (cacheable) while (!entries.isEmpty() && (entries.size() >= maxEntries || retainedBytes > maxRetainedBytes - bytes))
            evictOldest();
        RenderMemoryBudget.Reservation reservation = budget.tryReserve(bytes);
        while (reservation == null && !entries.isEmpty()) {
            evictOldest();
            reservation = budget.tryReserve(bytes);
        }
        if (reservation == null) return null;
        MetricsHandle dependency = null;
        try {
            if (geometry != null) dependency = geometry.retain();
            var preparedGeometry = dependency == null ? PreparedStyledRow.prepareGeometry(text, key.tabWidth())
                : dependency.metrics().view(start, end);
            if (dependency == null) metricsBuilds++;
            PreparedStyledRow prepared = PreparedStyledRow.prepare(preparedGeometry, key.tabPhase(), defaultStyle, spans);
            if (cacheable) {
                RenderMemoryBudget.Reservation cachedReservation = reservation.retain();
                MetricsHandle cacheDependency = null;
                Entry entry = null;
                try {
                    if (dependency != null) cacheDependency = dependency.retain();
                    entry = new Entry(prepared, cachedReservation, bytes, cacheDependency);
                    entries.put(key, entry);
                    retainedBytes += bytes;
                } catch (RuntimeException | Error failure) {
                    if (entry != null) entries.remove(key, entry);
                    if (cacheDependency != null) cacheDependency.close();
                    cachedReservation.close();
                    throw failure;
                }
            }
            return new Handle(key, prepared, reservation, dependency);
        } catch (RuntimeException | Error failure) {
            if (dependency != null) dependency.close();
            reservation.close();
            throw failure;
        }
    }

    public int size() { return entries.size(); }
    public long retainedBytes() { return retainedBytes; }
    public long hits() { return hits; }
    public long misses() { return misses; }
    /** Successful metric-index builds owned by raw row preparation; borrowed geometry adds none. */
    public long metricsBuilds() { return metricsBuilds; }

    /** Drops cache ownership. Borrowed frame handles remain valid and charged. */
    public void clear() {
        for (Entry entry : entries.values()) entry.close();
        entries.clear();
        retainedBytes = 0;
    }

    @Override public void close() { clear(); closed = true; }

    private void evictOldest() {
        var iterator = entries.entrySet().iterator();
        Entry eldest = iterator.next().getValue();
        iterator.remove();
        retainedBytes -= eldest.bytes;
        eldest.close();
    }

    private record Entry(PreparedStyledRow row, RenderMemoryBudget.Reservation reservation, long bytes, MetricsHandle dependency) {
        Handle borrow(PreparedStyledRow.Key key) {
            Handle handle = new Handle(key, row, null, null);
            handle.reservation = reservation.retain();
            try { if (dependency != null) handle.dependency = dependency.retain(); }
            catch (RuntimeException | Error failure) { handle.close(); throw failure; }
            return handle;
        }
        void close() { if (dependency != null) dependency.close(); reservation.close(); }
    }

    /** One real owner of row memory. Close when the frame/consumer no longer retains the row. */
    public static final class Handle implements AutoCloseable {
        private final PreparedStyledRow.Key key;
        private PreparedStyledRow row;
        private RenderMemoryBudget.Reservation reservation;
        private MetricsHandle dependency;
        private Handle(PreparedStyledRow.Key key, PreparedStyledRow row, RenderMemoryBudget.Reservation reservation, MetricsHandle dependency) {
            this.key = key; this.row = row; this.reservation = reservation; this.dependency = dependency;
        }
        public PreparedStyledRow.Key key() { return key; }
        public PreparedStyledRow row() {
            if (row == null) throw new IllegalStateException("Prepared row handle is closed");
            return row;
        }
        public Handle retain() {
            Handle retained = new Handle(key, row(), null, null);
            retained.reservation = reservation.retain();
            try { if (dependency != null) retained.dependency = dependency.retain(); }
            catch (RuntimeException | Error failure) { retained.close(); throw failure; }
            return retained;
        }
        @Override public void close() {
            if (reservation == null) return;
            row = null;
            if (dependency != null) dependency.close();
            dependency = null;
            reservation.close(); reservation = null;
        }
    }
}
