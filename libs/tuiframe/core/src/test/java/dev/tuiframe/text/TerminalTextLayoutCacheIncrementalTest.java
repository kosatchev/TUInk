package dev.tuiframe.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TerminalTextLayoutCacheIncrementalTest {
    @Test
    void oneCharacterEditAndShrinkingTheWidestLineDoNotRereadTheDocument() {
        MutableLineSource source = new MutableLineSource(Collections.nCopies(100_000, "abcdefg"));
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);
        int editedLine = 12_345;
        assertEquals(7, cache.maxVisualLineWidth(4));
        assertEquals(100_000, cache.totalWrappedRows(7, false, 4));

        source.replace(editedLine, 1, List.of("abcdefgX"));
        source.lineCalls = 0;

        assertEquals(8, cache.maxVisualLineWidth(4));
        assertEquals(100_001, cache.totalWrappedRows(7, false, 4));
        assertEquals(editedLine, cache.wrappedRowsBefore(editedLine, 7, false, 4));
        assertEquals(editedLine + 2, cache.wrappedRowsBefore(editedLine + 1, 7, false, 4));
        assertEquals(editedLine, cache.lineIndexFromWrappedOffset(editedLine + 1, 7, false, 4));
        assertEquals(editedLine + 1, cache.lineIndexFromWrappedOffset(editedLine + 2, 7, false, 4));
        assertEquals(List.of(0, 7), cache.wrapStarts(editedLine, 7, false, 4));
        assertTrue(source.lineCalls <= 12, "One changed line caused " + source.lineCalls + " source reads");

        source.replace(editedLine, 1, List.of("a"));
        source.lineCalls = 0;

        assertEquals(7, cache.maxVisualLineWidth(4));
        assertEquals(100_000, cache.totalWrappedRows(7, false, 4));
        assertEquals(editedLine + 1, cache.wrappedRowsBefore(editedLine + 1, 7, false, 4));
        assertEquals(editedLine + 1, cache.lineIndexFromWrappedOffset(editedLine + 1, 7, false, 4));
        assertEquals(List.of(0), cache.wrapStarts(editedLine, 7, false, 4));
        assertTrue(source.lineCalls <= 12, "Shrinking the maximum caused " + source.lineCalls + " source reads");
    }

    @Test
    void splitJoinInsertAndDeleteKeepWidthsAndWrappedCoordinatesCurrent() {
        MutableLineSource source = source("before", "longest original line", "中\t abc def", "tail", "last");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);
        assertMatchesFresh(cache, source, 4);

        source.replace(1, 1, List.of("split", "part"));
        assertMatchesFresh(cache, source, 4);

        source.replace(1, 2, List.of("joined\tparts"));
        assertMatchesFresh(cache, source, 4);

        source.replace(0, 0, List.of("start\t", "中"));
        assertMatchesFresh(cache, source, 4);

        source.replace(2, 2, List.of());
        assertMatchesFresh(cache, source, 4);

        source.replace(0, source.lineCount(), List.of());
        assertMatchesFresh(cache, source, 4);

        source.replace(0, 0, List.of("a\tb"));
        assertMatchesFresh(cache, source, 4);
    }

    @Test
    void unreadRevisionsUseOrderedChangeCoordinatesAndOnlyTheCurrentText() {
        MutableLineSource source = source("a", "middle", "last");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);
        assertMatchesFresh(cache, source, 4);

        source.replace(1, 1, List.of("temporary longest text that is subsequently deleted", "part"));
        source.replace(0, 0, List.of("prefix"));
        source.replace(2, 2, List.of());
        source.replace(1, 1, List.of("changed\tline", "next"));

        assertEquals(List.of("prefix", "changed\tline", "next", "last"), source.lines);
        assertMatchesFresh(cache, source, 4);

        source.replace(0, 2, List.of("final only"));
        source.replace(2, 1, List.of("end"));
        assertMatchesFresh(cache, source, 4);
    }

    @Test
    void tabWidthChangesRecomputeWidthsAndWrappingAfterKnownEdits() {
        MutableLineSource source = source("a\tb", "中\tz", "short");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);
        assertMatchesFresh(cache, source, 4);

        source.replace(1, 1, List.of("中中\tw"));
        assertMatchesFresh(cache, source, 8);
        assertMatchesFresh(cache, source, 2);

        source.replace(0, 1, List.of("\t\ta"));
        assertMatchesFresh(cache, source, 1);
        assertMatchesFresh(cache, source, 0);
        assertMatchesFresh(cache, source, 4);
    }

    @Test
    void missingHistoryFallsBackToCurrentContentsAndLaterKnownEditsStillWork() {
        MutableLineSource source = source("first", "widest original line", "中\ttail", "last");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source);
        assertMatchesFresh(cache, source, 4);

        source.historyAvailable = false;
        source.replace(1, 2, List.of("short"));
        source.replace(0, 0, List.of("new\tfirst"));
        assertMatchesFresh(cache, source, 4);

        source.historyAvailable = true;
        source.replace(2, 1, List.of("a much wider replacement", "中"));
        assertMatchesFresh(cache, source, 4);
    }

    @Test
    void batchedRandomEditsMatchFreshLayoutsWhenUnicodeMetricsAreEvicted() {
        MutableLineSource source = source("first", "中\twide", "a\u0301 text", "tail");
        TerminalTextLayoutCache cache = new TerminalTextLayoutCache(source, 3, 24, 4);
        List<String> texts = List.of("", "\tab", "中中", "a\u0301 text", "👩‍💻foo",
            "one two three", "1234567890", "tail");
        int[] tabWidths = {1, 2, 4, 8};
        Random random = new Random(0x51A7E);
        assertMatchesFresh(cache, source, 4);

        for (int step = 0; step < 120; step++) {
            int batchSize = 1 + random.nextInt(4);
            for (int mutation = 0; mutation < batchSize; mutation++) {
                int first = random.nextInt(source.lineCount() + 1);
                int removed = random.nextInt(Math.min(3, source.lineCount() - first) + 1);
                int added = source.lineCount() > 24 ? 0 : random.nextInt(4);
                List<String> replacement = new ArrayList<>();
                for (int line = 0; line < added; line++) {
                    replacement.add(texts.get(random.nextInt(texts.size())));
                }
                source.replace(first, removed, replacement);
            }

            assertMatchesFresh(cache, source, tabWidths[random.nextInt(tabWidths.length)]);
            assertTrue(cache.metricsCacheSize() <= 3, "Metrics line limit after step " + step);
            assertTrue(cache.metricsCacheWeight() <= 24 || cache.metricsCacheSize() <= 1,
                "Metrics weight limit after step " + step);
        }
    }

    private static void assertMatchesFresh(
        TerminalTextLayoutCache cache,
        MutableLineSource source,
        int tabWidth
    ) {
        TerminalTextLayoutCache fresh = new TerminalTextLayoutCache(new MutableLineSource(source.lines));
        assertEquals(fresh.maxVisualLineWidth(tabWidth), cache.maxVisualLineWidth(tabWidth), "Maximum width");
        for (int line = 0; line < source.lineCount(); line++) {
            assertEquals(source.lines.get(line), cache.metrics(line, tabWidth).text(), "Metrics text at " + line);
            assertEquals(fresh.metrics(line, tabWidth).visualWidth(), cache.metrics(line, tabWidth).visualWidth(),
                "Metrics width at " + line);
        }
        for (int width : new int[]{3, 7}) {
            for (boolean words : new boolean[]{false, true}) {
                int total = fresh.totalWrappedRows(width, words, tabWidth);
                assertEquals(total, cache.totalWrappedRows(width, words, tabWidth), "Wrapped row count");
                for (int line = -1; line <= source.lineCount() + 1; line++) {
                    assertEquals(fresh.wrapStarts(line, width, words, tabWidth),
                        cache.wrapStarts(line, width, words, tabWidth), "Wrap starts at " + line);
                    assertEquals(fresh.wrappedRowsBefore(line, width, words, tabWidth),
                        cache.wrappedRowsBefore(line, width, words, tabWidth), "Wrapped prefix at " + line);
                }
                for (int offset = -1; offset <= total + 1; offset++) {
                    assertEquals(fresh.lineIndexFromWrappedOffset(offset, width, words, tabWidth),
                        cache.lineIndexFromWrappedOffset(offset, width, words, tabWidth), "Wrapped lookup at " + offset);
                }
                assertEquals(fresh.lineIndexFromWrappedOffset(Integer.MAX_VALUE, width, words, tabWidth),
                    cache.lineIndexFromWrappedOffset(Integer.MAX_VALUE, width, words, tabWidth));
            }
        }
    }

    private static MutableLineSource source(String... lines) {
        return new MutableLineSource(List.of(lines));
    }

    private static final class MutableLineSource implements TerminalTextLayoutCache.LineSource {
        private final List<String> lines;
        private final List<TerminalTextLayoutCache.LineChange> changes = new ArrayList<>();
        private boolean historyAvailable = true;
        private int lineCalls;

        private MutableLineSource(List<String> lines) {
            this.lines = new ArrayList<>(lines);
        }

        @Override
        public long cacheIdentity() {
            return 1;
        }

        @Override
        public long revision() {
            return changes.size();
        }

        @Override
        public long contentEpoch() {
            return 0;
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

        @Override
        public List<TerminalTextLayoutCache.LineChange> changesSince(long earlierRevision) {
            if (!historyAvailable || earlierRevision < 0 || earlierRevision > changes.size()) {
                return null;
            }
            return List.copyOf(changes.subList((int) earlierRevision, changes.size()));
        }

        private void replace(int first, int removed, List<String> replacement) {
            if (removed == 0 && replacement.isEmpty()) {
                return;
            }
            lines.subList(first, first + removed).clear();
            lines.addAll(first, replacement);
            changes.add(new TerminalTextLayoutCache.LineChange(first, removed, replacement.size()));
        }
    }
}
