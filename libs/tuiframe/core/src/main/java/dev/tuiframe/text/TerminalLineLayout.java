package dev.tuiframe.text;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/** Stateless terminal-line wrapping over precomputed grapheme metrics. */
public final class TerminalLineLayout {
    private static final int MAX_CHECKPOINTS = 4096;
    private static final int PAGE_SIZE = 256;

    private TerminalLineLayout() {
    }

    /**
     * Returns UTF-16 offsets at which visual rows start.
     *
     * <p>Tab stops remain absolute to the complete line, and no returned
     * offset splits an extended grapheme cluster.</p>
     */
    public static List<Integer> wrapStarts(
        TerminalText.Metrics metrics,
        int maxVisualWidth,
        boolean wrapByWords
    ) {
        return wrapStarts(metrics, maxVisualWidth, wrapByWords, false);
    }

    /** Includes a final empty row when the last segment has no space for an insertion cursor. */
    public static List<Integer> wrapStarts(
        TerminalText.Metrics metrics, int maxVisualWidth, boolean wrapByWords, boolean reserveLineEndCell
    ) {
        WrapSummary summary = wrapSummary(metrics, maxVisualWidth, wrapByWords);
        boolean appendEnd = reserveLineEndCell && maxVisualWidth > 0 && !metrics.text().isEmpty()
            && metrics.visualWidth() - metrics.visualColumnAt(summary.lastStartOffset()) >= maxVisualWidth;
        List<Integer> starts;
        if (summary.rowCount() <= MAX_CHECKPOINTS) {
            List<Integer> materialized = new ArrayList<>(summary.rowCount());
            int boundary = 0;
            for (int row = 0; row < summary.rowCount(); row++) {
                materialized.add(offset(metrics, boundary));
                if (row + 1 < summary.rowCount()) {
                    boundary = nextStart(metrics, boundary, maxVisualWidth, wrapByWords);
                }
            }
            starts = List.copyOf(materialized);
        } else {
            starts = new SparseStarts(metrics, maxVisualWidth, wrapByWords, summary.rowCount());
        }
        return appendEnd ? new WithLineEnd(starts, metrics.text().length()) : starts;
    }

    /** Row geometry without materializing the UTF-16 start offset of every row. */
    public static WrapSummary wrapSummary(TerminalText.Metrics metrics, int maxVisualWidth, boolean wrapByWords) {
        Objects.requireNonNull(metrics, "metrics");
        if (metrics.text().isEmpty() || maxVisualWidth <= 0) {
            return new WrapSummary(1, 0);
        }
        int rows = 1;
        int boundary = 0;
        while (true) {
            int next = nextStart(metrics, boundary, maxVisualWidth, wrapByWords);
            if (next < 0) {
                return new WrapSummary(rows, offset(metrics, boundary));
            }
            rows++;
            boundary = next;
        }
    }

    public record WrapSummary(int rowCount, int lastStartOffset) {}

    /** Counts retained integer slots, including metrics retained by a sparse view. */
    static int retainedWeight(List<Integer> starts) {
        if (starts instanceof SparseStarts sparse) {
            return (int) Math.min(Integer.MAX_VALUE,
                (long) sparse.metrics.storageWeight() + sparse.checkpoints.length + sparse.page.length);
        }
        if (starts instanceof WithLineEnd appended) {
            return (int) Math.min(Integer.MAX_VALUE, (long) retainedWeight(appended.starts) + 1);
        }
        return starts.size();
    }

    private static int offset(TerminalText.Metrics metrics, int boundary) {
        return boundary == metrics.graphemeCount() ? metrics.text().length() : metrics.start(boundary);
    }

    /** A row boundary is sufficient to resume: tabs retain their absolute columns. */
    private static int nextStart(TerminalText.Metrics metrics, int start, int maxVisualWidth, boolean wrapByWords) {
        int startVisual = metrics.visualColumnAtBoundary(start);
        int lastWhitespaceEndIndex = -1;
        for (int index = start; index < metrics.graphemeCount(); index++) {
            int endVisual = metrics.visualColumnAtBoundary(index + 1);
            if (endVisual - startVisual > maxVisualWidth && index > start) {
                return wrapByWords && lastWhitespaceEndIndex > start
                    ? lastWhitespaceEndIndex
                    : index;
            }
            if (metrics.isWhitespace(index)) {
                lastWhitespaceEndIndex = index + 1;
            }
            if (endVisual - startVisual > maxVisualWidth && index == start) {
                return index + 1 < metrics.graphemeCount() ? index + 1 : -1;
            }
        }
        return -1;
    }

    /**
     * Immutable offsets with a fixed-size lookup cache. Synchronization protects
     * the cache when an otherwise immutable layout is read from another thread.
     */
    private static final class SparseStarts extends AbstractList<Integer> implements RandomAccess {
        private final TerminalText.Metrics metrics;
        private final int width;
        private final boolean words;
        private final int rowCount;
        private final int stride;
        private final int[] checkpoints;
        private final int[] page = new int[PAGE_SIZE];
        private int pageStart = -1;
        private int pageLength;

        private SparseStarts(TerminalText.Metrics metrics, int width, boolean words, int rowCount) {
            this.metrics = metrics;
            this.width = width;
            this.words = words;
            this.rowCount = rowCount;
            stride = (int) (((long) rowCount + MAX_CHECKPOINTS - 1) / MAX_CHECKPOINTS);
            checkpoints = new int[(rowCount - 1) / stride + 1];
            int boundary = 0;
            for (int row = 0; row < rowCount; row++) {
                if (row % stride == 0) {
                    checkpoints[row / stride] = boundary;
                }
                if (row + 1 < rowCount) {
                    boundary = nextStart(metrics, boundary, width, words);
                }
            }
        }

        @Override
        public int size() {
            return rowCount;
        }

        @Override
        public synchronized Integer get(int index) {
            Objects.checkIndex(index, rowCount);
            if (index < pageStart || index >= pageStart + pageLength) {
                int first = index / PAGE_SIZE * PAGE_SIZE;
                int checkpoint = first / stride;
                int row = checkpoint * stride;
                int boundary = checkpoints[checkpoint];
                while (row < first) {
                    boundary = nextStart(metrics, boundary, width, words);
                    row++;
                }
                pageLength = Math.min(PAGE_SIZE, rowCount - first);
                for (int entry = 0; entry < pageLength; entry++) {
                    page[entry] = offset(metrics, boundary);
                    if (entry + 1 < pageLength) {
                        boundary = nextStart(metrics, boundary, width, words);
                    }
                }
                pageStart = first;
            }
            return page[index - pageStart];
        }
    }

    /** Appends one terminal-neutral insertion position without copying its source list. */
    private static final class WithLineEnd extends AbstractList<Integer> implements RandomAccess {
        private final List<Integer> starts;
        private final int end;

        private WithLineEnd(List<Integer> starts, int end) {
            this.starts = starts;
            this.end = end;
        }

        @Override
        public int size() {
            return starts.size() + 1;
        }

        @Override
        public Integer get(int index) {
            Objects.checkIndex(index, size());
            return index == starts.size() ? end : starts.get(index);
        }
    }
}
