package dev.tuink.tui;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** One file operation in flight; only its immutable result crosses back to the owner. */
final class DocumentIoController implements AutoCloseable {
    private static final long SHUTDOWN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final Thread owner = Thread.currentThread();
    private final Executor executor;
    private final ExecutorService worker;
    private Pending<?> pending;
    private boolean closed;
    private long nextOperation;

    enum Kind { READ, SAVE }

    /** Stable host-operation identity; equal counters from different hosts are not interchangeable. */
    static final class Operation {
        private final long sequence;
        private final Kind kind;
        Operation(long sequence, Kind kind) { this.sequence = sequence; this.kind = Objects.requireNonNull(kind); }
        long sequence() { return sequence; }
        Kind kind() { return kind; }
    }

    DocumentIoController() {
        this(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "tuink-document-io");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    DocumentIoController(Executor executor) {
        this(executor, false);
    }

    DocumentIoController(Executor executor, boolean owned) {
        this.executor = Objects.requireNonNull(executor, "executor");
        worker = owned ? (ExecutorService) executor : null;
    }

    /** Read-only work may be abandoned without waiting for its provider. */
    <T> void submit(Callable<T> operation, Consumer<Completion<T>> completion) {
        submit(operation, completion, Kind.READ, null);
    }

    /** Releases worker-owned results when cancellation revokes their publication rights. */
    <T> void submit(Callable<T> operation, Consumer<Completion<T>> completion, Consumer<T> abandonedResult) {
        submit(operation, completion, Kind.READ, Objects.requireNonNull(abandonedResult, "abandonedResult"));
    }

    /** Disposes a completed receipt on the same worker without blocking its UI owner. */
    <T> void dispose(T result, Consumer<T> disposal) {
        requireOwner();
        executor.execute(() -> disposal.accept(result));
    }

    /** Save work can be abandoned only after its session revokes publication rights. */
    <T> void submitSave(Callable<T> operation, Consumer<Completion<T>> completion) {
        submit(operation, completion, Kind.SAVE, null);
    }

    private <T> void submit(Callable<T> operation, Consumer<Completion<T>> completion, Kind kind,
        Consumer<T> abandonedResult) {
        requireOwner();
        if (closed || pending != null) throw new IllegalStateException("Document I/O is unavailable");
        Pending<T> next = new Pending<>(new Operation(Math.incrementExact(nextOperation), kind), operation, completion,
            abandonedResult, executor);
        nextOperation = next.operation.sequence();
        pending = next;
        try {
            executor.execute(next);
        } catch (RuntimeException | OutOfMemoryError failure) {
            // An executor can start a task before reporting a submission
            // failure. Preserve its real outcome once a worker has claimed it.
            if (next.rejectBeforeStart()) {
                pending = null;
                completion.accept(new Completion<>(next.operation, null, new IOException("Cannot start the file operation", failure)));
            }
        }
    }

    boolean busy() { requireOwner(); return pending != null; }

    Operation pendingOperation() { requireOwner(); return pending == null ? null : pending.operation; }

    /** Ready receipts win the deadline. A callback's replacement operation is never expired. */
    void expireSave(Operation operation) {
        requireOwner();
        Pending<?> expiring = pending;
        if (expiring == null || expiring.operation != operation || operation.kind() != Kind.SAVE) return;
        if (collect()) return;
        throw saveShutdownTimeout(expiring, new TimeoutException("Quit deadline expired"));
    }

    /** The caller must revoke admission of a late open result first. */
    boolean cancelRead() {
        requireOwner();
        Pending<?> abandoned = pending;
        if (abandoned == null || abandoned.kind != Kind.READ) return false;
        abandon(abandoned, true);
        return true;
    }

    /** Call only after the session confirms cancellation before publication. */
    boolean cancelSave() {
        requireOwner();
        Pending<?> abandoned = pending;
        if (abandoned == null || abandoned.kind != Kind.SAVE) return false;
        abandon(abandoned, true);
        return true;
    }

    private void abandon(Pending<?> abandoned, boolean interrupt) {
        pending = null;
        abandoned.abandon();
        abandoned.task().cancel(interrupt);
        if (worker instanceof ThreadPoolExecutor pool) pool.remove(abandoned);
    }

    boolean collect(Operation operation) {
        requireOwner();
        return pending != null && pending.operation == operation && collect();
    }

    boolean collect() {
        requireOwner();
        Pending<?> ready = pending;
        if (ready == null || !ready.task().isDone()) return false;
        pending = null;
        ready.publish();
        return true;
    }

    /** Collect confirmed saves, or fail shutdown so final recovery retains the draft. */
    @Override public void close() {
        close(SHUTDOWN_TIMEOUT_NANOS);
    }

    void close(long timeoutNanos) {
        requireOwner();
        if (timeoutNanos < 0) throw new IllegalArgumentException("Negative shutdown timeout");
        if (closed) return;
        closed = true;
        boolean interrupted = false;
        long started = System.nanoTime();
        try {
            if (pending != null && pending.kind == Kind.READ
                && (!pending.task().isDone() || pending.abandonedResult != null)) cancelRead();
            while (pending != null) {
                try {
                    pending.task().get(Math.max(0L, timeoutNanos - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
                } catch (InterruptedException stop) {
                    interrupted = true;
                    continue;
                } catch (ExecutionException failed) {
                    // collect publishes the original failure on the owner thread.
                } catch (TimeoutException unfinished) {
                    throw saveShutdownTimeout(pending, unfinished);
                }
                collect();
            }
        } finally {
            if (worker != null) worker.shutdown();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private IllegalStateException saveShutdownTimeout(Pending<?> unfinishedSave, TimeoutException unfinished) {
        // Publication may already have changed the target. Never interrupt it
        // or report cancellation/success; final recovery must keep the draft.
        closed = true;
        abandon(unfinishedSave, false);
        if (worker != null) worker.shutdown();
        return new IllegalStateException("Save outcome is unknown because shutdown timed out. "
            + "The draft is retained for recovery; verify the target file before retrying.", unfinished);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Document I/O belongs to the UI thread");
    }

    record Completion<T>(Operation operation, T value, IOException failure) {
        boolean succeeded() { return failure == null; }
    }

    private static final class Pending<T> implements Runnable {
        private final Operation operation;
        private final FutureTask<T> task;
        private Consumer<Completion<T>> completion;
        private final Kind kind;
        private final Consumer<T> abandonedResult;
        private final Executor executor;
        private final AtomicInteger claim = new AtomicInteger();
        private boolean abandoned;
        private boolean produced;
        private T producedValue;

        private Pending(Operation token, Callable<T> operation, Consumer<Completion<T>> completion,
            Consumer<T> abandonedResult, Executor executor) {
            this.operation = token;
            this.task = new FutureTask<>(() -> {
                T value = operation.call();
                boolean discard;
                synchronized (this) {
                    discard = abandoned;
                    if (!discard && abandonedResult != null) {
                        produced = true;
                        producedValue = value;
                    }
                }
                if (discard && abandonedResult != null) abandonedResult.accept(value);
                return value;
            });
            this.completion = Objects.requireNonNull(completion, "completion");
            this.kind = token.kind();
            this.abandonedResult = abandonedResult;
            this.executor = executor;
        }

        void abandon() {
            T disposal = null;
            boolean disposeProduced;
            synchronized (this) {
                abandoned = true;
                completion = null;
                disposeProduced = produced;
                if (disposeProduced) {
                    disposal = producedValue;
                    producedValue = null;
                    produced = false;
                }
            }
            if (disposeProduced) {
                T value = disposal;
                executor.execute(() -> abandonedResult.accept(value));
            }
        }

        FutureTask<T> task() { return task; }

        @Override public void run() {
            if (claim.compareAndSet(0, 1)) task.run();
        }

        boolean rejectBeforeStart() { return claim.compareAndSet(0, 2); }

        void publish() {
            Completion<T> result;
            try {
                result = new Completion<>(operation, task.get(), null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("A completed document task was interrupted", interrupted);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof Error error && !(error instanceof OutOfMemoryError)) throw error;
                result = new Completion<>(operation, null, cause instanceof IOException io ? io
                    : new IOException(cause instanceof OutOfMemoryError ? "Not enough memory for the file operation"
                        : "File operation failed: " + cause.getMessage(), cause));
            }
            synchronized (this) {
                producedValue = null;
                produced = false;
            }
            completion.accept(result);
        }
    }
}
