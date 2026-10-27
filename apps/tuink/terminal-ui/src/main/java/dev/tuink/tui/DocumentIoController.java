package dev.tuink.tui;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** One file operation in flight; only its immutable result crosses back to the owner. */
final class DocumentIoController implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final Executor executor;
    private final ExecutorService worker;
    private Pending<?> pending;
    private boolean closed;

    DocumentIoController() {
        this(Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "tuink-document-io");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    DocumentIoController(Executor executor) {
        this(executor, false);
    }

    private DocumentIoController(Executor executor, boolean owned) {
        this.executor = Objects.requireNonNull(executor, "executor");
        worker = owned ? (ExecutorService) executor : null;
    }

    <T> void submit(Callable<T> operation, Consumer<Completion<T>> completion) {
        requireOwner();
        if (closed || pending != null) throw new IllegalStateException("Document I/O is unavailable");
        Pending<T> next = new Pending<>(new FutureTask<>(operation), completion);
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

    boolean collect() {
        requireOwner();
        Pending<?> ready = pending;
        if (ready == null || !ready.task().isDone()) return false;
        pending = null;
        ready.publish();
        return true;
    }

    /** Complete started writes before recovery takes its final owner-thread snapshot. */
    @Override public void close() {
        requireOwner();
        if (closed) return;
        closed = true;
        boolean interrupted = false;
        try {
            while (pending != null) {
                try {
                    pending.task().get();
                } catch (InterruptedException stop) {
                    interrupted = true;
                    continue;
                } catch (ExecutionException failed) {
                    // collect publishes the original failure on the owner thread.
                }
                collect();
            }
        } finally {
            if (worker != null) worker.shutdown();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Document I/O belongs to the UI thread");
    }

    record Completion<T>(T value, IOException failure) {
        boolean succeeded() { return failure == null; }
    }

    private static final class Pending<T> implements Runnable {
        private final FutureTask<T> task;
        private final Consumer<Completion<T>> completion;
        private final AtomicInteger claim = new AtomicInteger();

        private Pending(FutureTask<T> task, Consumer<Completion<T>> completion) {
            this.task = task;
            this.completion = Objects.requireNonNull(completion, "completion");
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
