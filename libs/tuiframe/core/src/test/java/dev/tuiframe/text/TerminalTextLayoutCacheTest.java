package dev.tuiframe.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalTextLayoutCacheTest {
    @Test
    void metricsReuseIdentityAndNormalizeLineAndTabWidth() {
        MutableLineSource source = source("a\t中", "plain");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);

        TerminalText.Metrics first = cache.metrics(0, 4);

        assertSame(first, cache.metrics(0, 4));
        assertSame(first, cache.metrics(-100, 4));
        assertEquals(6, first.visualWidth());

        TerminalText.Metrics widerTabs = cache.metrics(0, 8);
        assertNotSame(first, widerTabs);
        assertSame(widerTabs, cache.metrics(0, 8));
        assertEquals(10, widerTabs.visualWidth());

        TerminalText.Metrics tabOne = cache.metrics(0, 0);
        assertSame(tabOne, cache.metrics(0, 1));
        assertEquals(4, tabOne.visualWidth());

        TerminalText.Metrics last = cache.metrics(100, 4);
        assertEquals("plain", last.text());
        assertEquals(2, cache.metricsCacheSize());
    }

    @Test
    void ordinaryRevisionClearsLayoutsButPreservesValidLineMetrics() {
        MutableLineSource source = source("abcd", "中", "tail");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);
        TerminalText.Metrics changedBefore = cache.metrics(0, 4);
        TerminalText.Metrics unchangedBefore = cache.metrics(1, 4);
        List<Integer> wrapBefore = cache.wrapStarts(0, 2, false, 4);
        assertEquals(5, cache.totalWrappedRows(2, false, 4));
        assertTrue(cache.wrapStartsCacheSize() > 0);
        assertEquals(1, cache.wrapPrefixCacheSize());

        source.setLine(0, "abcdef");

        assertSame(unchangedBefore, cache.metrics(1, 4));
        assertEquals(0, cache.wrapStartsCacheSize());
        assertEquals(0, cache.wrapPrefixCacheSize());
        assertNotSame(changedBefore, cache.metrics(0, 4));
        List<Integer> wrapAfter = cache.wrapStarts(0, 2, false, 4);
        assertNotSame(wrapBefore, wrapAfter);
        assertEquals(List.of(0, 2, 4), wrapAfter);
        assertEquals(6, cache.totalWrappedRows(2, false, 4));
    }

    @Test
    void contentEpochAndSourceIdentityFullyResetMetricsAndWeights() {
        MutableLineSource source = source("a\tb", "中");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);
        TerminalText.Metrics first = cache.metrics(0, 4);
        cache.metrics(1, 4);
        cache.wrapStarts(0, 2, false, 4);
        assertTrue(cache.metricsCacheWeight() > 0);

        source.replaceAll(List.of("a\tb", "中"));

        TerminalText.Metrics afterEpoch = cache.metrics(0, 4);
        assertNotSame(first, afterEpoch);
        assertEquals(afterEpoch.storageWeight(), cache.metricsCacheWeight());
        assertEquals(0, cache.wrapStartsCacheSize());

        source.changeIdentity(77L);

        assertEquals(77L, cache.cacheIdentity());
        TerminalText.Metrics afterIdentity = cache.metrics(0, 4);
        assertNotSame(afterEpoch, afterIdentity);
        assertEquals(1, cache.metricsCacheSize());
    }

    @Test
    void wrapPrefixQueriesReuseOneLayoutAndMapOffsetsWithClamps() {
        MutableLineSource source = source("abcd", "x", "中a");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);

        List<Integer> firstLine = cache.wrapStarts(0, 2, false, 4);
        assertEquals(List.of(0, 2), firstLine);
        assertSame(firstLine, cache.wrapStarts(0, 2, false, 4));
        assertThrows(UnsupportedOperationException.class, () -> firstLine.add(3));

        assertEquals(5, cache.totalWrappedRows(2, false, 4));
        assertEquals(0, cache.wrappedRowsBefore(-10, 2, false, 4));
        assertEquals(2, cache.wrappedRowsBefore(1, 2, false, 4));
        assertEquals(5, cache.wrappedRowsBefore(100, 2, false, 4));
        assertEquals(0, cache.lineIndexFromWrappedOffset(-10, 2, false, 4));
        assertEquals(0, cache.lineIndexFromWrappedOffset(1, 2, false, 4));
        assertEquals(1, cache.lineIndexFromWrappedOffset(2, 2, false, 4));
        assertEquals(2, cache.lineIndexFromWrappedOffset(3, 2, false, 4));
        assertEquals(2, cache.lineIndexFromWrappedOffset(Integer.MAX_VALUE, 2, false, 4));

        int callsAfterPrefixBuild = source.lineCalls();
        cache.totalWrappedRows(2, false, 4);
        cache.wrappedRowsBefore(2, 2, false, 4);
        cache.lineIndexFromWrappedOffset(4, 2, false, 4);
        assertEquals(callsAfterPrefixBuild, source.lineCalls());
        assertEquals(1, cache.wrapStartsCacheSize(), "Prefix queries retain only explicitly requested starts");
        assertEquals(1, cache.wrapPrefixCacheSize());

        source.setLine(1, "wxyz");

        assertEquals(6, cache.totalWrappedRows(2, false, 4));
        assertTrue(source.lineCalls() > callsAfterPrefixBuild);
        assertEquals(0, cache.wrapStartsCacheSize());
        assertEquals(1, cache.wrapPrefixCacheSize());
    }

    @Test
    void maxVisualWidthUsesOneTabLayoutUntilRevisionOrTabWidthChanges() {
        MutableLineSource source = source("a\tb", "中中", "plain");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);

        assertEquals(5, cache.maxVisualLineWidth(4));
        int callsAfterFirstScan = source.lineCalls();
        assertEquals(5, cache.maxVisualLineWidth(4));
        assertEquals(callsAfterFirstScan, source.lineCalls());

        assertEquals(9, cache.maxVisualLineWidth(8));
        int callsAfterTabChange = source.lineCalls();
        assertTrue(callsAfterTabChange > callsAfterFirstScan);
        assertEquals(9, cache.maxVisualLineWidth(8));
        assertEquals(callsAfterTabChange, source.lineCalls());

        source.setLine(2, "longlonglong");

        assertEquals(12, cache.maxVisualLineWidth(4));
        assertTrue(source.lineCalls() > callsAfterTabChange);
    }

    @Test
    void metricsCacheUsesAccessOrderAndLineLimit() {
        MutableLineSource source = source("a", "b", "c");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source, 2, 100);
        TerminalText.Metrics first = cache.metrics(0, 4);
        TerminalText.Metrics eldest = cache.metrics(1, 4);

        assertSame(first, cache.metrics(0, 4));
        cache.metrics(2, 4);

        assertEquals(2, cache.metricsCacheSize());
        assertEquals(2, cache.metricsCacheWeight());
        assertSame(first, cache.metrics(0, 4));
        assertNotSame(eldest, cache.metrics(1, 4));
        assertEquals(2, cache.metricsCacheSize());
    }

    @Test
    void metricsCacheUsesWeightLimitAndRetainsOneOversizedEntry() {
        MutableLineSource oversizedSource = source("中", "a");
        TerminalTextLayoutCache oversizedCache = new TerminalTextLayoutCache(oversizedSource, 10, 3);

        TerminalText.Metrics oversized = oversizedCache.metrics(0, 4);
        assertEquals(4, oversized.storageWeight());
        assertEquals(1, oversizedCache.metricsCacheSize());
        assertEquals(4, oversizedCache.metricsCacheWeight());

        oversizedCache.metrics(1, 4);
        assertEquals(1, oversizedCache.metricsCacheSize());
        assertEquals(1, oversizedCache.metricsCacheWeight());

        MutableLineSource weightedSource = source("a\tb", "x", "y");
        TerminalTextLayoutCache weightedCache = new TerminalTextLayoutCache(weightedSource, 10, 5);
        TerminalText.Metrics tabbed = weightedCache.metrics(0, 4);
        assertEquals(4, tabbed.storageWeight());
        weightedCache.metrics(1, 4);
        assertEquals(5, weightedCache.metricsCacheWeight());
        weightedCache.metrics(2, 4);
        assertEquals(2, weightedCache.metricsCacheSize());
        assertEquals(2, weightedCache.metricsCacheWeight());
        assertNotSame(tabbed, weightedCache.metrics(0, 4));
    }

    @Test
    void wrapLayoutsUseAccessOrderAndStayBoundedAcrossResizes() {
        MutableLineSource source = source("abcdefgh", "中中中中", "tail");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source, 100, 10_000, 2);

        assertEquals(9, cache.totalWrappedRows(3, false, 4));
        assertEquals(5, cache.totalWrappedRows(4, false, 4));
        assertEquals(2, cache.wrapLayoutCacheSize());
        assertEquals(2, cache.wrapPrefixCacheSize());

        int callsAfterWarmup = source.lineCalls();
        assertEquals(9, cache.totalWrappedRows(3, false, 4));
        assertEquals(callsAfterWarmup, source.lineCalls());

        assertEquals(5, cache.totalWrappedRows(5, false, 4));
        assertEquals(2, cache.wrapLayoutCacheSize());
        assertEquals(2, cache.wrapPrefixCacheSize());

        int callsAfterEviction = source.lineCalls();
        assertEquals(5, cache.totalWrappedRows(4, false, 4));
        assertTrue(source.lineCalls() > callsAfterEviction);
        assertEquals(2, cache.wrapLayoutCacheSize());
        assertEquals(2, cache.wrapPrefixCacheSize());
    }

    @Test
    void offscreenRowIndicesDoNotRetainExpandedWrapStartsAcrossResizes() {
        MutableLineSource source = new MutableLineSource(Collections.nCopies(20_000, "word"));
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source, 2, 16, 4, 4, 8);

        for (int width = 80; width < 85; width++) {
            assertEquals(20_000, cache.totalWrappedRows(width, false, 4));
            assertEquals(0, cache.wrapStartsCacheSize());
            assertEquals(0, cache.wrapStartsCacheWeight());
            assertTrue(cache.wrapLayoutCacheSize() <= 4);
            assertTrue(cache.metricsCacheSize() <= 2);
        }
        List<Integer> visible = cache.wrapStarts(10_000, 84, false, 4);
        assertEquals(List.of(0), visible);
        assertSame(visible, cache.wrapStarts(10_000, 84, false, 4));
        assertEquals(1, cache.wrapStartsCacheSize());
    }

    @Test
    void explicitWrapStartsShareOneEntryAndOffsetBudgetAcrossLayouts() {
        MutableLineSource source = source("abcdefgh", "ijklmnop", "qrstuvwx");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source, 10, 100, 4, 3, 6);
        List<Integer> first = cache.wrapStarts(0, 4, false, 4);
        List<Integer> eldest = cache.wrapStarts(1, 4, false, 4);
        cache.wrapStarts(0, 5, false, 4);
        assertEquals(3, cache.wrapStartsCacheSize());
        assertEquals(6, cache.wrapStartsCacheWeight());

        assertSame(first, cache.wrapStarts(0, 4, false, 4));
        cache.wrapStarts(1, 5, false, 4);

        assertEquals(3, cache.wrapStartsCacheSize());
        assertEquals(6, cache.wrapStartsCacheWeight());
        assertSame(first, cache.wrapStarts(0, 4, false, 4));
        assertNotSame(eldest, cache.wrapStarts(1, 4, false, 4));
        assertTrue(cache.wrapStartsCacheSize() <= 3);
        assertTrue(cache.wrapStartsCacheWeight() <= 6);

        List<Integer> oversized = cache.wrapStarts(2, 1, false, 4);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), oversized);
        assertNotSame(oversized, cache.wrapStarts(2, 1, false, 4));
        assertTrue(cache.wrapStartsCacheSize() <= 3);
        assertTrue(cache.wrapStartsCacheWeight() <= 6);
    }

    @Test
    void aSparseLongLineReusesItsIndexWithinTheRetainedStorageBudget() {
        MutableLineSource source = source("a".repeat(1_000_000));
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source, 10, 100, 4, 3, 5000);
        List<Integer> starts = cache.wrapStarts(0, 1, false, 4);

        assertEquals(1_000_000, starts.size());
        assertEquals(1, cache.wrapStartsCacheSize());
        assertTrue(cache.wrapStartsCacheWeight() <= 5000);
        assertSame(starts, cache.wrapStarts(0, 1, false, 4));
        assertEquals(800_000, starts.get(800_000).intValue());
        long weight = cache.wrapStartsCacheWeight();
        assertSame(starts, cache.wrapStarts(0, 1, false, 4));
        assertEquals(weight, cache.wrapStartsCacheWeight());

        source.setLine(0, "changed");
        assertEquals(List.of(0), cache.wrapStarts(0, 80, false, 4));
        assertEquals(1, cache.wrapStartsCacheSize());
        assertEquals(1, cache.wrapStartsCacheWeight());
    }

    @Test
    void layoutEvictionAndRevisionResetReleaseTheWrapStartBudget() {
        MutableLineSource source = source("abcdefgh");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source, 10, 100, 2, 10, 100);
        cache.wrapStarts(0, 3, false, 4);
        cache.wrapStarts(0, 4, false, 4);
        assertEquals(5, cache.wrapStartsCacheWeight());

        cache.wrapStarts(0, 5, false, 4);
        assertEquals(2, cache.wrapStartsCacheSize());
        assertEquals(4, cache.wrapStartsCacheWeight());

        source.setLine(0, "changed");
        cache.metrics(0, 4);
        assertEquals(0, cache.wrapStartsCacheSize());
        assertEquals(0, cache.wrapStartsCacheWeight());
    }

    @Test
    void wrapStartEntryAndOffsetLimitsApplyIndependently() {
        MutableLineSource source = source("abcdefgh", "ijklmnop", "qrstuvwx");
        TerminalTextLayoutCache entryLimited = new TerminalTextLayoutCache(source, 10, 100, 4, 2, 100);
        TerminalTextLayoutCache offsetLimited = new TerminalTextLayoutCache(source, 10, 100, 4, 10, 3);
        for (int line = 0; line < 3; line++) {
            entryLimited.wrapStarts(line, 4, false, 4);
            offsetLimited.wrapStarts(line, 4, false, 4);
        }
        assertEquals(2, entryLimited.wrapStartsCacheSize());
        assertEquals(4, entryLimited.wrapStartsCacheWeight());
        assertEquals(1, offsetLimited.wrapStartsCacheSize());
        assertEquals(2, offsetLimited.wrapStartsCacheWeight());
    }

    @Test
    void emptySourcePreservesEmptyModelClampsWithoutRetainingEntries() {
        MutableLineSource source = source();
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);

        TerminalText.Metrics first = cache.metrics(100, 4);
        TerminalText.Metrics second = cache.metrics(-100, 4);
        assertEquals("", first.text());
        assertNotSame(first, second);
        assertEquals(List.of(0), cache.wrapStarts(100, 10, false, 4));
        assertEquals(0, cache.maxVisualLineWidth(4));
        assertEquals(0, cache.wrappedRowsBefore(100, 10, false, 4));
        assertEquals(0, cache.totalWrappedRows(10, false, 4));
        assertEquals(0, cache.lineIndexFromWrappedOffset(100, 10, false, 4));
        assertEquals(0, cache.metricsCacheSize());
        assertEquals(0, cache.wrapStartsCacheSize());
    }

    @Test
    void constructorAndLineSourceContractsAreValidated() {
        MutableLineSource source = source("line");
        assertThrows(NullPointerException.class, () -> new TerminalTextLayoutCache(null));
        assertThrows(IllegalArgumentException.class, () -> new TerminalTextLayoutCache(source, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TerminalTextLayoutCache(source, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TerminalTextLayoutCache(source, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TerminalTextLayoutCache(source, 1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TerminalTextLayoutCache(source, 1, 1, 1, 1, 0));

        source.setLineWithoutRevision(0, null);
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);
        assertThrows(NullPointerException.class, () -> cache.metrics(0, 4));
    }

    private static MutableLineSource source(String... lines) {
        return new MutableLineSource(List.of(lines));
    }

    private static final class MutableLineSource implements TerminalTextLayoutCache.LineSource {
        private final List<String> lines;
        private long identity = 1L;
        private long revision;
        private long contentEpoch;
        private int lineCalls;

        private MutableLineSource(List<String> lines) {
            this.lines = new ArrayList<>(lines);
        }

        @Override
        public long cacheIdentity() {
            return identity;
        }

        @Override
        public long revision() {
            return revision;
        }

        @Override
        public long contentEpoch() {
            return contentEpoch;
        }

        @Override
        public int lineCount() {
            return lines.size();
        }

        @Override
        public String line(int index) {
            lineCalls++;
            return lines.get(index);
        }

        private int lineCalls() {
            return lineCalls;
        }

        private void setLine(int index, String text) {
            lines.set(index, text);
            revision++;
        }

        private void setLineWithoutRevision(int index, String text) {
            lines.set(index, text);
        }

        private void replaceAll(List<String> replacement) {
            lines.clear();
            lines.addAll(replacement);
            revision++;
            contentEpoch++;
        }

        private void changeIdentity(long nextIdentity) {
            identity = nextIdentity;
        }
    }
}
