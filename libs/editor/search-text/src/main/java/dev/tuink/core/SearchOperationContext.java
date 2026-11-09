package dev.tuink.core;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Shared search limits for one synchronous operation, possibly spanning several
 * scans or documents. Confine a context and its children to one thread. Compiled
 * matchers and resumable cursors do not retain it between operations.
 */
public final class SearchOperationContext {
    private static final SearchOperationContext UNLIMITED =
        new SearchOperationContext(Long.MAX_VALUE, Long.MAX_VALUE, null);

    private final SearchOperationContext parent;
    private BooleanSupplier cancelled;
    private long remainingWork;
    private long remainingCompilationBytes;

    private SearchOperationContext(long maximumWork, long maximumCompilationBytes,
                                   SearchOperationContext parent) {
        if (maximumWork < 0 || maximumCompilationBytes < 0) {
            throw new IllegalArgumentException("Search limits must not be negative");
        }
        this.remainingWork = maximumWork;
        this.remainingCompilationBytes = maximumCompilationBytes;
        this.parent = parent;
    }

    /** Shares one work and compilation allowance across the complete operation. */
    public static SearchOperationContext bounded(long maximumWork) {
        return bounded(maximumWork, SearchMemoryBudget.additionalPeakLimit());
    }

    static SearchOperationContext bounded(long maximumWork, long maximumCompilationBytes) {
        return new SearchOperationContext(maximumWork, maximumCompilationBytes, null);
    }

    /**
     * Limits a nested part of this operation without resetting its parent's
     * allowances. Successful charges reduce every ancestor's allowance as well.
     */
    public SearchOperationContext child(long maximumWork) {
        return new SearchOperationContext(maximumWork, SearchMemoryBudget.additionalPeakLimit(), this);
    }

    /** A detached search may cooperatively stop without changing matching semantics. */
    public static SearchOperationContext cancellable(long maximumWork, BooleanSupplier cancelled) {
        SearchOperationContext result = bounded(maximumWork);
        result.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        return result;
    }

    public void checkpoint() {
        if (parent != null) parent.checkpoint();
        if (cancelled != null && (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()))
            throw new CancellationException("Search cancelled");
    }

    /** No aggregate operation limits; individual allocation admission still applies. */
    public static SearchOperationContext unlimited() { return UNLIMITED; }

    void consume(long work) {
        checkpoint();
        if (work < 0) throw new IllegalArgumentException("Search work must not be negative");
        if (this == UNLIMITED) return;
        if (work > remainingWork) throw new SearchLimitException();
        if (parent != null) parent.consume(work);
        remainingWork -= work;
    }

    void reserveCompilation(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Search bytes must not be negative");
        if (this == UNLIMITED) return;
        if (bytes > remainingCompilationBytes) throw SearchLimitException.memory();
        if (parent != null) parent.reserveCompilation(bytes);
        remainingCompilationBytes -= bytes;
    }
}
