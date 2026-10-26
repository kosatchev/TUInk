package dev.tuink.editor.session.document;

import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.core.DocumentEditLimitException;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.backend.DocumentBackend;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.recovery.RecoveryDocument;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.document.ViewSnapshot;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.session.edit.WorkspaceEditValidator;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.editor.storage.SaveDisposition;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Single-threaded session for one document with headless and attached editing views.
 *
 * <p>Only the thread that creates a session may mutate it. Immutable snapshots
 * are safely published and may be read by background providers; their results
 * return as revision-guarded {@link WorkspaceEdit}s. Headless workspace commands and
 * attached frontend commands use the same underlying document and history.</p>
 */
public final class EditorDocumentSession {
    private final DocumentId documentId;
    private final ViewId defaultViewId;
    private final DocumentBackend backend;
    private final Thread ownerThread;
    private volatile DocumentSnapshot publishedSnapshot;

    private EditorDocumentSession(DocumentBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
        if (!backend.isOwnerThread()) {
            throw new IllegalStateException("A session must connect on the document owner thread");
        }
        this.documentId = Objects.requireNonNull(backend.documentId(), "documentId");
        this.defaultViewId = Objects.requireNonNull(backend.defaultViewId(), "defaultViewId");
        if (!backend.ownsView(defaultViewId)) throw new IllegalArgumentException("The default view must be registered");
        this.ownerThread = Thread.currentThread();
        this.publishedSnapshot = captureSnapshot();
    }

    /** Connects an owned backend without exporting its mutable document or views. */
    public static EditorDocumentSession connect(DocumentBackend backend) {
        return new EditorDocumentSession(backend);
    }

    /** Creates a headless session whose initial content starts at revision zero. */
    public static EditorDocumentSession create(DocumentId documentId, List<String> initialLines) {
        return connect(CoreDocumentBackend.create(documentId, initialLines));
    }

    public ViewId defaultViewId() { return defaultViewId; }

    /** Captures an immediate input target without capturing document text. */
    public DocumentCommandTarget commandTarget(ViewId viewId) {
        requireOwnerThread();
        if (!backend.ownsView(Objects.requireNonNull(viewId, "viewId"))) {
            throw new IllegalArgumentException("Unknown document view");
        }
        return new DocumentCommandTarget(documentId, viewId, backend.revision(), backend.stateRevision(), backend.viewRevision(viewId));
    }

    /** Small logical selection projection; frontend viewport/layout state stays with the host. */
    public ViewSnapshot view(ViewId viewId) {
        requireOwnerThread();
        return backend.view(Objects.requireNonNull(viewId, "viewId"));
    }

    /** Owner input uses current document and view versions without a whole-document snapshot. */
    public DocumentCommandResult execute(ViewId viewId, DocumentCommand command) {
        return execute(commandTarget(viewId), command);
    }

    /**
     * The single document-command gate. Deferred invocations must still match the
     * document and originating view. Results are prepared before publication;
     * immutable text remains demand-driven through snapshot().
     */
    public DocumentCommandResult execute(DocumentCommandTarget target, DocumentCommand command) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(command, "command");
        if (!isOwnerThread()) return rejected(target, DocumentCommandResult.RejectionReason.WRONG_OWNER_THREAD);
        if (!documentId.equals(target.documentId())) return rejected(target, DocumentCommandResult.RejectionReason.WRONG_DOCUMENT);
        if (!backend.ownsView(target.viewId())) return rejected(target, DocumentCommandResult.RejectionReason.UNKNOWN_VIEW);
        if (backend.revision() != target.revision()) return rejected(target, DocumentCommandResult.RejectionReason.STALE_DOCUMENT);
        if (backend.stateRevision() != target.stateRevision()) return rejected(target, DocumentCommandResult.RejectionReason.STALE_DOCUMENT_STATE);
        if (backend.viewRevision(target.viewId()) != target.viewRevision()) return rejected(target, DocumentCommandResult.RejectionReason.STALE_VIEW);
        return backend.execute(target, command);
    }

    private static DocumentCommandResult.Rejected rejected(DocumentCommandTarget target,
                                                           DocumentCommandResult.RejectionReason reason) {
        return new DocumentCommandResult.Rejected(target, reason);
    }

    /** Stable logical identity; reading it never captures or publishes document text. */
    public DocumentId documentId() {
        return documentId;
    }

    /** Owner-thread storage/savepoint state, independent of the text revision. */
    public Status status() {
        requireOwnerThread();
        DocumentBackend.Status status = backend.status();
        return new Status(status.binding(), status.metadata(), status.dirty(), status.recoveryRevision());
    }

    public record Status(ResourceBinding binding, DocumentFileMetadata metadata, boolean dirty,
                         long recoveryRevision) {}

    /**
     * Publishes a detached read on the owner thread. Snapshot publication remains
     * demand driven, as it is for attached frontend edits: call snapshot() on the
     * owner before submitting document-derived background work.
     */
    public void openPrepared(DocumentLoad loaded) {
        requireOwnerThread();
        backend.openPrepared(loaded);
    }

    /** Restores neutral draft data; its frontend restores preallocated viewport state after success. */
    public void restoreRecovery(ViewId viewId, RecoveryDocument checkpoint) {
        requireOwnerThread();
        backend.restoreRecovery(Objects.requireNonNull(viewId, "viewId"), Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    /** Captures immutable current-save inputs without calling a storage provider. */
    public PreparedSave prepareSave() throws IOException {
        requireOwnerThread();
        return new PreparedSave(this, backend.prepareSave());
    }

    /** Captures a Save As plan. Run inspectTarget() on the host's I/O worker. */
    public SavePreparation prepareSaveAsInput(DocumentResource resource, SaveDisposition disposition, boolean utf8)
        throws IOException {
        requireOwnerThread();
        return new SavePreparation(this, backend.prepareSaveAsInput(resource, disposition, utf8));
    }

    /** Approves exactly the detached observation while its owner state remains unchanged. */
    public PreparedSave approveSaveAs(SaveTargetInspection inspection) throws IOException {
        requireOwnerThread();
        Objects.requireNonNull(inspection, "inspection");
        if (inspection.owner != this) throw new IllegalArgumentException("Save inspection belongs to another session");
        return new PreparedSave(this, backend.approveSaveAs(inspection.inspection));
    }

    /**
     * Completes the matching write on the owner. Hosts keep document edits locked
     * between capture and completion; failure leaves the draft and savepoint intact.
     */
    public void completeSaved(PreparedSave prepared, SavedDocument saved) {
        requireOwnerThread();
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.owner != this) throw new IllegalArgumentException("Prepared save belongs to another session");
        backend.completeSaved(prepared.prepared, saved);
    }

    /** Opaque owner capture; inspection reads no mutable document state. */
    public static final class SavePreparation {
        private final EditorDocumentSession owner;
        private final DocumentBackend.SavePreparation preparation;

        private SavePreparation(EditorDocumentSession owner, DocumentBackend.SavePreparation preparation) {
            this.owner = owner;
            this.preparation = preparation;
        }

        public SaveTargetInspection inspectTarget() throws IOException {
            return new SaveTargetInspection(owner, preparation.inspectTarget());
        }

        public SaveDisposition disposition() { return preparation.disposition(); }
    }

    /** An inspected target bound to one capture, ready for owner approval. */
    public static final class SaveTargetInspection {
        private final EditorDocumentSession owner;
        private final DocumentBackend.SaveTargetInspection inspection;

        private SaveTargetInspection(EditorDocumentSession owner, DocumentBackend.SaveTargetInspection inspection) {
            this.owner = owner;
            this.inspection = inspection;
        }

        public DocumentResource requestedResource() { return inspection.requestedResource(); }
        public DocumentResource physicalResource() { return inspection.physicalResource(); }
        public boolean exists() { return inspection.exists(); }
        public boolean currentTarget() { return inspection.currentTarget(); }
    }

    /** Single-use detached write; completion accepts only the receipt it produced. */
    public static final class PreparedSave {
        private final EditorDocumentSession owner;
        private final DocumentBackend.PreparedSave prepared;

        private PreparedSave(EditorDocumentSession owner, DocumentBackend.PreparedSave prepared) {
            this.owner = owner;
            this.prepared = prepared;
        }

        public SavedDocument write() throws IOException { return prepared.write(); }
    }

    /**
     * On the owner thread, returns and publishes the current document on demand.
     * Unchanged revisions reuse the same immutable snapshot. Background callers receive the
     * last published snapshot without reading mutable document state; it may precede recent UI edits.
     */
    public DocumentSnapshot snapshot() {
        return isOwnerThread() ? publishIfChanged() : publishedSnapshot;
    }

    /**
     * Validates and atomically applies an edit on the owner thread.
     * Owner-thread validation first publishes the current revision. Rejected edits never mutate
     * document content; foreign-thread rejections use only the last published snapshot.
     */
    public WorkspaceEditResult applyWorkspaceEdit(WorkspaceEdit proposed) {
        Objects.requireNonNull(proposed, "proposed");
        DocumentSnapshot current = publishedSnapshot;
        if (!isOwnerThread()) {
            return new WorkspaceEditResult.Rejected(
                WorkspaceEditResult.RejectionReason.WRONG_OWNER_THREAD,
                current,
                List.of()
            );
        }
        current = publishIfChanged();

        List<WorkspaceEditValidator.Violation> violations =
            WorkspaceEditValidator.validate(current, proposed);
        if (!violations.isEmpty()) {
            return new WorkspaceEditResult.Rejected(
                WorkspaceEditResult.RejectionReason.VALIDATION_FAILED,
                current,
                violations
            );
        }

        try {
            WorkspaceEditResult.Applied result = backend.applyWorkspaceEdit(proposed,
                this::prepareMutationResult);
            publishedSnapshot = result.snapshot();
            return result;
        } catch (DocumentEditLimitException tooLarge) {
            return new WorkspaceEditResult.Rejected(
                WorkspaceEditResult.RejectionReason.EDIT_LIMIT_EXCEEDED, current, List.of());
        }
    }

    /** Undoes exactly one committed session mutation. */
    public boolean undo() {
        requireOwnerThread();
        WorkspaceEditResult.Applied result = backend.undo(this::prepareMutationResult);
        publishedSnapshot = result.snapshot();
        return result.changed();
    }

    /** Redoes exactly one mutation from the same document history as every attached view. */
    public boolean redo() {
        requireOwnerThread();
        WorkspaceEditResult.Applied result = backend.redo(this::prepareMutationResult);
        publishedSnapshot = result.snapshot();
        return result.changed();
    }

    private WorkspaceEditResult.Applied prepareMutationResult(boolean changed) {
        return new WorkspaceEditResult.Applied(changed ? captureSnapshot() : publishedSnapshot, changed);
    }

    DocumentSnapshot publishIfChanged() {
        requireOwnerThread();
        if (publishedSnapshot.revision() != backend.revision()) {
            publishedSnapshot = captureSnapshot();
        }
        return publishedSnapshot;
    }

    private DocumentSnapshot captureSnapshot() {
        return backend.snapshot();
    }

    private boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    void requireOwnerThread() {
        if (!isOwnerThread()) {
            throw new IllegalStateException("Editor document session mutation requires its owner thread");
        }
    }

}
