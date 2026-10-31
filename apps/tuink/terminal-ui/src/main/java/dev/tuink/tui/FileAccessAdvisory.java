package dev.tuink.tui;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** One background filesystem observation for the selected path; never authorizes a save. */
final class FileAccessAdvisory implements AutoCloseable {
    private static final long REFRESH_NANOS = 1_000_000_000L;
    private static final long COMPLETION_POLL_NANOS = 50_000_000L;
    private final Executor executor;
    private final ExecutorService worker;
    private final Predicate<Path> readOnly;
    private Path selectedPath;
    private Access access = Access.WRITABLE;
    private long generation;
    private long refreshAtNanos;
    private Pending pending;
    private boolean closed;

    FileAccessAdvisory() {
        this(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "tuink-file-access");
            thread.setDaemon(true);
            return thread;
        }), true, SaveTargetPolicy::isReadOnlyFile);
    }

    FileAccessAdvisory(Executor executor, boolean owned, Predicate<Path> readOnly) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.worker = owned ? (ExecutorService) executor : null;
        this.readOnly = Objects.requireNonNull(readOnly, "readOnly");
    }

    /** Polls completed work and schedules checks without waiting for filesystem access. */
    boolean refresh(Path path, long nowNanos) {
        if (closed) return false;
        Access before = access;
        if (!Objects.equals(selectedPath, path)) {
            selectedPath = path;
            generation++;
            access = path == null ? Access.WRITABLE : Access.UNKNOWN;
            refreshAtNanos = nowNanos;
        }
        if (pending != null && pending.task().isDone()) {
            Pending completed = pending;
            pending = null;
            Access observed;
            try {
                observed = completed.task().get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                observed = Access.UNKNOWN;
            } catch (ExecutionException failed) {
                observed = Access.UNKNOWN;
            }
            if (completed.generation() == generation) {
                access = observed;
                refreshAtNanos = nowNanos + REFRESH_NANOS;
            }
        }
        if (selectedPath != null && pending == null && nowNanos - refreshAtNanos >= 0L) {
            Path requested = selectedPath;
            FutureTask<Access> task = new FutureTask<>(() ->
                readOnly.test(requested) ? Access.READ_ONLY : Access.WRITABLE);
            pending = new Pending(generation, task);
            try {
                executor.execute(task);
            } catch (RuntimeException | OutOfMemoryError unavailable) {
                task.cancel(false);
                pending = null;
                access = Access.UNKNOWN;
                refreshAtNanos = nowNanos + REFRESH_NANOS;
            }
        }
        return access != before;
    }

    /** Rendering borrows only the last observation for this exact selected path. */
    String indicator(Path path) {
        return path == null ? Access.WRITABLE.indicator
            : Objects.equals(selectedPath, path) ? access.indicator : Access.UNKNOWN.indicator;
    }

    boolean isReadOnly(Path path) {
        return path != null && Objects.equals(selectedPath, path) && access == Access.READ_ONLY;
    }

    long nanosUntilRefresh(long nowNanos) {
        if (closed || selectedPath == null && pending == null) return -1L;
        if (pending != null) return pending.task().isDone() ? 0L : COMPLETION_POLL_NANOS;
        return Math.max(0L, refreshAtNanos - nowNanos);
    }

    /** A stalled stat/access must not hold terminal cleanup or retain a presentation callback. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (pending != null) pending.task().cancel(true);
        pending = null;
        selectedPath = null;
        if (worker != null) worker.shutdownNow();
    }

    private enum Access {
        UNKNOWN("??"), READ_ONLY("RO"), WRITABLE("RW");
        final String indicator;
        Access(String indicator) { this.indicator = indicator; }
    }

    private record Pending(long generation, FutureTask<Access> task) {}
}
