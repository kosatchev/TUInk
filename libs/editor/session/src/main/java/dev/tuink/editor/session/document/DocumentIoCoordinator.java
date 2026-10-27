package dev.tuink.editor.session.document;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.editor.storage.StorageWriteException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owner-thread save protocol. The host executes returned work on its own worker
 * and returns its opaque completion to the same coordinator. No executor, UI or
 * frontend lifetime is owned here. Detaching a view therefore does not discard
 * a draft or cancel an already started external write.
 */
public final class DocumentIoCoordinator {
    public enum State { IDLE, INSPECTING, AWAITING_APPROVAL, WRITING, STOPPED }
    public enum WorkKind { INSPECT, WRITE }
    public enum FailureKind { FAILED_BEFORE_COMMIT, UNKNOWN, CONFIRMED_NOT_PUBLISHED }
    public enum CancelResult { IDLE, CANCELLED, DRAINING_WRITE }

    public sealed interface Transition permits Ready, Approval, Saved, Failed, Ignored {}
    public record Ready(Work work) implements Transition {}
    public record Approval(Confirmation confirmation) implements Transition {}
    public record Saved(DocumentId documentId, long operationId, SavedDocument receipt) implements Transition {}
    public record Failed(DocumentId documentId, long operationId, FailureKind kind, IOException failure)
        implements Transition {}
    public record Ignored(DocumentId documentId, long operationId) implements Transition {}

    private final EditorDocumentSession session;
    private Operation active;
    private long nextOperationId;
    private boolean stopped;

    DocumentIoCoordinator(EditorDocumentSession session) { this.session = session; }

    public State state() {
        requireOwner();
        return active == null ? (stopped ? State.STOPPED : State.IDLE) : active.state;
    }

    public boolean busy() { requireOwner(); return active != null; }
    public boolean writing() { requireOwner(); return active != null && active.state == State.WRITING; }

    /** Captures current content and version; no provider call occurs here. */
    public Work beginSave() throws IOException {
        requireAvailable();
        var prepared = session.prepareCoordinatedSave(this);
        Operation operation = new Operation(nextId(), null);
        Work work = new Work(this, operation, prepared, session.status().binding().resource());
        operation.work = work;
        operation.state = State.WRITING;
        active = operation;
        return work;
    }

    /** Captures a Save As plan without inspecting or writing a resource. */
    public Work beginSaveAs(DocumentResource resource, SaveDisposition disposition, boolean utf8) throws IOException {
        requireAvailable();
        var preparation = session.prepareCoordinatedSaveAs(this, resource, disposition, utf8);
        Operation operation = new Operation(nextId(), Objects.requireNonNull(disposition, "disposition"));
        Work work = new Work(this, operation, preparation, resource);
        operation.work = work;
        operation.state = State.INSPECTING;
        active = operation;
        return work;
    }

    /**
     * Accepts exactly the completion produced by this work. Old, cancelled and
     * duplicate replies have no effects. A foreign coordinator cannot publish it.
     */
    public Transition accept(Work work, Completion completion) {
        requireOwner();
        requireWork(work);
        Objects.requireNonNull(completion, "completion");
        if (completion.work != work) throw new IllegalArgumentException("Completion belongs to another work item");
        if (completion != work.completion || !completion.finished) {
            throw new IllegalArgumentException("The work has not produced this completion");
        }
        if (active != work.operation || active.work != work || completion.consumed) return ignored(work);
        if (completion.cancelled) {
            Ignored ignored = ignored(work);
            completion.consumed = true;
            active = null;
            return ignored;
        }
        if (completion.failure != null) {
            Failed failed = new Failed(session.documentId(), work.operation.id, completion.failureKind, completion.failure);
            completion.consumed = true;
            active = null;
            return failed;
        }
        if (work.kind == WorkKind.INSPECT) {
            var inspection = completion.inspection;
            if (work.operation.disposition == SaveDisposition.CREATE_NEW && inspection.exists()) {
                Failed failed = new Failed(session.documentId(), work.operation.id, FailureKind.FAILED_BEFORE_COMMIT,
                    new IOException("Save target appeared while it was being inspected. Choose the target again."));
                completion.consumed = true;
                active = null;
                return failed;
            }
            Confirmation confirmation = new Confirmation(this, work.operation, inspection);
            Approval approval = new Approval(confirmation);
            completion.consumed = true;
            active.confirmation = confirmation;
            active.state = State.AWAITING_APPROVAL;
            return approval;
        }
        // Prepare both mandatory outcomes before the backend publishes its savepoint.
        Saved saved = new Saved(session.documentId(), work.operation.id, completion.receipt);
        IOException unpublishedError = new IOException("Storage confirmed the write, but the editor could not publish"
            + " its savepoint. The draft is retained; verify the saved resource before retrying.");
        Failed unpublished = new Failed(session.documentId(), work.operation.id,
            FailureKind.CONFIRMED_NOT_PUBLISHED, unpublishedError);
        try {
            session.completeCoordinatedSave(this, work.prepared, completion.receipt);
        } catch (RuntimeException | OutOfMemoryError failure) {
            try { unpublishedError.addSuppressed(failure); }
            catch (RuntimeException | Error ignored) { /* The prepared failure still retains the draft. */ }
            completion.consumed = true;
            active = null;
            return unpublished;
        }
        completion.consumed = true;
        active = null;
        return saved;
    }

    /** Approves the exact observation; overwrite confirmation is a host decision. */
    public Transition approve(Confirmation confirmation) {
        requireOwner();
        Objects.requireNonNull(confirmation, "confirmation");
        if (confirmation.owner != this) throw new IllegalArgumentException("Approval belongs to another coordinator");
        if (active != confirmation.operation || active.confirmation != confirmation
            || active.state != State.AWAITING_APPROVAL) {
            return new Ignored(session.documentId(), confirmation.operation.id);
        }
        try {
            var prepared = session.approveCoordinatedSaveAs(this, confirmation.inspection);
            Work work = new Work(this, active, prepared, confirmation.requestedResource());
            Ready ready = new Ready(work);
            active.work = work;
            active.confirmation = null;
            active.state = State.WRITING;
            return ready;
        } catch (IOException failure) {
            Failed result = new Failed(session.documentId(), active.id, FailureKind.FAILED_BEFORE_COMMIT, failure);
            active = null;
            return result;
        }
    }

    /** Reports executor rejection only when this work has never started. */
    public Transition reject(Work work, IOException failure) {
        requireOwner();
        requireWork(work);
        Objects.requireNonNull(failure, "failure");
        if (active != work.operation || active.work != work) return ignored(work);
        Failed result = new Failed(session.documentId(), work.operation.id, FailureKind.FAILED_BEFORE_COMMIT, failure);
        if (!work.cancelBeforeStart()) throw new IllegalStateException("Started work must return its actual completion");
        active = null;
        return result;
    }

    /** Cancels read-only phases and unstarted writes; started writes must drain. */
    public CancelResult cancel() {
        requireOwner();
        if (active == null) return CancelResult.IDLE;
        if (active.state == State.WRITING && !active.work.cancelBeforeStart()) return CancelResult.DRAINING_WRITE;
        if (active.work != null) active.work.cancelBeforeStart();
        active = null;
        return CancelResult.CANCELLED;
    }

    /** Abandons only this still-current work item, never a later operation or phase. */
    public CancelResult cancel(Work work) {
        requireOwner();
        requireWork(work);
        if (active != work.operation || active.work != work) return CancelResult.IDLE;
        return cancel();
    }

    /** A stale dialog cannot cancel another save or the write it already approved. */
    public CancelResult cancel(Confirmation confirmation) {
        requireOwner();
        Objects.requireNonNull(confirmation, "confirmation");
        if (confirmation.owner != this) throw new IllegalArgumentException("Approval belongs to another coordinator");
        if (active != confirmation.operation || active.confirmation != confirmation
            || active.state != State.AWAITING_APPROVAL) return CancelResult.IDLE;
        return cancel();
    }

    /** Prevents new work. A write already claimed by a worker remains collectible. */
    public CancelResult stop() {
        requireOwner();
        stopped = true;
        return cancel();
    }

    private long nextId() {
        if (nextOperationId == Long.MAX_VALUE) throw new IllegalStateException("Document operation IDs exhausted");
        return ++nextOperationId;
    }

    private void requireOwner() { session.requireOwnerThread(); }
    private void requireAvailable() {
        requireOwner();
        if (stopped || active != null) throw new IllegalStateException("Document I/O is unavailable");
    }
    private void requireWork(Work work) {
        Objects.requireNonNull(work, "work");
        if (work.owner != this) throw new IllegalArgumentException("Work belongs to another coordinator");
    }
    private Ignored ignored(Work work) { return new Ignored(session.documentId(), work.operation.id); }

    private static final class Operation {
        private final long id;
        private final SaveDisposition disposition;
        private State state;
        private Work work;
        private Confirmation confirmation;
        private Operation(long id, SaveDisposition disposition) { this.id = id; this.disposition = disposition; }
    }

    /** Exact provider observation retained until approval; never a reconstructed path ticket. */
    public static final class Confirmation {
        private final DocumentIoCoordinator owner;
        private final Operation operation;
        private final EditorDocumentSession.SaveTargetInspection inspection;
        private Confirmation(DocumentIoCoordinator owner, Operation operation,
                             EditorDocumentSession.SaveTargetInspection inspection) {
            this.owner = owner;
            this.operation = operation;
            this.inspection = inspection;
        }
        public DocumentId documentId() { return owner.session.documentId(); }
        public long operationId() { return operation.id; }
        public DocumentResource requestedResource() { return inspection.requestedResource(); }
        public DocumentResource physicalResource() { return inspection.physicalResource(); }
        public boolean exists() { return inspection.exists(); }
        public boolean currentTarget() { return inspection.currentTarget(); }
        public SaveDisposition disposition() { return operation.disposition; }
        public boolean requiresOverwriteConfirmation() { return exists() && !currentTarget(); }
    }

    /** One-shot detached task. Cancellation and worker start use the same atomic claim. */
    public static final class Work {
        private final DocumentIoCoordinator owner;
        private final Operation operation;
        private final WorkKind kind;
        private final EditorDocumentSession.SavePreparation preparation;
        private final EditorDocumentSession.PreparedSave prepared;
        private final DocumentResource resource;
        private final AtomicInteger execution = new AtomicInteger(); // fresh, started, finished, cancelled
        private final Completion completion;
        private final IOException unexpectedFailure;

        private Work(DocumentIoCoordinator owner, Operation operation, EditorDocumentSession.SavePreparation preparation,
                     DocumentResource resource) {
            this(owner, operation, WorkKind.INSPECT, preparation, null, resource);
        }
        private Work(DocumentIoCoordinator owner, Operation operation, EditorDocumentSession.PreparedSave prepared,
                     DocumentResource resource) {
            this(owner, operation, WorkKind.WRITE, null, prepared, resource);
        }
        private Work(DocumentIoCoordinator owner, Operation operation, WorkKind kind,
                     EditorDocumentSession.SavePreparation preparation, EditorDocumentSession.PreparedSave prepared,
                     DocumentResource resource) {
            this.owner = owner;
            this.operation = operation;
            this.kind = kind;
            this.preparation = preparation;
            this.prepared = prepared;
            this.resource = resource;
            completion = new Completion(this);
            unexpectedFailure = new IOException(kind == WorkKind.WRITE
                ? "The write did not return a confirmed outcome. The editor retains your draft; verify the resource before retrying."
                : "The save target could not be inspected.");
        }
        public DocumentId documentId() { return owner.session.documentId(); }
        public long operationId() { return operation.id; }
        public WorkKind kind() { return kind; }
        public SaveDisposition disposition() { return operation.disposition; }
        public DocumentResource resource() { return resource; }

        public Completion perform() {
            if (!execution.compareAndSet(0, 1)) {
                if (execution.get() == 3) return completion;
                throw new IllegalStateException("Document work may execute only once");
            }
            try {
                if (kind == WorkKind.INSPECT) completion.inspection = preparation.inspectTarget();
                else completion.receipt = prepared.write();
            } catch (IOException failure) {
                completion.failure = failure;
                completion.failureKind = failure instanceof StorageWriteException write
                    && write.outcome() == StorageWriteException.Outcome.UNKNOWN ? FailureKind.UNKNOWN
                    : FailureKind.FAILED_BEFORE_COMMIT;
            } catch (RuntimeException | OutOfMemoryError failure) {
                try { unexpectedFailure.addSuppressed(failure); }
                catch (RuntimeException | Error ignored) { /* Mandatory outcome was allocated before I/O. */ }
                completion.failure = unexpectedFailure;
                completion.failureKind = kind == WorkKind.WRITE ? FailureKind.UNKNOWN : FailureKind.FAILED_BEFORE_COMMIT;
            }
            execution.set(2);
            completion.finished = true;
            return completion;
        }

        private boolean cancelBeforeStart() {
            if (!execution.compareAndSet(0, 3)) return false;
            completion.cancelled = true;
            completion.finished = true;
            return true;
        }
    }

    /** Opaque worker result; its provenance and publication rights cannot be reconstructed. */
    public static final class Completion {
        private final Work work;
        private EditorDocumentSession.SaveTargetInspection inspection;
        private SavedDocument receipt;
        private IOException failure;
        private FailureKind failureKind;
        private boolean cancelled;
        private volatile boolean finished;
        private boolean consumed;
        private Completion(Work work) { this.work = work; }
        public boolean succeeded() { return finished && failure == null && !cancelled; }
        public IOException failure() { return failure; }
    }
}
