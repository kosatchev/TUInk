package dev.tuink.core;

import dev.tuink.editor.search.SearchOptions;

import java.util.Objects;

/**
 * One view's compiled query and bounded navigation state. Only the current line
 * retains a scanner, a recent result block and sparse accepted-match checkpoints.
 * Moving forward continues that scanner; moving outside the block restarts from
 * a canonical checkpoint, never from an arbitrary user offset. Backward scans
 * can repeat work after eviction; memory does not grow with the number of hits.
 */
final class SearchNavigation {
    static final int RESULT_CAPACITY = 256;
    static final int CHECKPOINT_CAPACITY = 64;
    private final TextModel model;
    private String query;
    private SearchOptions options;
    private SearchMatcher matcher;
    private long revision = Long.MIN_VALUE;
    private long epoch = Long.MIN_VALUE;
    private LineNavigation line;
    private long completedScanSteps;

    SearchNavigation(TextModel model) {
        this.model = model;
    }

    SearchMatcher configure(String query, SearchOptions options) {
        return configure(query, options, SearchWorkBudget.current());
    }

    SearchMatcher configure(String query, SearchOptions options, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        if (safe.matchMode() == null) safe = new SearchOptions(safe.caseInsensitive(), SearchOptions.MatchMode.SUBSTRING);
        if (matcher == null || !query.equals(this.query) || !safe.equals(this.options)) {
            SearchMatcher compiled = SearchMatcher.compile(query, safe, context);
            discardLine();
            this.query = query;
            this.options = safe;
            matcher = compiled;
        }
        return matcher;
    }

    void configureLike(SearchNavigation other) {
        if (matcher == null || !other.query.equals(query) || !other.options.equals(options)) {
            discardLine();
            query = other.query;
            options = other.options;
            matcher = other.matcher;
        }
    }

    SearchMatcher.Match firstAtOrAfter(int lineIndex, int from) {
        return firstAtOrAfter(lineIndex, from, SearchWorkBudget.current());
    }

    SearchMatcher.Match firstAtOrAfter(int lineIndex, int from, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        try {
            return line(lineIndex).firstAtOrAfter(from, context);
        } catch (SearchLimitException refused) {
            // An interrupted Shift-And word update cannot be resumed safely.
            discardLine();
            throw refused;
        } catch (OutOfMemoryError exhausted) {
            discardLine();
            throw SearchLimitException.memory();
        }
    }

    SearchMatcher.Match lastBefore(int lineIndex, int before) {
        return lastBefore(lineIndex, before, SearchWorkBudget.current());
    }

    SearchMatcher.Match lastBefore(int lineIndex, int before, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        try {
            return line(lineIndex).lastBefore(before, context);
        } catch (SearchLimitException refused) {
            discardLine();
            throw refused;
        } catch (OutOfMemoryError exhausted) {
            discardLine();
            throw SearchLimitException.memory();
        }
    }

    boolean matchesRange(int lineIndex, int start, int end) {
        return matchesRange(lineIndex, start, end, SearchWorkBudget.current());
    }

    boolean matchesRange(int lineIndex, int start, int end, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        if (start < 0 || end < start || end > model.lineLength(lineIndex)) return false;
        SearchMatcher.Match match = firstAtOrAfter(lineIndex, start, context);
        return match != null && match.startUtf16() == start && match.endUtf16() == end;
    }

    private LineNavigation line(int lineIndex) {
        CharSequence source = model.lineText(lineIndex);
        if (revision != model.revision() || epoch != model.contentEpoch()
            || line == null || line.index != lineIndex || line.source != source) {
            discardLine();
            line = new LineNavigation(lineIndex, source);
            revision = model.revision();
            epoch = model.contentEpoch();
        }
        return line;
    }

    private void discardLine() {
        if (line != null) completedScanSteps += line.scanSteps();
        line = null;
    }

    // Deterministic work/space observations for navigation regression tests.
    long scanSteps() {
        return completedScanSteps + (line == null ? 0 : line.scanSteps());
    }

    int retainedResults() { return line == null ? 0 : line.size; }
    int retainedCheckpoints() { return line == null ? 0 : line.checkpointCount; }

    private final class LineNavigation {
        private final int index;
        private final CharSequence source;
        private final int[] starts = new int[RESULT_CAPACITY];
        private final int[] ends = new int[RESULT_CAPACITY];
        private final int[] checkpointStarts = new int[CHECKPOINT_CAPACITY];
        private final int[] checkpointEnds = new int[CHECKPOINT_CAPACITY];
        private int checkpointCount;
        private long checkpointStride = 4096;
        private int head;
        private int size;
        private int coveredFrom;
        private boolean exhausted;
        private SearchMatcher.Cursor cursor;
        private long previousScanSteps;

        private LineNavigation(int index, CharSequence source) {
            this.index = index;
            this.source = source;
            cursor = matcher.cursor(source, 0);
        }

        private SearchMatcher.Match firstAtOrAfter(int from, SearchOperationContext context) {
            if (from < coveredFrom) restart(from);
            int cached = lowerBound(from);
            if (cached < size) return match(slot(cached));
            SearchMatcher.Match next;
            while ((next = advance(context)) != null) {
                if (next.startUtf16() >= from) return next;
            }
            return null;
        }

        private SearchMatcher.Match lastBefore(int before, SearchOperationContext context) {
            if (before <= 0) return null;
            if (before <= coveredFrom || (size > 0 && starts[head] >= before && coveredFrom > 0)) restart(before);
            // Scan at most one result beyond the limit. Retaining it also makes
            // a direction change continue without a second prefix traversal.
            while (!exhausted && (size == 0 || starts[slot(size - 1)] < before)) advance(context);
            int cached = lowerBound(before) - 1;
            return cached < 0 ? null : match(slot(cached));
        }

        private SearchMatcher.Match advance(SearchOperationContext context) {
            if (exhausted) return null;
            SearchMatcher.Match next = cursor.next(context);
            if (next == null) {
                exhausted = true;
                return null;
            }
            append(next.startUtf16(), next.endUtf16());
            checkpoint(next.startUtf16(), next.endUtf16());
            return next;
        }

        private void append(int start, int end) {
            if (size == RESULT_CAPACITY) {
                coveredFrom = ends[head];
                head = (head + 1) % RESULT_CAPACITY;
                size--;
            }
            int slot = slot(size++);
            starts[slot] = start;
            ends[slot] = end;
        }

        private void checkpoint(int start, int end) {
            int latest = checkpointCount == 0 ? 0 : checkpointEnds[checkpointCount - 1];
            if ((long) end - latest < checkpointStride) return;
            if (checkpointCount == CHECKPOINT_CAPACITY) {
                // Thin the sparse index rather than retaining one entry per
                // match or per fixed-size source interval indefinitely.
                for (int offset = 0; offset < CHECKPOINT_CAPACITY / 2; offset++) {
                    checkpointStarts[offset] = checkpointStarts[offset * 2];
                    checkpointEnds[offset] = checkpointEnds[offset * 2];
                }
                checkpointCount /= 2;
                checkpointStride *= 2;
            }
            checkpointStarts[checkpointCount] = start;
            checkpointEnds[checkpointCount++] = end;
        }

        private void restart(int from) {
            int checkpoint = checkpointCount - 1;
            while (checkpoint >= 0 && checkpointEnds[checkpoint] > from) checkpoint--;
            previousScanSteps += cursor.scannedCodePoints;
            // Release the old query-sized scanner before allocating another.
            cursor = null;
            coveredFrom = checkpoint < 0 ? 0 : checkpointEnds[checkpoint];
            head = 0;
            size = 0;
            exhausted = false;
            cursor = matcher.cursor(source, coveredFrom);
            if (checkpoint >= 0) append(checkpointStarts[checkpoint], checkpointEnds[checkpoint]);
        }

        private int slot(int offset) { return (head + offset) % RESULT_CAPACITY; }

        private int lowerBound(int start) {
            int low = 0;
            int high = size;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (starts[slot(middle)] < start) low = middle + 1;
                else high = middle;
            }
            return low;
        }

        private SearchMatcher.Match match(int slot) { return new SearchMatcher.Match(starts[slot], ends[slot]); }
        private long scanSteps() { return previousScanSteps + (cursor == null ? 0 : cursor.scannedCodePoints); }
    }
}
