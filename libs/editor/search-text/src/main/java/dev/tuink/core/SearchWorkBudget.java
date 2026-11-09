package dev.tuink.core;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bounds the nonlinear work of wildcard matching across a complete interactive
 * action, including repeated scans and searches across documents. Linear literal
 * and wildcard-segment scans do not consume this budget.
 */
public final class SearchWorkBudget {
    // Compatibility boundary for existing Supplier callbacks. Search algorithms
    // receive the context explicitly rather than reading this binding in loops.
    private static final ThreadLocal<SearchOperationContext> CURRENT = new ThreadLocal<>();

    private SearchWorkBudget() {}

    /** Runs with a shared limit on automaton words beyond its linear first word. */
    public static <T> T run(long maximumWork, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return run(maximumWork, context -> action.get());
    }

    /** Supplies the operation context directly while preserving legacy callbacks. */
    public static <T> T run(long maximumWork, Function<SearchOperationContext, T> action) {
        Objects.requireNonNull(action, "action");
        SearchOperationContext previous = CURRENT.get();
        SearchOperationContext context = previous == null
            ? SearchOperationContext.bounded(maximumWork) : previous.child(maximumWork);
        try {
            CURRENT.set(context);
            return action.apply(context);
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    static SearchOperationContext current() {
        try {
            SearchOperationContext context = CURRENT.get();
            return context == null ? SearchOperationContext.unlimited() : context;
        } catch (OutOfMemoryError exhausted) {
            // An unbound thread's first lookup may allocate a ThreadLocal entry.
            throw SearchLimitException.memory();
        }
    }
}
