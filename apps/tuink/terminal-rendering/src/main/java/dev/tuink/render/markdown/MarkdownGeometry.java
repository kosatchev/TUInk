package dev.tuink.render.markdown;

import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.render.PreparedRowCache;
import dev.tuiframe.text.TerminalText;
import dev.tuiframe.text.TerminalTextLayoutCache;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded geometry adapter for an accepted immutable terminal Markdown layout. */
public final class MarkdownGeometry implements AutoCloseable {
    private static final AtomicLong IDENTITIES = new AtomicLong();
    private static final int TAB_WIDTH = 4;
    private final LayoutSource source;
    private final TerminalTextLayoutCache cache;
    private final PreparedRowCache rows;
    private final RenderMemoryBudget.Tracked budget;
    private ProjectionSource projectionSource;
    private TerminalTextLayoutCache projectionCache;
    private int[] contentRows;
    private RenderMemoryBudget.Reservation contentRowsReservation;
    private boolean mappingRefused;
    private long mappingBuilds;
    private boolean closed;

    public MarkdownGeometry(MarkdownLayout.Result result, RenderMemoryBudget budget) {
        this(result, budget, new TerminalTextLayoutCache.MetricsLimits(256, 8L << 20));
    }

    public MarkdownGeometry(MarkdownLayout.Result result, RenderMemoryBudget budget,
                            TerminalTextLayoutCache.MetricsLimits limits) {
        source = new LayoutSource(Objects.requireNonNull(result), IDENTITIES.incrementAndGet());
        this.budget = RenderMemoryBudget.tracked(budget);
        cache = new TerminalTextLayoutCache(source, limits, this.budget);
        rows = new PreparedRowCache(this.budget, 256, 4L << 20);
    }

    public PreparedRowCache rows() { result(); return rows; }
    public long identity() { return source.identity; }
    public long revision() { return result().document().revision(); }
    public MarkdownLayout.Result result() {
        if (closed) throw new IllegalStateException("Markdown geometry is closed");
        return source.result;
    }
    public TerminalTextLayoutCache.MetricsHandle acquire(int row) {
        result(); return cache.acquireMetrics(row, TAB_WIDTH);
    }
    public long hits() { return cache.admittedMetricsHits(); }
    public long misses() { return cache.admittedMetricsMisses(); }
    public long visualMetricsBuilds() { return cache.admittedMetricsBuilds(); }
    public long semanticMetricsBuilds() { return projectionCache == null ? 0 : projectionCache.admittedMetricsBuilds(); }
    public long preparedRowsHits() { return rows.hits(); }
    public long preparedRowsMisses() { return rows.misses(); }
    /** Live geometry/raster allocations, including evicted rows still retained by frame handles. */
    public long retainedBytes() { return budget.usedBytes(); }

    public int projectionFloor(int offset) { return projectionBoundary(offset, false); }
    public int projectionCeil(int offset) { return projectionBoundary(offset, true); }
    public long projectionMetricsMisses() { return projectionCache == null ? 0 : projectionCache.admittedMetricsMisses(); }
    public long projectionLineScans() { return projectionSource == null ? 0 : projectionSource.revision; }
    public long mappingBuilds() { return mappingBuilds; }

    private int projectionBoundary(int offset, boolean ceil) {
        String text = result().document().projection().text();
        int safe = Math.max(0, Math.min(text.length(), offset));
        if (safe == 0 || safe == text.length() || text.charAt(safe - 1) == '\n' || text.charAt(safe) == '\n') return safe;
        if (projectionCache == null) {
            projectionSource = new ProjectionSource(text, source.identity);
            projectionCache = new TerminalTextLayoutCache(projectionSource,
                new TerminalTextLayoutCache.MetricsLimits(1, 16L << 20), budget);
        }
        projectionSource.locate(safe);
        try (var handle = projectionCache.acquireMetrics(0, TAB_WIDTH)) {
            var metrics = handle == null ? TerminalText.streamingGeometry(projectionSource.lineSequence(0), TAB_WIDTH)
                : handle.metrics();
            int local = safe - projectionSource.start;
            return projectionSource.start + (ceil ? metrics.ceilBoundary(local) : metrics.floorBoundary(local));
        }
    }

    /** Nearest semantic row; ties prefer the following row, matching decorative-row navigation. */
    public int nearestContentRow(int row) {
        var lines = result().lines();
        if (lines.isEmpty()) return -1;
        int safe = Math.max(0, Math.min(lines.size() - 1, row));
        if (contentRows == null && !mappingRefused) {
            var reservation = budget.tryReserve(128L + 4L * lines.size());
            if (reservation == null) mappingRefused = true;
            else try {
                int[] prepared = new int[lines.size()];
                int previous = -1;
                for (int index = 0; index < prepared.length; index++) {
                    if (hasContent(lines.get(index))) previous = index;
                    prepared[index] = previous;
                }
                int next = -1;
                for (int index = prepared.length - 1; index >= 0; index--) {
                    if (hasContent(lines.get(index))) next = index;
                    if (next >= 0 && (prepared[index] < 0 || next - index <= index - prepared[index])) prepared[index] = next;
                }
                contentRows = prepared; contentRowsReservation = reservation; mappingBuilds++;
            } catch (RuntimeException | Error failure) { reservation.close(); throw failure; }
        }
        if (contentRows != null) return contentRows[safe];
        if (hasContent(lines.get(safe))) return safe;
        for (int distance = 1; distance < lines.size(); distance++) {
            if (safe + distance < lines.size() && hasContent(lines.get(safe + distance))) return safe + distance;
            if (safe - distance >= 0 && hasContent(lines.get(safe - distance))) return safe - distance;
        }
        return -1;
    }
    private static boolean hasContent(MarkdownLayout.Line line) {
        for (var run : line.runs()) if (run.projectionStart() >= 0) return true;
        return false;
    }

    public int visualWidth(int row) {
        try (var handle = acquire(row)) { return geometry(row, handle).visualWidth(); }
    }
    public int visualColumnAt(int row, int offset) {
        try (var handle = acquire(row)) { return geometry(row, handle).visualColumnAt(offset); }
    }
    public int offsetAtColumn(int row, int column, TerminalText.Bias bias) {
        try (var handle = acquire(row)) { return geometry(row, handle).offsetForVisualColumn(column, bias); }
    }
    public int previousBoundary(int row, int offset) {
        try (var handle = acquire(row)) { return geometry(row, handle).previousBoundary(offset); }
    }
    public int nextBoundary(int row, int offset) {
        try (var handle = acquire(row)) { return geometry(row, handle).nextBoundary(offset); }
    }
    private TerminalText.Geometry geometry(int row, TerminalTextLayoutCache.MetricsHandle handle) {
        if (handle != null) return handle.metrics();
        var lines = result().lines();
        String text = lines.isEmpty() ? "" : lines.get(Math.max(0, Math.min(lines.size() - 1, row))).text();
        return TerminalText.streamingGeometry(text, TAB_WIDTH);
    }
    @Override public void close() {
        if (closed) return;
        rows.close(); cache.close();
        if (projectionCache != null) projectionCache.close();
        if (projectionSource != null) projectionSource.text = null;
        contentRows = null;
        if (contentRowsReservation != null) contentRowsReservation.close();
        contentRowsReservation = null; source.result = null; closed = true;
    }

    private static final class ProjectionSource implements TerminalTextLayoutCache.LineSource {
        private String text;
        private final long identity;
        private int start = -1, end = -1;
        private long revision;
        ProjectionSource(String text, long identity) { this.text = text; this.identity = identity; }
        void locate(int offset) {
            if (start >= 0 && offset >= start && offset <= end) return;
            start = text.lastIndexOf('\n', Math.max(-1, offset - 1)) + 1;
            int next = text.indexOf('\n', offset);
            end = next < 0 ? text.length() : next;
            revision++;
        }
        @Override public long cacheIdentity() { return identity; }
        @Override public long revision() { return revision; }
        @Override public long contentEpoch() { return identity; }
        @Override public int lineCount() { return 1; }
        @Override public String line(int index) { return text.substring(start, end); }
        @Override public CharSequence lineSequence(int index) { return TerminalText.slice(text, start, end); }
        @Override public long lineIdentity(int index) { return 1; }
    }

    private static final class LayoutSource implements TerminalTextLayoutCache.LineSource {
        private MarkdownLayout.Result result;
        private final long identity;
        LayoutSource(MarkdownLayout.Result result, long identity) { this.result = result; this.identity = identity; }
        @Override public long cacheIdentity() { return identity; }
        @Override public long revision() { return result.document().revision(); }
        @Override public long contentEpoch() { return identity; }
        @Override public int lineCount() { return result.lines().size(); }
        @Override public String line(int index) { return result.lines().get(index).text(); }
        @Override public long lineIdentity(int index) { return index + 1L; }
    }
}
