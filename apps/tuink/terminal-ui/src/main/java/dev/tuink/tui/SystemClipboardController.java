package dev.tuink.tui;

import dev.tuink.core.EditorClipboard;
import dev.tuink.editor.clipboard.ClipboardPort;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owner-thread clipboard publications and an ordered input barrier around asynchronous paste reads. */
final class SystemClipboardController implements AutoCloseable {
    private static final int MAX_DEFERRED_INPUTS = 256;
    private static final long CLOSE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
    private final Thread owner = Thread.currentThread();
    private final EditorClipboard clipboard;
    private final ClipboardPort port;
    private final Runnable wakeup;
    private final Consumer<String> warning;
    private final Executor executor;
    private final ExecutorService worker;
    private final ArrayDeque<Runnable> inputs = new ArrayDeque<>();
    private long observedRevision;
    private String latestWrite;
    private Operation active;
    private Paste paste;
    private boolean collecting;
    private boolean replaying;
    private boolean replayedSinceFrame;
    private boolean applying;
    private boolean closing;
    private boolean closed;

    SystemClipboardController(EditorClipboard clipboard, ClipboardPort port, Runnable wakeup, Consumer<String> warning) {
        this(clipboard, port, wakeup, warning, new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1), task -> {
                var thread = new Thread(task, "tuink-system-clipboard");
                thread.setDaemon(true);
                return thread;
            }), true);
    }

    SystemClipboardController(EditorClipboard clipboard, ClipboardPort port, Runnable wakeup,
        Consumer<String> warning, Executor executor) {
        this(clipboard, port, wakeup, warning, executor, false);
    }

    private SystemClipboardController(EditorClipboard clipboard, ClipboardPort port, Runnable wakeup,
        Consumer<String> warning, Executor executor, boolean owned) {
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        this.port = port;
        this.wakeup = Objects.requireNonNull(wakeup, "wakeup");
        this.warning = Objects.requireNonNull(warning, "warning");
        this.executor = Objects.requireNonNull(executor, "executor");
        worker = owned ? (ExecutorService) executor : null;
        observedRevision = clipboard.revision();
    }

    /** True means the caller must not execute this input again. Replay itself bypasses this gate. */
    boolean deferInput(Runnable input) {
        requireOwner();
        Objects.requireNonNull(input, "input");
        if (closed || replaying || applying || paste == null && inputs.isEmpty()) return false;
        if (inputs.size() == MAX_DEFERRED_INPUTS) {
            if (paste != null) {
                finishPaste(paste, Optional.empty(),
                    "System clipboard is slow; pasting from the internal clipboard");
            }
            replayOne();
            if (inputs.size() == MAX_DEFERRED_INPUTS)
                throw new IllegalStateException("Paste application must not start another deferred paste");
        }
        inputs.addLast(input);
        return true;
    }

    /** The pinned target is validated on the owner before either import or paste application. */
    void paste(BooleanSupplier stillCurrent, Runnable apply) {
        requireOwner();
        Objects.requireNonNull(stillCurrent, "stillCurrent");
        Objects.requireNonNull(apply, "apply");
        if (closed) return;
        if (paste != null) {
            deferInput(() -> paste(stillCurrent, apply));
            return;
        }
        paste = new Paste(new Object(), stillCurrent, apply);
        if (port == null) finishPaste(paste, Optional.empty(), null);
        else {
            observePublication();
            startNext();
        }
    }

    /** Coalesces accepted local publications; identical text with a new revision is still a publication. */
    void synchronize() {
        requireOwner();
        if (closed) return;
        observePublication();
        startNext();
    }

    /** Publishes at most one receipt and replays at most one event, allowing a frame between input owners. */
    void collect() {
        requireOwner();
        if (closed || collecting || replaying || applying) return;
        collecting = true;
        try {
            observePublication();
            if (active != null && active.isDone()) {
                var ready = active;
                active = null;
                publish(ready);
            }
            replayOne();
            observePublication();
            startNext();
        } finally {
            collecting = false;
        }
    }

    /** The host publishes new placements between replayed events, even if the next event is already queued. */
    void frameEnded() {
        requireOwner();
        replayedSinceFrame = false;
    }

    boolean busy() {
        requireOwner();
        return active != null || latestWrite != null || paste != null || !inputs.isEmpty();
    }

    private void observePublication() {
        long revision = clipboard.revision();
        if (revision == observedRevision) return;
        observedRevision = revision;
        if (port != null) latestWrite = clipboard.text();
    }

    private void startNext() {
        if (port == null || active != null || closed && !closing) return;
        ClipboardPort backend = port;
        Callable<Receipt> task;
        Object reading = null;
        if (latestWrite != null) {
            String text = latestWrite;
            latestWrite = null;
            task = () -> new Receipt(Optional.empty(), backend.writeText(text));
        } else if (paste != null && !closing) {
            reading = paste.token;
            task = () -> new Receipt(Objects.requireNonNull(backend.readText(), "Clipboard read result"), true);
        } else return;
        active = new Operation(reading, task, wakeup);
        try {
            executor.execute(active);
        } catch (RuntimeException failure) {
            active.rejectBeforeStart(failure);
        }
    }

    private void publish(Operation operation) {
        // An overflow fallback or disposal revoked this read's right to import or apply anything.
        if (operation.reading != null && (paste == null || operation.reading != paste.token)) return;
        try {
            Receipt result = operation.get();
            if (operation.reading == null) {
                if (!result.written) warning.accept("System clipboard write failed; the internal clipboard is retained");
            } else {
                finishPaste(paste, result.text, result.text.isEmpty()
                    ? "System clipboard is unavailable; pasting from the internal clipboard" : null);
            }
        } catch (ExecutionException | CancellationException failure) {
            failed(operation);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failed(operation);
        }
    }

    private void failed(Operation operation) {
        if (operation.reading == null)
            warning.accept("System clipboard write failed; the internal clipboard is retained");
        else {
            finishPaste(paste, Optional.empty(),
                "System clipboard read failed; pasting from the internal clipboard");
        }
    }

    private void finishPaste(Paste request, Optional<String> external, String failureNotice) {
        if (paste != request) return;
        paste = null;
        boolean current;
        try {
            current = request.stillCurrent.getAsBoolean();
        } catch (RuntimeException unavailable) {
            warning.accept("The paste target is no longer available");
            return;
        }
        if (!current) return;
        if (external.isPresent()) {
            clipboard.setText(external.get());
            // Import is not a local Copy publication: never echo it back to the system clipboard.
            observedRevision = clipboard.revision();
        }
        applying = true;
        try {
            request.apply.run();
        } finally {
            applying = false;
        }
        if (failureNotice != null) warning.accept(failureNotice);
    }

    private void replayOne() {
        if (paste != null || inputs.isEmpty() || replaying || applying || replayedSinceFrame) return;
        Runnable input = inputs.removeFirst();
        replaying = true;
        replayedSinceFrame = true;
        try {
            input.run();
        } finally {
            replaying = false;
            // Even a no-op input needs a prepared frame to release the replay fence.
            wakeup.run();
        }
    }

    @Override public void close() { close(CLOSE_TIMEOUT_NANOS); }

    /** A short injectable deadline lets tests exercise uncooperative executors without a real wait. */
    void close(long timeoutNanos) {
        requireOwner();
        if (timeoutNanos < 0) throw new IllegalArgumentException("Negative clipboard shutdown timeout");
        if (closed) return;
        observePublication();
        closed = true;
        closing = true;
        paste = null;
        inputs.clear();
        long started = System.nanoTime();
        long budget = Math.min(timeoutNanos, CLOSE_TIMEOUT_NANOS);
        try {
            while (active != null || latestWrite != null) {
                startNext();
                if (active == null) break;
                try {
                    active.get(Math.max(0L, budget - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
                } catch (ExecutionException | CancellationException failed) {
                    // The ready receipt is reported through the same owner-thread publication below.
                } catch (TimeoutException unfinished) {
                    warning.accept("System clipboard did not finish before exit; the internal clipboard is retained");
                    break;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    warning.accept("System clipboard was interrupted during exit");
                    break;
                }
                var ready = active;
                active = null;
                publish(ready);
            }
        } finally {
            closing = false;
            if (active != null) {
                active.detachWakeup();
                active.cancel(true);
            }
            active = null;
            latestWrite = null;
            if (worker != null) worker.shutdownNow();
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("System clipboard belongs to the UI thread");
    }

    private record Paste(Object token, BooleanSupplier stillCurrent, Runnable apply) {}
    private record Receipt(Optional<String> text, boolean written) {}

    private static final class Operation extends FutureTask<Receipt> {
        private final Object reading;
        private volatile Runnable wakeup;
        private final AtomicInteger claim = new AtomicInteger();
        Operation(Object reading, Callable<Receipt> task, Runnable wakeup) {
            super(task);
            this.reading = reading;
            this.wakeup = wakeup;
        }
        @Override public void run() {
            if (claim.compareAndSet(0, 1)) super.run();
        }
        void rejectBeforeStart(RuntimeException failure) {
            // Some executors run the task before throwing: its actual result must win that race.
            if (claim.compareAndSet(0, 2)) setException(failure);
        }
        void detachWakeup() { wakeup = null; }
        @Override protected void done() {
            Runnable notify = wakeup;
            if (notify == null) return;
            try { notify.run(); }
            catch (RuntimeException disposed) { /* An exiting window need not accept another wakeup. */ }
        }
    }
}
