package dev.tuink.editor.session.recovery;

import dev.tuink.editor.document.DocumentId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Owner-thread recovery lifecycle with one detached checkpoint in flight.
 * Executors, persistence, frontend checkpoint data, and process lifetime belong to the host.
 */
public final class RecoveryCoordinator<C> {
    private static final long DEBOUNCE_NANOS = 2_000_000_000L;
    private static final long ERROR_COOLDOWN_NANOS = 5_000_000_000L;
    public enum RemovalDisposition { PRESERVE, DISCARD }
    private enum Cleanup { RELEASE, DELETE }

    private final RecoveryPersistence<C> persistence;
    private final Executor executor;
    private final LongSupplier nanoClock;
    private final Supplier<String> entryIds;
    private final Thread owner = Thread.currentThread();
    private Map<DocumentId, RuntimeState<C>> runtime = new LinkedHashMap<>();
    private Map<String, PendingCleanup> pendingCleanup = new LinkedHashMap<>();
    private long ownershipRevision;
    private long lastErrorNotifyAtNanos = Long.MIN_VALUE;
    private Operation inFlight;
    private int nextDocumentIndex;

    public RecoveryCoordinator(RecoveryPersistence<C> persistence, Executor executor, LongSupplier nanoClock) {
        this(persistence, executor, nanoClock, () -> UUID.randomUUID().toString());
    }

    public RecoveryCoordinator(RecoveryPersistence<C> persistence, Executor executor, LongSupplier nanoClock,
                               Supplier<String> entryIds) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.entryIds = Objects.requireNonNull(entryIds, "entryIds");
    }

    /**
     * Updates document membership. A detached view is not a discard: hosts must
     * explicitly choose whether removed documents retain their persisted drafts.
     */
    public void synchronize(List<? extends RecoveryParticipant<C>> participants, RemovalDisposition removed) {
        requireOwner();
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(removed, "removed");
        Map<DocumentId, RuntimeState<C>> next = new LinkedHashMap<>();
        Map<String, PendingCleanup> cleanup = new LinkedHashMap<>(pendingCleanup);
        HashSet<String> allocatedEntries = new HashSet<>(cleanup.keySet());
        for (RuntimeState<C> existing : runtime.values()) allocatedEntries.add(existing.entryId);
        for (RecoveryParticipant<C> participant : participants) {
            Objects.requireNonNull(participant, "participant");
            DocumentId id = Objects.requireNonNull(participant.documentId(), "documentId");
            if (next.containsKey(id)) throw new IllegalArgumentException("Duplicate recovery document: " + id);
            RuntimeState<C> state = runtime.get(id);
            if (state != null && state.participant != participant) {
                throw new IllegalArgumentException("A recovery document must retain its participant identity");
            }
            if (state == null) {
                String entry = requireEntryId(entryIds.get());
                if (!allocatedEntries.add(entry)) throw new IllegalArgumentException("Duplicate recovery entryId: " + entry);
                state = new RuntimeState<>(participant, entry);
            }
            next.put(id, state);
        }
        for (Map.Entry<DocumentId, RuntimeState<C>> entry : runtime.entrySet()) {
            if (!next.containsKey(entry.getKey()) && entry.getValue().possiblyPersisted) {
                queue(cleanup, entry.getValue().entryId, removed == RemovalDisposition.DISCARD
                    ? Cleanup.DELETE : Cleanup.RELEASE);
            }
        }
        runtime = next;
        pendingCleanup = cleanup;
        ownershipRevision++;
    }

    /** Requests fresh checkpoints during the document lifetime, including view-only changes. */
    public void requestCheckpoint() {
        requireOwner();
        ownershipRevision++;
        for (RuntimeState<C> state : runtime.values()) state.checkpointRequested = true;
    }

    /** Captures at most one immutable version; never queues snapshots behind a blocked write. */
    public String tick(long nowNanos) {
        requireOwner();
        ownershipRevision++;
        String warning = collectCompletion(nowNanos, false);
        if (inFlight != null) return warning;
        warning = firstNonNull(warning, retryCleanup(nowNanos, false));
        if (inFlight != null || runtime.isEmpty()) return warning;
        List<RuntimeState<C>> documents = new ArrayList<>(runtime.values());
        int start = Math.floorMod(nextDocumentIndex, documents.size());
        for (int offset = 0; offset < documents.size(); offset++) {
            int index = (start + offset) % documents.size();
            RuntimeState<C> state = documents.get(index);
            RecoveryParticipant.Status status = state.participant.status();
            if (!status.dirty()) {
                state.checkpointRequested = false;
                if (!state.possiblyPersisted || !elapsed(nowNanos, state.lastCleanupAttempt, DEBOUNCE_NANOS)) continue;
                state.lastCleanupAttempt = nowNanos;
                submit(new Operation(state, -1, nowNanos, null, Cleanup.DELETE));
            } else {
                if (!state.checkpointRequested && state.hasSnapshot && status.revision() == state.lastRevision) continue;
                if (!elapsed(nowNanos, state.lastSaveAttempt, DEBOUNCE_NANOS)) continue;
                state.lastSaveAttempt = nowNanos;
                try {
                    PreparedCheckpoint<C> captured = Objects.requireNonNull(state.participant.prepareCheckpoint(), "checkpoint");
                    Operation operation = new Operation(state, status.revision(), nowNanos, captured, null);
                    // The port may commit and then throw. Cleanup authorization must
                    // survive even the very first uncertain publication.
                    state.possiblyPersisted = true;
                    state.checkpointRequested = false;
                    submit(operation);
                } catch (RuntimeException | OutOfMemoryError failure) {
                    warning = firstNonNull(warning, failureWarning("Recovery autosave failed", failure, nowNanos, false));
                    continue;
                }
            }
            nextDocumentIndex = index + 1;
            warning = firstNonNull(warning, collectCompletion(nowNanos, false));
            if (inFlight != null) return warning;
        }
        return warning;
    }

    public record RestoredParticipant<C>(RecoveryParticipant<C> participant, String entryId) {
        public RestoredParticipant {
            Objects.requireNonNull(participant, "participant");
            requireEntryId(entryId);
        }
    }

    /** Staged ownership carries no deletion permission until the frontend publishes its documents. */
    public static final class PreparedRestoration<C> {
        private final RecoveryCoordinator<C> owner;
        private final long revision;
        private final Map<DocumentId, RuntimeState<C>> runtime;
        private final Map<String, PendingCleanup> cleanup;
        private boolean published;

        private PreparedRestoration(RecoveryCoordinator<C> owner, Map<DocumentId, RuntimeState<C>> runtime,
                                    Map<String, PendingCleanup> cleanup) {
            this.owner = owner;
            this.revision = owner.ownershipRevision;
            this.runtime = runtime;
            this.cleanup = cleanup;
        }
    }

    public PreparedRestoration<C> prepareRestoration(List<RestoredParticipant<C>> restored, long nowNanos) {
        requireOwner();
        Map<DocumentId, RuntimeState<C>> next = new LinkedHashMap<>(runtime);
        Map<String, PendingCleanup> cleanup = new LinkedHashMap<>(pendingCleanup);
        HashSet<DocumentId> documents = new HashSet<>();
        HashSet<String> entries = new HashSet<>();
        for (RestoredParticipant<C> item : restored) {
            DocumentId id = Objects.requireNonNull(item.participant().documentId(), "documentId");
            if (!documents.add(id) || !entries.add(item.entryId())) {
                throw new IllegalArgumentException("Duplicate restored recovery document or entry");
            }
            if (inFlight != null && inFlight.state.entryId.equals(item.entryId())) {
                throw new IllegalStateException("Recovery entry has an outstanding operation");
            }
            for (RuntimeState<C> active : runtime.values()) {
                if (active.entryId.equals(item.entryId()) && !active.participant.documentId().equals(id)) {
                    throw new IllegalArgumentException("Recovery entry already belongs to another document");
                }
            }
            RuntimeState<C> adopted = new RuntimeState<>(item.participant(), item.entryId());
            adopted.markSaved(item.participant().status().revision(), nowNanos);
            RuntimeState<C> replaced = next.put(id, adopted);
            cleanup.remove(item.entryId());
            if (replaced != null && !replaced.entryId.equals(item.entryId()) && replaced.possiblyPersisted) {
                queue(cleanup, replaced.entryId, Cleanup.DELETE);
            }
        }
        return new PreparedRestoration<>(this, next, cleanup);
    }

    /** Allocation-free commit after prepared documents have been published by the frontend. */
    public void publishRestoration(PreparedRestoration<C> prepared) {
        requireOwner();
        if (prepared.owner != this || prepared.published || prepared.revision != ownershipRevision) {
            throw new IllegalStateException("Recovery publication is stale or belongs to another session");
        }
        runtime = prepared.runtime;
        pendingCleanup = prepared.cleanup;
        prepared.published = true;
        ownershipRevision++;
    }

    public record EntryFailure(String entryId, IOException error) {}
    public record BatchResult(int completedCount, List<EntryFailure> failures) {
        public BatchResult { failures = List.copyOf(failures); }
        public boolean successful() { return failures.isEmpty(); }
    }

    /** Explicit startup discard; postponed/unpublished entries never enter runtime cleanup automatically. */
    public BatchResult deleteEntries(List<String> entries) { return processEntries(entries, Cleanup.DELETE); }
    public BatchResult releaseEntries(List<String> entries) { return processEntries(entries, Cleanup.RELEASE); }

    private BatchResult processEntries(List<String> entries, Cleanup action) {
        requireOwner();
        ownershipRevision++;
        HashSet<String> requested = new HashSet<>();
        for (String entry : entries) {
            requireEntryId(entry);
            if (!requested.add(entry)) throw new IllegalArgumentException("Duplicate recovery entryId: " + entry);
            if (pendingCleanup.containsKey(entry)
                || inFlight != null && inFlight.state.entryId.equals(entry)) {
                throw new IllegalStateException("Recovery entry has an outstanding operation");
            }
            for (RuntimeState<C> state : runtime.values()) {
                if (state.entryId.equals(entry)) throw new IllegalStateException("Recovery entry belongs to an active document");
            }
        }
        int completed = 0;
        List<EntryFailure> failures = new ArrayList<>();
        for (String entry : entries) {
            try {
                if (action == Cleanup.DELETE) persistence.delete(entry);
                else persistence.release(entry);
                pendingCleanup.remove(entry);
                completed++;
            } catch (IOException failure) {
                failures.add(new EntryFailure(entry, failure));
            }
        }
        return new BatchResult(completed, failures);
    }

    /**
     * Blocking owner shutdown boundary. An unconfirmed stop captures the latest
     * state even inside debounce; only explicit frontend discard permits deletion.
     * This method does not own or shut down the host executor.
     */
    public String finish(List<? extends RecoveryParticipant<C>> participants, boolean exitConfirmed) {
        requireOwner();
        ownershipRevision++;
        awaitInFlight();
        String warning = collectCompletion(nanoClock.getAsLong(), true);
        synchronize(participants, exitConfirmed ? RemovalDisposition.DISCARD : RemovalDisposition.PRESERVE);
        if (exitConfirmed) {
            Map<String, PendingCleanup> cleanup = new LinkedHashMap<>(pendingCleanup);
            for (RuntimeState<C> state : runtime.values()) {
                if (state.possiblyPersisted) queue(cleanup, state.entryId, Cleanup.DELETE);
            }
            runtime = new LinkedHashMap<>();
            pendingCleanup = cleanup;
            return firstNonNull(warning, retryCleanup(nanoClock.getAsLong(), true));
        }
        Throwable finalFailure = null;
        try {
            warning = firstNonNull(warning, retryCleanup(nanoClock.getAsLong(), true));
        } catch (RuntimeException | OutOfMemoryError failure) {
            finalFailure = failure;
        }
        for (RuntimeState<C> state : runtime.values()) {
            try {
                RecoveryParticipant.Status status = state.participant.status();
                long nowNanos = nanoClock.getAsLong();
                if (!status.dirty()) {
                    if (!state.possiblyPersisted) continue;
                    try {
                        persistence.delete(state.entryId);
                        state.clearSnapshot();
                    } catch (IOException failure) {
                        warning = firstNonNull(warning, failureWarning("Recovery cleanup failed", failure, nowNanos, true));
                    }
                    continue;
                }
                state.lastSaveAttempt = nowNanos;
                try {
                    C checkpoint = state.participant.prepareCheckpoint().materialize();
                    state.possiblyPersisted = true;
                    persistence.save(state.entryId, checkpoint);
                    state.markSaved(status.revision(), nowNanos);
                    state.checkpointRequested = false;
                } catch (IOException failure) {
                    warning = firstNonNull(warning, failureWarning("Recovery autosave failed", failure, nowNanos, true));
                }
            } catch (RuntimeException | OutOfMemoryError failure) {
                if (finalFailure == null) finalFailure = failure;
                else if (finalFailure != failure) finalFailure.addSuppressed(failure);
            }
        }
        if (finalFailure instanceof RuntimeException unchecked) throw unchecked;
        if (finalFailure instanceof OutOfMemoryError exhausted) throw exhausted;
        return warning;
    }

    private String retryCleanup(long nowNanos, boolean force) {
        var iterator = pendingCleanup.entrySet().iterator();
        String warning = null;
        while (iterator.hasNext()) {
            Map.Entry<String, PendingCleanup> entry = iterator.next();
            PendingCleanup pending = entry.getValue();
            if (!force && !elapsed(nowNanos, pending.lastAttempt, DEBOUNCE_NANOS)) continue;
            pending.lastAttempt = nowNanos;
            if (!force) {
                submit(new Operation(new RuntimeState<>(null, entry.getKey()), -1, nowNanos, null, pending.action));
                return collectCompletion(nowNanos, false);
            }
            try {
                if (pending.action == Cleanup.DELETE) persistence.delete(entry.getKey());
                else persistence.release(entry.getKey());
                iterator.remove();
            } catch (IOException failure) {
                warning = firstNonNull(warning, failureWarning("Recovery cleanup failed", failure, nowNanos, true));
            }
        }
        return warning;
    }

    private void submit(Operation operation) {
        if (inFlight != null) throw new IllegalStateException("Recovery operation already pending");
        inFlight = operation;
        try {
            executor.execute(operation);
        } catch (RuntimeException | Error failure) {
            // An executor may enqueue or start work before throwing. Only an
            // operation which we can still claim may be cancelled here.
            if (operation.claim.compareAndSet(0, 2)) {
                operation.failure = failure;
                operation.done.countDown();
            }
        }
    }

    private String collectCompletion(long nowNanos, boolean force) {
        Operation operation = inFlight;
        if (operation == null || operation.done.getCount() != 0) return null;
        inFlight = null;
        if (operation.failure != null) {
            if (operation.snapshot != null) operation.state.checkpointRequested = true;
            return failureWarning(operation.snapshot == null ? "Recovery cleanup failed" : "Recovery autosave failed",
                operation.failure, nowNanos, force);
        }
        if (operation.snapshot == null) {
            pendingCleanup.remove(operation.state.entryId);
            operation.state.clearSnapshot();
        } else {
            operation.state.markSaved(operation.revision, operation.attemptedAtNanos);
        }
        return null;
    }

    private void awaitInFlight() {
        if (inFlight == null) return;
        boolean interrupted = false;
        while (true) {
            try { inFlight.done.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private final class Operation implements Runnable {
        final RuntimeState<C> state;
        final long revision;
        final long attemptedAtNanos;
        final PreparedCheckpoint<C> snapshot;
        final Cleanup cleanup;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger claim = new AtomicInteger();
        Throwable failure;

        Operation(RuntimeState<C> state, long revision, long attemptedAtNanos,
                  PreparedCheckpoint<C> snapshot, Cleanup cleanup) {
            this.state = state;
            this.revision = revision;
            this.attemptedAtNanos = attemptedAtNanos;
            this.snapshot = snapshot;
            this.cleanup = cleanup;
        }

        @Override public void run() {
            if (!claim.compareAndSet(0, 1)) return;
            try {
                if (snapshot != null) persistence.save(state.entryId, snapshot.materialize());
                else if (cleanup == Cleanup.DELETE) persistence.delete(state.entryId);
                else persistence.release(state.entryId);
            } catch (IOException | RuntimeException | Error error) {
                failure = error;
            } finally { done.countDown(); }
        }
    }

    private static final class RuntimeState<C> {
        final RecoveryParticipant<C> participant;
        final String entryId;
        long lastRevision = Long.MIN_VALUE;
        long lastSaveAttempt = Long.MIN_VALUE;
        long lastCleanupAttempt = Long.MIN_VALUE;
        boolean hasSnapshot;
        boolean possiblyPersisted;
        boolean checkpointRequested;
        RuntimeState(RecoveryParticipant<C> participant, String entryId) {
            this.participant = participant;
            this.entryId = entryId;
        }
        void markSaved(long revision, long nowNanos) {
            lastRevision = revision;
            lastSaveAttempt = nowNanos;
            lastCleanupAttempt = Long.MIN_VALUE;
            hasSnapshot = true;
            possiblyPersisted = true;
        }
        void clearSnapshot() {
            lastRevision = lastSaveAttempt = lastCleanupAttempt = Long.MIN_VALUE;
            hasSnapshot = possiblyPersisted = false;
        }
    }

    private static final class PendingCleanup {
        final Cleanup action;
        long lastAttempt = Long.MIN_VALUE;
        PendingCleanup(Cleanup action) { this.action = action; }
    }

    private static void queue(Map<String, PendingCleanup> pending, String entry, Cleanup action) {
        PendingCleanup existing = pending.get(entry);
        if (existing == null || action == Cleanup.DELETE && existing.action != Cleanup.DELETE) {
            pending.put(entry, new PendingCleanup(action));
        }
    }

    private String failureWarning(String prefix, Throwable failure, long nowNanos, boolean force) {
        if (!force && !elapsed(nowNanos, lastErrorNotifyAtNanos, ERROR_COOLDOWN_NANOS)) return null;
        if (!force) lastErrorNotifyAtNanos = nowNanos;
        return prefix + ": " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Recovery requires its owner thread");
    }

    private static String requireEntryId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Recovery entryId is required");
        return id;
    }

    private static boolean elapsed(long now, long previous, long delay) {
        return previous == Long.MIN_VALUE || now - previous >= delay;
    }

    private static String firstNonNull(String current, String candidate) { return current == null ? candidate : current; }
}
