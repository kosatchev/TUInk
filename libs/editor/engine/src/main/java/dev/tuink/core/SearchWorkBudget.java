package dev.tuink.core;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounds the nonlinear work of wildcard matching across a complete interactive
 * action, including repeated scans and searches across documents. Linear literal
 * and wildcard-segment scans do not consume this budget.
 */
public final class SearchWorkBudget {
    private static final ScopedValue<SearchWorkBudget> CURRENT = ScopedValue.newInstance();
    private final SearchWorkBudget parent;
    private long remaining;
    private long remainingCompilationBytes;

    private SearchWorkBudget(long maximumWork, SearchWorkBudget parent) {
        remaining = maximumWork;
        this.parent = parent;
        remainingCompilationBytes = SearchMemoryBudget.additionalPeakLimit();
    }

    /** Runs with a shared limit on automaton words beyond its linear first word. */
    public static <T> T run(long maximumWork, Supplier<T> action) {
        if (maximumWork < 0) {
            throw new IllegalArgumentException("Search work limit must not be negative");
        }
        Objects.requireNonNull(action, "action");
        SearchWorkBudget budget = new SearchWorkBudget(maximumWork, current());
        return ScopedValue.where(CURRENT, budget).call(action::get);
    }

    static SearchWorkBudget current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    void consume(long work) {
        if (work > remaining) {
            throw new SearchLimitException();
        }
        if (parent != null) {
            parent.consume(work);
        }
        remaining -= work;
    }

    /** Retained and temporary compilation state across a complete action. */
    void reserveCompilation(long bytes) {
        if (bytes > remainingCompilationBytes) throw SearchLimitException.memory();
        if (parent != null) parent.reserveCompilation(bytes);
        remainingCompilationBytes -= bytes;
    }
}
