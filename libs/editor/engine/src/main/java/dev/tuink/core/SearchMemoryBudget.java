package dev.tuink.core;

/** Additional heap admitted for compilation and query-sized matching state. */
final class SearchMemoryBudget {
    static final long FIXED_OVERHEAD = 128 * 1024L;

    private SearchMemoryBudget() {}

    static long additionalPeakLimit() {
        Runtime runtime = Runtime.getRuntime();
        long available = Math.max(0, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
        // The input, document and history are already included in used heap.
        // Leave half the remaining space for rendering, edits and error feedback.
        return available / 2;
    }

    static void requireCompilation(long bytes) {
        requireScratch(bytes);
        SearchWorkBudget budget = SearchWorkBudget.current();
        if (budget != null) budget.reserveCompilation(bytes);
    }

    static void requireScratch(long bytes) {
        if (bytes > additionalPeakLimit()) throw SearchLimitException.memory();
    }
}
