package dev.tuink.terminal.text;

import java.util.HashMap;
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
 * metrics are weight-bounded and wrap layouts retain a small access-order set
 * of recent width/word-wrap/tab-width combinations.</p>
 */
public final class TerminalTextLayoutCache {
    private static final int DEFAULT_MAX_METRICS_LINES = 200_000;
    private static final int DEFAULT_MAX_METRICS_WEIGHT = 8_388_608;
    private static final int DEFAULT_MAX_WRAP_LAYOUTS = 4;

    /** Versioned random-access source of non-null text lines. */
    public interface LineSource {
        /** Stable identity of the logical source; changing it resets every cache. */
        long cacheIdentity();

        /** Content revision, changed after every visible text or line mutation. */
        long revision();

        /** Generation changed for wholesale content replacement or source reset. */
        long contentEpoch();

        int lineCount();

        String line(int index);
    }

    private final LineSource source;
    private final int maxMetricsLines;
    private final int maxMetricsWeight;
    private final int maxWrapLayouts;
    private final Map<Integer, MetricsEntry> metricsCache =
        new LinkedHashMap<>(256, 0.75f, true);
    private final Map<WrapLayoutKey, WrapLayout> wrapLayouts;
    private int metricsWeight;
    private int cachedVisualWidthTabWidth = -1;
    private int cachedMaxVisualLineWidth;
    private boolean maxVisualWidthDirty = true;
    private boolean sourceObserved;
    private long observedCacheIdentity;
    private long observedRevision;
    private long observedContentEpoch;

    public TerminalTextLayoutCache(LineSource source) {
        this(
            source,
            DEFAULT_MAX_METRICS_LINES,
            DEFAULT_MAX_METRICS_WEIGHT,
            DEFAULT_MAX_WRAP_LAYOUTS
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
        this.source = Objects.requireNonNull(source, "source");
        if (maxMetricsLines <= 0) {
            throw new IllegalArgumentException("maxMetricsLines must be positive");
        }
        if (maxMetricsWeight <= 0) {
            throw new IllegalArgumentException("maxMetricsWeight must be positive");
        }
        if (maxWrapLayouts <= 0) {
            throw new IllegalArgumentException("maxWrapLayouts must be positive");
        }
        this.maxMetricsLines = maxMetricsLines;
        this.maxMetricsWeight = maxMetricsWeight;
        this.maxWrapLayouts = maxWrapLayouts;
        this.wrapLayouts = new LinkedHashMap<>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<WrapLayoutKey, WrapLayout> eldest) {
                return size() > TerminalTextLayoutCache.this.maxWrapLayouts;
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
        if (!maxVisualWidthDirty && cachedVisualWidthTabWidth == safeTabWidth) {
            return cachedMaxVisualLineWidth;
        }
        int max = 0;
        int count = safeLineCount();
        for (int line = 0; line < count; line++) {
            max = Math.max(max, metricsSynchronized(line, safeTabWidth).visualWidth());
        }
        cachedVisualWidthTabWidth = safeTabWidth;
        cachedMaxVisualLineWidth = max;
        maxVisualWidthDirty = false;
        return max;
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
        int[] prefix = wrapPrefixSynchronized(width, wrapByWords, tabWidth, count);
        int limit = Math.max(0, Math.min(lineIndexExclusive, count));
        return prefix[limit];
    }

    public int totalWrappedRows(int width, boolean wrapByWords, int tabWidth) {
        synchronizeSource();
        int count = safeLineCount();
        int[] prefix = wrapPrefixSynchronized(width, wrapByWords, tabWidth, count);
        return prefix[count];
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
        int[] prefix = wrapPrefixSynchronized(width, wrapByWords, tabWidth, count);
        int total = prefix[count];
        int target = Math.max(0, Math.min(wrappedOffset, Math.max(0, total - 1)));
        int low = 0;
        int high = Math.max(0, count - 1);
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int start = prefix[middle];
            int end = prefix[middle + 1];
            if (target < start) {
                high = middle - 1;
            } else if (target >= end) {
                low = middle + 1;
            } else {
                return middle;
            }
        }
        return Math.max(0, count - 1);
    }

    private TerminalText.Metrics metricsSynchronized(int line, int tabWidth) {
        int count = safeLineCount();
        if (count <= 0) {
            return TerminalText.metrics("", tabWidth);
        }
        int safeLine = Math.max(0, Math.min(line, count - 1));
        int safeTabWidth = Math.max(1, tabWidth);
        String text = requireLine(safeLine);
        MetricsEntry cached = metricsCache.get(safeLine);
        if (cached != null && cached.matches(text, safeTabWidth)) {
            return cached.metrics();
        }
        if (cached != null) {
            metricsCache.remove(safeLine);
            metricsWeight -= cached.weight();
        }

        TerminalText.Metrics metrics = TerminalText.metrics(text, safeTabWidth);
        MetricsEntry computed = new MetricsEntry(text, safeTabWidth, metrics);
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
        List<Integer> computed = TerminalLineLayout.wrapStarts(
            metricsSynchronized(safeLine, key.tabWidth()),
            key.width(),
            key.wrapByWords()
        );
        layout.wrapStarts.put(safeLine, computed);
        return computed;
    }

    private int[] wrapPrefixSynchronized(
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
        if (layout.prefix != null) {
            return layout.prefix;
        }
        int[] prefix = new int[lineCount + 1];
        for (int line = 0; line < lineCount; line++) {
            List<Integer> starts = layout.wrapStarts.get(line);
            if (starts == null) {
                starts = TerminalLineLayout.wrapStarts(
                    metricsSynchronized(line, key.tabWidth()),
                    key.width(),
                    key.wrapByWords()
                );
                layout.wrapStarts.put(line, starts);
            }
            prefix[line + 1] = prefix[line] + starts.size();
        }
        layout.prefix = prefix;
        return prefix;
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
        long cacheIdentity = source.cacheIdentity();
        long contentEpoch = source.contentEpoch();
        long revision = source.revision();
        if (!sourceObserved
            || observedCacheIdentity != cacheIdentity
            || observedContentEpoch != contentEpoch) {
            clearAllCaches();
        } else if (observedRevision != revision) {
            clearLayoutCaches();
        }
        sourceObserved = true;
        observedCacheIdentity = cacheIdentity;
        observedContentEpoch = contentEpoch;
        observedRevision = revision;
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
        maxVisualWidthDirty = true;
        wrapLayouts.clear();
    }

    private int safeLineCount() {
        return Math.max(0, source.lineCount());
    }

    private String requireLine(int index) {
        return Objects.requireNonNull(source.line(index), "source line " + index);
    }

    int metricsCacheSize() {
        return metricsCache.size();
    }

    int metricsCacheWeight() {
        return metricsWeight;
    }

    int wrapStartsCacheSize() {
        int size = 0;
        for (WrapLayout layout : wrapLayouts.values()) {
            size += layout.wrapStarts.size();
        }
        return size;
    }

    int wrapPrefixCacheSize() {
        int size = 0;
        for (WrapLayout layout : wrapLayouts.values()) {
            if (layout.prefix != null) {
                size++;
            }
        }
        return size;
    }

    int wrapLayoutCacheSize() {
        return wrapLayouts.size();
    }

    private record MetricsEntry(String lineText, int tabWidth, TerminalText.Metrics metrics) {
        private boolean matches(String text, int width) {
            return tabWidth == width && lineText.equals(text);
        }

        private int weight() {
            return Math.max(1, metrics.storageWeight());
        }
    }

    private static final class WrapLayout {
        private final Map<Integer, List<Integer>> wrapStarts = new HashMap<>();
        private int[] prefix;
    }

    private record WrapLayoutKey(int width, boolean wrapByWords, int tabWidth) {
    }
}
