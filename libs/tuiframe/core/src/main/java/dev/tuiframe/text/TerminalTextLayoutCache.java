package dev.tuiframe.text;

import dev.tuiframe.memory.RenderMemoryBudget;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Version-aware terminal text metrics and wrapping cache for a line source.
 *
 * <p>The cache is mutable and intended to be confined to one UI/render
 * thread. A source revision must change after every visible content mutation.
 * A source content epoch must additionally change after a wholesale content
 * replacement for which retained line metrics should be discarded. Per-line
 * metrics and explicit wrap-start lists are weight-bounded. Wrap layouts retain
 * a small access-order set of compact row-count indices for recent
 * width/word-wrap/tab-width combinations; indexing offscreen lines does not
 * retain their wrap-start lists.</p>
 */
public final class TerminalTextLayoutCache implements AutoCloseable {
    private static final int DEFAULT_MAX_METRICS_LINES = 200_000;
    private static final int DEFAULT_MAX_METRICS_WEIGHT = 8_388_608;
    private static final int DEFAULT_MAX_WRAP_LAYOUTS = 4;
    private static final int DEFAULT_MAX_WRAP_STARTS_LINES = 4_096;
    private static final int DEFAULT_MAX_WRAP_STARTS_WEIGHT = 262_144;

    /** Versioned random-access source of non-null text lines. */
    public interface LineSource {
        /** Stable identity of the logical source; changing it resets every cache. */
        long cacheIdentity();

        /** Content revision, changed after every visible text or line mutation. */
        long revision();

        /** Generation changed for wholesale content replacement or source reset. */
        long contentEpoch();

        /**
         * Complete ordered line replacements after a revision, or {@code null}
         * if unavailable. Coordinates follow each preceding replacement; line()
         * always returns current text. Sources without history use full invalidation.
         */
        default List<LineChange> changesSince(long revision) {
            return null;
        }

        int lineCount();

        String line(int index);

        /** Immutable source; frontends may avoid constructing a contiguous String. */
        default CharSequence lineSequence(int index) { return line(index); }

        /** Identity of this immutable line value, or zero when unavailable. */
        default long lineIdentity(int index) { return 0; }

        /** Exact replacement from a previous immutable line, or null when unavailable. */
        default TextEdit textEdit(int index) { return null; }
    }

    public record TextEdit(long sourceIdentity, int start, int removedLength, int insertedLength) {}

    /** Bounds for an admitted metrics-only cache; wrapped layouts already belong to its consumer. */
    public record MetricsLimits(int maxEntries, long maxBytes) {
        public MetricsLimits {
            if (maxEntries < 1 || maxBytes < 1) throw new IllegalArgumentException("Metrics limits must be positive");
        }
    }

    public record LineChange(int firstLine, int removedLineCount, int addedLineCount) {
        public LineChange {
            if (firstLine < 0 || removedLineCount < 0 || addedLineCount < 0) {
                throw new IllegalArgumentException("Line change coordinates must be nonnegative");
            }
        }
    }

    private final LineSource source;
    private final boolean reserveLineEndCell;
    private final int maxMetricsLines;
    private final int maxMetricsWeight;
    private final int maxWrapLayouts;
    private final int maxWrapStartsLines;
    private final int maxWrapStartsWeight;
    private final Map<Integer, MetricsEntry> metricsCache =
        new LinkedHashMap<>(256, 0.75f, true);
    private final Map<WrapLayoutKey, WrapLayout> wrapLayouts;
    private int metricsWeight;
    private int wrapStartsCount;
    private long wrapStartsWeight;
    private int cachedVisualWidthTabWidth = -1;
    private LineLayoutIndex visualWidths;
    private boolean sourceObserved;
    private long observedCacheIdentity;
    private long observedRevision;
    private long observedContentEpoch;
    private int observedLineCount;
    private RenderMemoryBudget metricsBudget;
    private MetricsLimits metricsLimits;
    private LinkedHashMap<Integer, AdmittedMetrics> admittedMetrics;
    private long admittedBytes;
    private long admittedHits;
    private long admittedMisses;
    private long admittedBuilds;
    private boolean closed;

    public TerminalTextLayoutCache(LineSource source) {
        this(source, false);
    }

    /**
     * Admitted metrics-only mode. Use acquireMetrics and close every returned handle.
     * Raw metrics and wrapping APIs are unavailable because they return unowned references.
     * Existing constructors preserve the editor's incremental and oversized-entry policies.
     */
    public TerminalTextLayoutCache(LineSource source, MetricsLimits limits, RenderMemoryBudget budget) {
        this(source);
        metricsLimits = Objects.requireNonNull(limits, "limits");
        metricsBudget = Objects.requireNonNull(budget, "budget");
        admittedMetrics = new LinkedHashMap<>(16, 0.75f, true);
    }

    /** One actual owner of a metric allocation. It never retains its cache or line source. */
    public static final class MetricsHandle implements AutoCloseable {
        private TerminalText.Metrics metrics;
        private RenderMemoryBudget.Reservation reservation;
        private MetricsHandle(TerminalText.Metrics metrics, RenderMemoryBudget.Reservation reservation) {
            this.metrics = metrics; this.reservation = reservation;
        }
        public TerminalText.Metrics metrics() {
            if (metrics == null) throw new IllegalStateException("Metrics handle is closed");
            return metrics;
        }
        public MetricsHandle retain() {
            MetricsHandle retained = new MetricsHandle(metrics(), null);
            retained.reservation = reservation.retain();
            return retained;
        }
        @Override public void close() {
            if (reservation == null) return;
            reservation.close(); reservation = null; metrics = null;
        }
    }

    private record AdmittedMetrics(TerminalText.Metrics metrics, long lineIdentity, int tabWidth,
                                   RenderMemoryBudget.Reservation reservation, long bytes) {
        MetricsHandle borrow() {
            MetricsHandle handle = new MetricsHandle(metrics, null);
            handle.reservation = reservation.retain();
            return handle;
        }
    }

    /** Returns null on refusal before copying text or building metric arrays. */
    public MetricsHandle acquireMetrics(int line, int tabWidth) {
        if (closed) throw new IllegalStateException("Text layout cache is closed");
        if (metricsBudget == null) throw new IllegalStateException("Metrics handles require an admitted cache");
        long identity = source.cacheIdentity(), epoch = source.contentEpoch(), revision = source.revision();
        if (!sourceObserved || observedCacheIdentity != identity || observedContentEpoch != epoch || observedRevision != revision)
            clearAdmittedMetrics();
        sourceObserved = true; observedCacheIdentity = identity; observedContentEpoch = epoch; observedRevision = revision;
        int count = safeLineCount();
        if (count == 0) return null;
        int safeLine = Math.max(0, Math.min(count - 1, line));
        int width = Math.max(1, tabWidth);
        CharSequence text = requireLine(safeLine);
        long lineIdentity = source.lineIdentity(safeLine);
        AdmittedMetrics cached = admittedMetrics.get(safeLine);
        if (cached != null && cached.tabWidth() == width && (lineIdentity != 0 && cached.lineIdentity() == lineIdentity
            || text instanceof String && cached.metrics().sequence().equals(text))) {
            admittedHits++; return cached.borrow();
        }
        if (cached != null) removeAdmitted(safeLine);
        admittedMisses++;
        long bytes = 1024L + 32L * text.length();
        boolean cacheable = bytes <= metricsLimits.maxBytes();
        if (cacheable) while (!admittedMetrics.isEmpty() && (admittedMetrics.size() >= metricsLimits.maxEntries()
            || admittedBytes > metricsLimits.maxBytes() - bytes)) evictAdmitted();
        RenderMemoryBudget.Reservation reservation = metricsBudget.tryReserve(bytes);
        while (reservation == null && !admittedMetrics.isEmpty()) {
            evictAdmitted(); reservation = metricsBudget.tryReserve(bytes);
        }
        if (reservation == null) return null;
        try {
            String owned;
            if (text instanceof String string) owned = string;
            else {
                char[] copy = new char[text.length()];
                for (int index = 0; index < copy.length; index++) copy[index] = text.charAt(index);
                owned = new String(copy);
            }
            TerminalText.Metrics value = TerminalText.metrics(owned, width);
            admittedBuilds++;
            if (cacheable) {
                var cacheReservation = reservation.retain();
                AdmittedMetrics entry = null;
                try {
                    entry = new AdmittedMetrics(value, lineIdentity, width, cacheReservation, bytes);
                    admittedMetrics.put(safeLine, entry); admittedBytes += bytes;
                } catch (RuntimeException | Error failure) {
                    if (entry != null) admittedMetrics.remove(safeLine, entry);
                    cacheReservation.close(); throw failure;
                }
            }
            return new MetricsHandle(value, reservation);
        } catch (RuntimeException | Error failure) { reservation.close(); throw failure; }
    }

    public long admittedMetricsHits() { return admittedHits; }
    public long admittedMetricsMisses() { return admittedMisses; }
    /** Successful index builds in admitted mode, excluding requests, refusals and cache hits. */
    public long admittedMetricsBuilds() { return admittedBuilds; }
    public long admittedMetricsBytes() { return admittedBytes; }

    /** Drops cache ownership; existing geometry/frame handles remain valid and charged. */
    public void clear() { clearAdmittedMetrics(); clearAllCaches(); sourceObserved = false; }
    @Override public void close() { clear(); closed = true; }
    private void clearAdmittedMetrics() {
        if (admittedMetrics == null) return;
        for (var entry : admittedMetrics.values()) entry.reservation().close();
        admittedMetrics.clear(); admittedBytes = 0;
    }
    private void removeAdmitted(int line) {
        var removed = admittedMetrics.remove(line);
        if (removed != null) { admittedBytes -= removed.bytes(); removed.reservation().close(); }
    }
    private void evictAdmitted() { removeAdmitted(admittedMetrics.keySet().iterator().next()); }

    /**
     * When requested by an editor, include a trailing empty row if the final
     * wrapped segment has no room for the insertion cursor after the text.
     */
    public TerminalTextLayoutCache(LineSource source, boolean reserveLineEndCell) {
        this(
            source,
            DEFAULT_MAX_METRICS_LINES,
            DEFAULT_MAX_METRICS_WEIGHT,
            DEFAULT_MAX_WRAP_LAYOUTS,
            reserveLineEndCell
        );
    }

    TerminalTextLayoutCache(LineSource source, int maxMetricsLines, int maxMetricsWeight) {
        this(source, maxMetricsLines, maxMetricsWeight, DEFAULT_MAX_WRAP_LAYOUTS);
    }

    TerminalTextLayoutCache(
        LineSource source,
        int maxMetricsLines,
        int maxMetricsWeight,
        int maxWrapLayouts
    ) {
        this(source, maxMetricsLines, maxMetricsWeight, maxWrapLayouts, false);
    }

    private TerminalTextLayoutCache(
        LineSource source,
        int maxMetricsLines,
        int maxMetricsWeight,
        int maxWrapLayouts,
        boolean reserveLineEndCell
    ) {
        this(source, maxMetricsLines, maxMetricsWeight, maxWrapLayouts,
            DEFAULT_MAX_WRAP_STARTS_LINES, DEFAULT_MAX_WRAP_STARTS_WEIGHT, reserveLineEndCell);
    }

    TerminalTextLayoutCache(
        LineSource source,
        int maxMetricsLines,
        int maxMetricsWeight,
        int maxWrapLayouts,
        int maxWrapStartsLines,
        int maxWrapStartsWeight
    ) {
        this(source, maxMetricsLines, maxMetricsWeight, maxWrapLayouts,
            maxWrapStartsLines, maxWrapStartsWeight, false);
    }

    private TerminalTextLayoutCache(
        LineSource source,
        int maxMetricsLines,
        int maxMetricsWeight,
        int maxWrapLayouts,
        int maxWrapStartsLines,
        int maxWrapStartsWeight,
        boolean reserveLineEndCell
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.reserveLineEndCell = reserveLineEndCell;
        if (maxMetricsLines <= 0) {
            throw new IllegalArgumentException("maxMetricsLines must be positive");
        }
        if (maxMetricsWeight <= 0) {
            throw new IllegalArgumentException("maxMetricsWeight must be positive");
        }
        if (maxWrapLayouts <= 0) {
            throw new IllegalArgumentException("maxWrapLayouts must be positive");
        }
        if (maxWrapStartsLines <= 0 || maxWrapStartsWeight <= 0) {
            throw new IllegalArgumentException("Wrap-start cache limits must be positive");
        }
        this.maxMetricsLines = maxMetricsLines;
        this.maxMetricsWeight = maxMetricsWeight;
        this.maxWrapLayouts = maxWrapLayouts;
        this.maxWrapStartsLines = maxWrapStartsLines;
        this.maxWrapStartsWeight = maxWrapStartsWeight;
        this.wrapLayouts = new LinkedHashMap<>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<WrapLayoutKey, WrapLayout> eldest) {
                if (size() > TerminalTextLayoutCache.this.maxWrapLayouts) {
                    clearWrapStarts(eldest.getValue());
                    return true;
                }
                return false;
            }
        };
    }

    /** Returns the currently observed source identity. */
    public long cacheIdentity() {
        synchronizeSource();
        return observedCacheIdentity;
    }

    public TerminalText.Metrics metrics(int line, int tabWidth) {
        synchronizeSource();
        return metricsSynchronized(line, tabWidth);
    }

    public int maxVisualLineWidth(int tabWidth) {
        synchronizeSource();
        int safeTabWidth = Math.max(1, tabWidth);
        if (visualWidths == null || cachedVisualWidthTabWidth != safeTabWidth) {
            int[] widths = new int[safeLineCount()];
            for (int line = 0; line < widths.length; line++) {
                widths[line] = metricsSynchronized(line, safeTabWidth).visualWidth();
            }
            visualWidths = new LineLayoutIndex(widths, true);
            cachedVisualWidthTabWidth = safeTabWidth;
        }
        return visualWidths.aggregate();
    }

    public List<Integer> wrapStarts(
        int line,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        synchronizeSource();
        return wrapStartsSynchronized(line, width, wrapByWords, tabWidth);
    }

    public int wrappedRowsBefore(
        int lineIndexExclusive,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        synchronizeSource();
        int count = safeLineCount();
        if (count == 0) {
            return 0;
        }
        LineLayoutIndex rows = wrapRowsSynchronized(width, wrapByWords, tabWidth, count);
        int limit = Math.max(0, Math.min(lineIndexExclusive, count));
        return rows.sumBefore(limit);
    }

    public int totalWrappedRows(int width, boolean wrapByWords, int tabWidth) {
        synchronizeSource();
        int count = safeLineCount();
        return wrapRowsSynchronized(width, wrapByWords, tabWidth, count).aggregate();
    }

    public int lineIndexFromWrappedOffset(
        int wrappedOffset,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        synchronizeSource();
        int count = safeLineCount();
        if (count == 0) {
            return 0;
        }
        return wrapRowsSynchronized(width, wrapByWords, tabWidth, count).lineAtOffset(wrappedOffset);
    }

    private TerminalText.Metrics metricsSynchronized(int line, int tabWidth) {
        int count = safeLineCount();
        if (count <= 0) {
            return TerminalText.metrics("", tabWidth);
        }
        int safeLine = Math.max(0, Math.min(line, count - 1));
        int safeTabWidth = Math.max(1, tabWidth);
        CharSequence text = requireLine(safeLine);
        MetricsEntry cached = metricsCache.get(safeLine);
        if (cached != null && cached.matches(text, safeTabWidth)) {
            return cached.metrics();
        }
        if (cached != null) {
            metricsCache.remove(safeLine);
            metricsWeight -= cached.weight();
        }

        TextEdit edit = source.textEdit(safeLine);
        TerminalText.Metrics metrics = cached != null && cached.tabWidth() == safeTabWidth && edit != null
            && cached.identity() != 0 && edit.sourceIdentity() == cached.identity()
            ? cached.metrics().updated(text, edit.start(), edit.removedLength(), edit.insertedLength())
            : TerminalText.metrics(text, safeTabWidth);
        MetricsEntry computed = new MetricsEntry(text, source.lineIdentity(safeLine), safeTabWidth, metrics);
        metricsCache.put(safeLine, computed);
        metricsWeight += computed.weight();
        trimMetricsCache();
        return metrics;
    }

    private List<Integer> wrapStartsSynchronized(
        int line,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        int count = safeLineCount();
        if (count <= 0) {
            return List.of(0);
        }
        int safeLine = Math.max(0, Math.min(line, count - 1));
        WrapLayoutKey key = new WrapLayoutKey(
            Math.max(0, width),
            wrapByWords,
            Math.max(1, tabWidth)
        );
        WrapLayout layout = wrapLayout(key);
        List<Integer> cached = layout.wrapStarts.get(safeLine);
        if (cached != null) {
            return cached;
        }
        List<Integer> computed = computeWrapStarts(safeLine, key);
        cacheWrapStarts(layout, safeLine, computed);
        return computed;
    }

    private LineLayoutIndex wrapRowsSynchronized(
        int width,
        boolean wrapByWords,
        int tabWidth,
        int lineCount
    ) {
        WrapLayoutKey key = new WrapLayoutKey(
            Math.max(0, width),
            wrapByWords,
            Math.max(1, tabWidth)
        );
        WrapLayout layout = wrapLayout(key);
        if (layout.rows != null) {
            return layout.rows;
        }
        int[] rows = new int[lineCount];
        for (int line = 0; line < lineCount; line++) {
            List<Integer> starts = layout.wrapStarts.get(line);
            rows[line] = starts == null ? computeWrapRowCount(line, key) : starts.size();
        }
        layout.rows = new LineLayoutIndex(rows, false);
        return layout.rows;
    }

    private List<Integer> computeWrapStarts(int line, WrapLayoutKey key) {
        TerminalText.Metrics metrics = metricsSynchronized(line, key.tabWidth());
        return TerminalLineLayout.wrapStarts(metrics, key.width(), key.wrapByWords(), reserveLineEndCell);
    }

    private int computeWrapRowCount(int line, WrapLayoutKey key) {
        TerminalText.Metrics metrics = metricsSynchronized(line, key.tabWidth());
        TerminalLineLayout.WrapSummary summary = TerminalLineLayout.wrapSummary(metrics, key.width(), key.wrapByWords());
        boolean lineEndRow = reserveLineEndCell && key.width() > 0 && !metrics.sequence().isEmpty()
            && metrics.visualWidth() - metrics.visualColumnAt(summary.lastStartOffset()) >= key.width();
        return summary.rowCount() + (lineEndRow ? 1 : 0);
    }

    private void cacheWrapStarts(WrapLayout layout, int line, List<Integer> starts) {
        int weight = TerminalLineLayout.retainedWeight(starts);
        if (weight > maxWrapStartsWeight) {
            return;
        }
        removeWrapStarts(layout, line);
        layout.wrapStarts.put(line, starts);
        wrapStartsCount++;
        wrapStartsWeight += weight;
        // Retain recent layouts first, and recent lines within each layout.
        for (WrapLayout candidate : wrapLayouts.values()) {
            var entries = candidate.wrapStarts.entrySet().iterator();
            while (entries.hasNext()
                && (wrapStartsCount > maxWrapStartsLines || wrapStartsWeight > maxWrapStartsWeight)) {
                wrapStartsWeight -= TerminalLineLayout.retainedWeight(entries.next().getValue());
                wrapStartsCount--;
                entries.remove();
            }
            if (wrapStartsCount <= maxWrapStartsLines && wrapStartsWeight <= maxWrapStartsWeight) {
                break;
            }
        }
    }

    private void removeWrapStarts(WrapLayout layout, int line) {
        List<Integer> removed = layout.wrapStarts.remove(line);
        if (removed != null) {
            wrapStartsCount--;
            wrapStartsWeight -= TerminalLineLayout.retainedWeight(removed);
        }
    }

    private void clearWrapStarts(WrapLayout layout) {
        wrapStartsCount -= layout.wrapStarts.size();
        for (List<Integer> starts : layout.wrapStarts.values()) {
            wrapStartsWeight -= TerminalLineLayout.retainedWeight(starts);
        }
        layout.wrapStarts.clear();
    }

    private WrapLayout wrapLayout(WrapLayoutKey key) {
        WrapLayout cached = wrapLayouts.get(key);
        if (cached != null) {
            return cached;
        }
        WrapLayout computed = new WrapLayout();
        wrapLayouts.put(key, computed);
        return computed;
    }

    private void synchronizeSource() {
        if (closed) throw new IllegalStateException("Text layout cache is closed");
        if (metricsBudget != null) throw new IllegalStateException("Admitted metrics cache requires owned handles");
        long cacheIdentity = source.cacheIdentity();
        long contentEpoch = source.contentEpoch();
        long revision = source.revision();
        if (!sourceObserved
            || observedCacheIdentity != cacheIdentity
            || observedContentEpoch != contentEpoch) {
            clearAllCaches();
        } else if (observedRevision != revision) {
            List<LineChange> changes = source.changesSince(observedRevision);
            if (!updateChangedLines(changes)) {
                clearLayoutCaches();
            }
        }
        sourceObserved = true;
        observedCacheIdentity = cacheIdentity;
        observedContentEpoch = contentEpoch;
        observedRevision = revision;
        observedLineCount = safeLineCount();
    }

    private boolean updateChangedLines(List<LineChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return false;
        }
        long count = observedLineCount;
        boolean changesLineCount = false;
        for (LineChange change : changes) {
            if (change == null || (long) change.firstLine() + change.removedLineCount() > count) {
                return false;
            }
            count += (long) change.addedLineCount() - change.removedLineCount();
            changesLineCount |= change.addedLineCount() != change.removedLineCount();
        }
        if (count != safeLineCount()) {
            return false;
        }
        if (!changesLineCount) {
            // Coordinates do not shift: read only the edited lines, even when
            // several distant edits arrive before the next frame.
            for (LineChange change : changes) {
                updateLineRange(change);
            }
        } else {
            // Coalesce splices in their final coordinates before reading text:
            // the source no longer contains intermediate document versions.
            int first = observedLineCount;
            int end = 0;
            int delta = 0;
            for (LineChange change : changes) {
                first = Math.min(first, change.firstLine());
                int shift = change.addedLineCount() - change.removedLineCount();
                end = Math.max(change.firstLine() + change.addedLineCount(), end + shift);
                delta += shift;
            }
            updateLineRange(new LineChange(first, end - delta - first, end - first));
        }
        return true;
    }

    private void updateLineRange(LineChange change) {
        int first = change.firstLine();
        int added = change.addedLineCount();
        boolean shifted = change.removedLineCount() != added;
        if (shifted) {
            metricsCache.clear();
            metricsWeight = 0;
        }
        if (visualWidths != null) {
            int[] widths = new int[added];
            for (int offset = 0; offset < added; offset++) {
                widths[offset] = metricsSynchronized(first + offset, cachedVisualWidthTabWidth).visualWidth();
            }
            visualWidths.replace(first, change.removedLineCount(), widths);
        }
        for (Map.Entry<WrapLayoutKey, WrapLayout> entry : wrapLayouts.entrySet()) {
            WrapLayout layout = entry.getValue();
            if (shifted) {
                clearWrapStarts(layout);
            } else {
                for (int line = first; line < first + added; line++) {
                    removeWrapStarts(layout, line);
                }
            }
            if (layout.rows != null) {
                int[] rows = new int[added];
                for (int offset = 0; offset < added; offset++) {
                    rows[offset] = computeWrapRowCount(first + offset, entry.getKey());
                }
                layout.rows.replace(first, change.removedLineCount(), rows);
            }
        }
    }

    private void trimMetricsCache() {
        var entries = metricsCache.entrySet().iterator();
        while (entries.hasNext()
            && (metricsCache.size() > maxMetricsLines
                || (metricsWeight > maxMetricsWeight && metricsCache.size() > 1))) {
            MetricsEntry removed = entries.next().getValue();
            metricsWeight -= removed.weight();
            entries.remove();
        }
    }

    private void clearAllCaches() {
        metricsCache.clear();
        metricsWeight = 0;
        clearLayoutCaches();
    }

    private void clearLayoutCaches() {
        visualWidths = null;
        wrapLayouts.clear();
        wrapStartsCount = 0;
        wrapStartsWeight = 0;
    }

    private int safeLineCount() {
        return Math.max(0, source.lineCount());
    }

    private CharSequence requireLine(int index) {
        return Objects.requireNonNull(source.lineSequence(index), "source line " + index);
    }

    int metricsCacheSize() {
        return metricsCache.size();
    }

    int metricsCacheWeight() {
        return metricsWeight;
    }

    int wrapStartsCacheSize() {
        return wrapStartsCount;
    }

    /** Retained index and metrics storage units, across every width and wrapping mode. */
    long wrapStartsCacheWeight() {
        return wrapStartsWeight;
    }

    int wrapPrefixCacheSize() {
        int size = 0;
        for (WrapLayout layout : wrapLayouts.values()) {
            if (layout.rows != null) {
                size++;
            }
        }
        return size;
    }

    int wrapLayoutCacheSize() {
        return wrapLayouts.size();
    }

    private record MetricsEntry(CharSequence lineText, long identity, int tabWidth, TerminalText.Metrics metrics) {
        private boolean matches(CharSequence text, int width) {
            return tabWidth == width && (lineText == text || lineText instanceof String && lineText.equals(text));
        }

        private int weight() {
            return Math.max(1, metrics.storageWeight());
        }
    }

    private static final class WrapLayout {
        private final Map<Integer, List<Integer>> wrapStarts = new LinkedHashMap<>(16, 0.75f, true);
        private LineLayoutIndex rows;
    }

    private record WrapLayoutKey(int width, boolean wrapByWords, int tabWidth) {
    }
}
