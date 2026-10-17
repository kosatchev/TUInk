package dev.tuink.core;

/** Admission policy for building a replacement while retaining the source and undo history. */
final class DocumentEditBudget {
    // A String may widen to two bytes per code unit; all backing arrays use int indices.
    static final long MAX_LINE_CODE_UNITS = (Integer.MAX_VALUE - 8L) / 2;
    static final long MAX_LINES = Integer.MAX_VALUE - 8L;
    private static final long FIXED_OVERHEAD = 128 * 1024L;
    private final int sourceLines;
    private final long additionalPeakLimit;
    private long copiedCodeUnits;
    private long completedLines;

    DocumentEditBudget(int sourceLines, long additionalPeakLimit) {
        this.sourceLines = sourceLines;
        this.additionalPeakLimit = Math.max(0, additionalPeakLimit);
    }

    static DocumentEditBudget currentHeap(int sourceLines) {
        Runtime runtime = Runtime.getRuntime();
        long available = Math.max(0, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
        // Existing tabs/history already occupy used heap. Keep half the available
        // space for the UI, derived state and allocations not owned by this builder.
        return new DocumentEditBudget(sourceLines, available / 2);
    }

    DocumentEditBudget fresh() {
        return new DocumentEditBudget(sourceLines, additionalPeakLimit);
    }

    void append(long currentLength, boolean copied) {
        if (currentLength > MAX_LINE_CODE_UNITS) throw new DocumentEditLimitException();
        requireFits(add(copiedCodeUnits, copied ? currentLength : 0), completedLines + 1);
    }

    void finishLine(long length, boolean copied) {
        finishLines(copied ? length : 0, 1, length);
    }

    void finishLines(long copiedUnits, long count, long longestLine) {
        if (longestLine > MAX_LINE_CODE_UNITS || count > MAX_LINES - completedLines) {
            throw new DocumentEditLimitException();
        }
        completedLines += count;
        copiedCodeUnits = add(copiedCodeUnits, copiedUnits);
        requireFits(copiedCodeUnits, completedLines);
    }

    /**
     * A local replacement retains compact text deltas rather than document snapshots.
     * Unchanged text is not copied, but growing the buffer and rebuilding separator
     * lists can allocate reference arrays for the complete resulting document.
     */
    void requireLocalReplacement(long copiedUnits, long resultLines, long changedLines, long longestLine) {
        if (resultLines > MAX_LINES || longestLine > MAX_LINE_CODE_UNITS) {
            throw new DocumentEditLimitException();
        }
        long peak = add(FIXED_OVERHEAD, add(multiply(copiedUnits, 8),
            add(multiply(changedLines, 256),
                add(multiply(sourceLines, 16), multiply(resultLines, 32)))));
        if (peak > additionalPeakLimit) throw new DocumentEditLimitException();
    }

    void requireLineReplacements(long copiedUnits, long changedLines, long longestLine) {
        if (longestLine > MAX_LINE_CODE_UNITS) throw new DocumentEditLimitException();
        // Stage a sibling buffer before publishing any line. Compact undo deltas,
        // ranges, separator slices and history are also prepared before publication.
        long peak = add(FIXED_OVERHEAD, add(multiply(copiedUnits, 8),
            add(multiply(changedLines, 768), multiply(sourceLines, 32))));
        if (peak > additionalPeakLimit) throw new DocumentEditLimitException();
    }

    private void requireFits(long copiedUnits, long resultLines) {
        // Eight bytes per copied UTF-16 code unit cover finished Strings plus
        // pending StringBuilder growth/widening/toString. Untouched whole-line
        // references do not copy their text. Per-line space covers String/array
        // headers, builder lists, model validation/storage, metadata, published
        // snapshots, undo snapshots and gutter arrays; source snapshot refs are
        // accounted separately. These are additional allocations, not total heap.
        long peak = add(FIXED_OVERHEAD, add(multiply(copiedUnits, 8),
            add(multiply(resultLines, 256), multiply(sourceLines, 16))));
        if (peak > additionalPeakLimit) throw new DocumentEditLimitException();
    }

    static void requireTemporaryReferences(int count) {
        DocumentEditBudget budget = currentHeap(0);
        if (count > MAX_LINES || add(FIXED_OVERHEAD, multiply(count, 32)) > budget.additionalPeakLimit) {
            throw new DocumentEditLimitException();
        }
    }

    static long add(long left, long right) {
        if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) throw new DocumentEditLimitException();
        return left + right;
    }

    private static long multiply(long value, long factor) {
        if (value < 0 || value > Long.MAX_VALUE / factor) throw new DocumentEditLimitException();
        return value * factor;
    }
}
