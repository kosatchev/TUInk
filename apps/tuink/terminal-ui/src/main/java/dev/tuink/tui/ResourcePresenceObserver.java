package dev.tuink.tui;

import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.session.document.DocumentSaveSubject;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded, detached presence checks. Observations never authorize writes or change a document. */
final class ResourcePresenceObserver implements AutoCloseable {
    enum Presence { PRESENT, MISSING, DENIED, UNKNOWN }

    record Observation(DocumentSaveSubject subject, ResourceBinding expected, Path path, Presence presence) {}

    @FunctionalInterface
    interface Probe { void inspect(Path path) throws IOException; }

    private record Request(DocumentSaveSubject subject, ResourceBinding expected, Path path, long generation) {
        boolean sameAs(Request other) {
            return other != null && subject == other.subject && expected.equals(other.expected)
                && generation == other.generation;
        }
    }
    private record Pending(Request request, FutureTask<Presence> task) {}

    private final Thread owner = Thread.currentThread();
    private final Executor executor;
    private final ExecutorService worker;
    private final Probe probe;
    private final BooleanSupplier windowBusy;
    private Pending pending;
    private Request queued;
    private boolean closed;

    ResourcePresenceObserver() { this(() -> false); }

    ResourcePresenceObserver(BooleanSupplier windowBusy) {
        this(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "tuink-resource-presence");
            thread.setDaemon(true);
            return thread;
        }), true, path -> Files.readAttributes(path, BasicFileAttributes.class), windowBusy);
    }

    ResourcePresenceObserver(Executor executor, boolean owned, Probe probe, BooleanSupplier windowBusy) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.worker = owned ? (ExecutorService) executor : null;
        this.probe = Objects.requireNonNull(probe, "probe");
        this.windowBusy = Objects.requireNonNull(windowBusy, "windowBusy");
    }

    /** Captures owner state only. While a stat is outstanding, only the latest additional request is retained. */
    void schedule(DocumentSaveSubject subject) {
        requireOwner();
        Objects.requireNonNull(subject, "subject");
        if (closed || !subject.isOwnerThread() || subject.io().state() == DocumentIoCoordinator.State.STOPPED
            || subject.io().busy()) return;
        ResourceBinding expected = subject.binding();
        if (expected.disposition() != SaveDisposition.REPLACE_EXISTING) return;
        Path path = JvmDocumentStorage.pathOf(expected.resource());
        if (path == null) return;
        Request next = new Request(subject, expected, path, generation(subject));
        if (pending != null && next.sameAs(pending.request())) { queued = null; return; }
        if (next.sameAs(queued)) return;
        queued = next;
        startNext();
    }

    /** Returns only current, idle owner observations; completed work is never awaited. */
    List<Observation> poll() {
        requireOwner();
        if (closed || windowBusy.getAsBoolean()) return List.of();
        Observation observed = null;
        if (pending != null && pending.task().isDone()) {
            Pending completed = pending;
            pending = null;
            Presence presence;
            try { presence = completed.task().get(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                presence = Presence.UNKNOWN;
            } catch (ExecutionException | java.util.concurrent.CancellationException failed) {
                presence = Presence.UNKNOWN;
            }
            Request request = completed.request();
            if (current(request)) observed = new Observation(request.subject(), request.expected(), request.path(), presence);
        }
        startNext();
        return observed == null ? List.of() : List.of(observed);
    }

    boolean busy() { requireOwner(); return !closed && (pending != null || queued != null); }

    private boolean current(Request request) {
        DocumentSaveSubject subject = request.subject();
        return subject.isOwnerThread() && subject.io().state() != DocumentIoCoordinator.State.STOPPED
            && !subject.io().busy() && subject.binding().equals(request.expected())
            && generation(subject) == request.generation();
    }

    private void startNext() {
        if (closed || pending != null || queued == null || windowBusy.getAsBoolean()) return;
        Request request = queued;
        queued = null;
        if (!current(request)) return;
        // The worker captures only a path and a probe, not a document, presentation or this observer.
        Path path = request.path();
        Probe inspector = probe;
        FutureTask<Presence> task = new FutureTask<>(() -> inspect(inspector, path));
        pending = new Pending(request, task);
        try { executor.execute(task); }
        catch (RuntimeException | OutOfMemoryError unavailable) { task.cancel(false); }
    }

    private static Presence inspect(Probe probe, Path path) {
        try { probe.inspect(path); return Presence.PRESENT; }
        catch (NoSuchFileException missing) { return Presence.MISSING; }
        catch (AccessDeniedException | SecurityException denied) { return Presence.DENIED; }
        catch (IOException | RuntimeException unavailable) { return Presence.UNKNOWN; }
    }

    private static long generation(DocumentSaveSubject subject) {
        if (subject instanceof EditorDocumentSession live) return live.status().recoveryRevision();
        if (subject instanceof FrozenDocumentSession frozen) return frozen.generation();
        return 0L;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Resource presence belongs to its owner thread");
    }

    /** A stalled filesystem call must not hold terminal cleanup or retain a document callback. */
    @Override public void close() {
        requireOwner();
        if (closed) return;
        closed = true;
        if (pending != null) pending.task().cancel(true);
        pending = null;
        queued = null;
        if (worker != null) worker.shutdownNow();
    }
}
