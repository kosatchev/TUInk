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
    private boolean quitting;
    private long quitDeadlineNanos;

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
        submit(operation, completion, Kind.READ);
    }

    /** Save work can be abandoned only after its session revokes publication rights. */
    <T> void submitSave(Callable<T> operation, Consumer<Completion<T>> completion) {
        submit(operation, completion, Kind.SAVE);
    }

    private <T> void submit(Callable<T> operation, Consumer<Completion<T>> completion, Kind kind) {
        requireOwner();
        if (closed || pending != null) throw new IllegalStateException("Document I/O is unavailable");
        Pending<T> next = new Pending<>(new FutureTask<>(operation), completion, kind);
        pending = next;
        try {
            executor.execute(next);
        } catch (RuntimeException | OutOfMemoryError failure) {
            // An executor can start a task before reporting a submission
            // failure. Preserve its real outcome once a worker has claimed it.
            if (next.rejectBeforeStart()) {
                pending = null;
                completion.accept(new Completion<>(null, new IOException("Cannot start the file operation", failure)));
            }
        }
    }

    boolean busy() { requireOwner(); return pending != null; }

    /** Repeated quit requests keep the original deadline while publication is pending. */
    void beginQuitWait(long nowNanos) {
        requireOwner();
        if (!quitting && pending != null) {
            quitting = true;
            quitDeadlineNanos = nowNanos + SHUTDOWN_TIMEOUT_NANOS;
        }
    }

    /** Runs after collecting ready receipts, before another terminal input is read. */
    void checkQuitWait(long nowNanos) {
        requireOwner();
        if (quitting && pending != null && nowNanos - quitDeadlineNanos >= 0L && !collect()) {
            throw saveShutdownTimeout(new TimeoutException("Quit deadline expired"));
        }
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
        quitting = false;
        abandoned.completion = null;
        abandoned.task().cancel(interrupt);
        if (worker instanceof ThreadPoolExecutor pool) pool.remove(abandoned);
    }

    boolean collect() {
        requireOwner();
        Pending<?> ready = pending;
        if (ready == null || !ready.task().isDone()) return false;
        pending = null;
        quitting = false;
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
            if (pending != null && pending.kind == Kind.READ && !pending.task().isDone()) cancelRead();
            while (pending != null) {
                try {
                    pending.task().get(Math.max(0L, timeoutNanos - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
                } catch (InterruptedException stop) {
                    interrupted = true;
                    continue;
                } catch (ExecutionException failed) {
                    // collect publishes the original failure on the owner thread.
                } catch (TimeoutException unfinished) {
                    throw saveShutdownTimeout(unfinished);
                }
                collect();
            }
        } finally {
            if (worker != null) worker.shutdown();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private IllegalStateException saveShutdownTimeout(TimeoutException unfinished) {
        // Publication may already have changed the target. Never interrupt it
        // or report cancellation/success; final recovery must keep the draft.
        closed = true;
        abandon(pending, false);
        if (worker != null) worker.shutdown();
        return new IllegalStateException("Save outcome is unknown because shutdown timed out. "
            + "The draft is retained for recovery; verify the target file before retrying.", unfinished);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Document I/O belongs to the UI thread");
    }

    record Completion<T>(T value, IOException failure) {
        boolean succeeded() { return failure == null; }
    }

    private enum Kind { READ, SAVE }

    private static final class Pending<T> implements Runnable {
        private final FutureTask<T> task;
        private Consumer<Completion<T>> completion;
        private final Kind kind;
        private final AtomicInteger claim = new AtomicInteger();

        private Pending(FutureTask<T> task, Consumer<Completion<T>> completion, Kind kind) {
            this.task = task;
            this.completion = Objects.requireNonNull(completion, "completion");
            this.kind = kind;
        }

        FutureTask<T> task() { return task; }

        @Override public void run() {
            if (claim.compareAndSet(0, 1)) task.run();
        }

        boolean rejectBeforeStart() { return claim.compareAndSet(0, 2); }

        void publish() {
            Completion<T> result;
            try {
                result = new Completion<>(task.get(), null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("A completed document task was interrupted", interrupted);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof Error error && !(error instanceof OutOfMemoryError)) throw error;
                result = new Completion<>(null, cause instanceof IOException io ? io
                    : new IOException(cause instanceof OutOfMemoryError ? "Not enough memory for the file operation"
                        : "File operation failed: " + cause.getMessage(), cause));
            }
            completion.accept(result);
        }
    }
}
