package dev.tuink.editor.session.document;

import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.core.DocumentEditLimitException;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.backend.DocumentBackend;
import dev.tuink.editor.storage.DocumentSaveOperations;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.recovery.RecoveryDocument;
import dev.tuink.editor.recovery.RecoveryCheckpoint;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.document.ViewSnapshot;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.session.edit.WorkspaceEditValidator;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.StorageOperation;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.format.DocumentFormatSpec;
import dev.tuink.editor.format.DocumentFormatSummary;
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
public final class EditorDocumentSession implements DocumentSaveSubject {
    private final DocumentId documentId;
    private final ViewId defaultViewId;
    private final DocumentBackend backend;
    private final Thread ownerThread;
    private final DocumentIoCoordinator io;
    private volatile DocumentSnapshot publishedSnapshot;
    private EditorWorkspace<?> workspace;
    private DocumentFileMetadata summaryMetadata;
    private DocumentFormatSummary summary;

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
        this.io = new DocumentIoCoordinator(this, new LiveSaveOperations());
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

    /** True only for a currently attached view; retired IDs never become valid again. */
    public boolean ownsView(ViewId viewId) {
        requireOwnerThread();
        return backend.ownsView(Objects.requireNonNull(viewId, "viewId"));
    }

    /** Creates an independent logical position over the same text and undo history. */
    public ViewId attachView() {
        requireOwnerThread();
        return backend.attachView();
    }

    /**
     * Retires a view without discarding the document, recovery or in-flight save.
     * Views admitted to a workspace must be removed through that owner; a
     * prepared view whose admission was abandoned can still be retired here.
     */
    public void detachView(ViewId viewId) {
        requireOwnerThread();
        Objects.requireNonNull(viewId, "viewId");
        if (workspace != null) {
            var admitted = workspace.find(viewId);
            if (admitted != null && admitted.document() == this) {
                throw new IllegalStateException("Workspace owns document view lifetime");
            }
        }
        backend.detachView(viewId);
    }

    /** Internal owner handshake used after workspace removal has been prepared. */
    void detachWorkspaceView(EditorWorkspace<?> owner, ViewId viewId) {
        requireOwnerThread();
        if (workspace != Objects.requireNonNull(owner, "owner")) throw new IllegalArgumentException("Wrong workspace owner");
        backend.detachView(Objects.requireNonNull(viewId, "viewId"));
    }

    /** The current workspace owner, or null while the document has no workspace attachments. */
    public EditorWorkspace<?> workspace() {
        requireOwnerThread();
        return workspace;
    }

    /** Internal ownership handshake; a document cannot belong to two workspace owners. */
    void claimWorkspace(EditorWorkspace<?> owner) {
        requireOwnerThread();
        Objects.requireNonNull(owner, "owner");
        if (!owner.isOwnerThread()) throw new IllegalStateException("Workspace belongs to another owner thread");
        if (workspace != null && workspace != owner) throw new IllegalStateException("Document already belongs to another workspace");
        workspace = owner;
    }

    /** Releases the document after the last workspace attachment has been retired. */
    void releaseWorkspace(EditorWorkspace<?> owner) {
        requireOwnerThread();
        if (workspace != Objects.requireNonNull(owner, "owner")) throw new IllegalArgumentException("Wrong workspace owner");
        workspace = null;
    }

    /** The retained owner coordinator; detaching a frontend does not replace it. */
    public DocumentIoCoordinator io() { return io; }

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
        if (io.busy() && mutatesDocument(command)) return rejected(target, DocumentCommandResult.RejectionReason.DOCUMENT_BUSY);
        return backend.execute(target, command);
    }

    private static boolean mutatesDocument(DocumentCommand command) {
        if (command instanceof DocumentCommand.Input || command instanceof DocumentCommand.Paste
            || command instanceof DocumentCommand.SetDocumentFormat) return true;
        if (command instanceof DocumentCommand.Edit edit) {
            return edit.action() != DocumentCommand.EditAction.COPY && edit.action() != DocumentCommand.EditAction.SELECT_ALL;
        }
        if (command instanceof DocumentCommand.Search search) {
            return search.action() == DocumentCommand.SearchAction.REPLACE
                || search.action() == DocumentCommand.SearchAction.REPLACE_ALL;
        }
        return false;
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

    @Override public ResourceBinding binding() { return status().binding(); }
    @Override public DocumentFileMetadata metadata() { return status().metadata(); }
    @Override public DocumentOpenMode reopenMode() { requireOwnerThread(); return backend.reopenMode(); }
    @Override public boolean unsavedExternalChanges() { return status().dirty(); }

    @Override public boolean acceptExternal(ResourceBinding expected, DocumentLoad loaded, boolean update) {
        requireOwnerThread();
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(loaded, "loaded");
        if (io.busy() || io.state() == DocumentIoCoordinator.State.STOPPED) return false;
        return backend.acceptExternal(expected, loaded, update);
    }

    @Override public boolean relocateResource(ResourceBinding expected, ResourceBinding replacement) {
        requireOwnerThread();
        requireIoIdle();
        return backend.relocateResource(Objects.requireNonNull(expected, "expected"),
            Objects.requireNonNull(replacement, "replacement"));
    }

    /** Cached by immutable metadata identity; no text or separator scan occurs on repeated frames. */
    public DocumentFormatSummary formatSummary() {
        requireOwnerThread();
        DocumentFileMetadata metadata = backend.status().metadata();
        boolean finalNewline = backend.finalNewline();
        if (summaryMetadata != metadata || summary == null || summary.finalNewline() != finalNewline) {
            DocumentFormatSummary prepared = DocumentFormatSummary.from(metadata, finalNewline);
            summaryMetadata = metadata;
            summary = prepared;
        }
        return summary;
    }

    /** Captures text/format revisions while allowing later cursor movement. */
    public PreparedDocumentFormat prepareFormatChange(ViewId viewId, DocumentFormatSpec spec) {
        requireOwnerThread();
        requireIoIdle();
        Objects.requireNonNull(spec, "spec");
        DocumentCommandTarget target = commandTarget(viewId);
        return new PreparedDocumentFormat(this, target, backend.prepareFormatChange(spec), spec);
    }

    /** Publishes exactly one matching validation through the normal document command path. */
    public DocumentCommandResult completeFormatChange(PreparedDocumentFormat prepared,
                                                       PreparedDocumentFormat.Completion completion) {
        Objects.requireNonNull(prepared, "prepared");
        if (!isOwnerThread()) return rejected(prepared.target, DocumentCommandResult.RejectionReason.WRONG_OWNER_THREAD);
        if (prepared.owner != this) throw new IllegalArgumentException("Format preparation belongs to another session");
        prepared.requireCompletion(completion);
        DocumentCommandTarget target = prepared.target;
        DocumentCommandResult.RejectionReason reason = null;
        if (prepared.cancelled()) reason = DocumentCommandResult.RejectionReason.CANCELLED;
        else if (!backend.ownsView(target.viewId())) reason = DocumentCommandResult.RejectionReason.UNKNOWN_VIEW;
        else if (backend.revision() != target.revision()) reason = DocumentCommandResult.RejectionReason.STALE_DOCUMENT;
        else if (backend.stateRevision() != target.stateRevision()) reason = DocumentCommandResult.RejectionReason.STALE_DOCUMENT_STATE;
        else if (io.busy()) reason = DocumentCommandResult.RejectionReason.DOCUMENT_BUSY;
        if (reason != null) {
            DocumentCommandResult result = rejected(target, reason);
            prepared.accepted();
            return result;
        }
        DocumentCommandResult result = execute(commandTarget(target.viewId()),
            new DocumentCommand.SetDocumentFormat(prepared.prepared, completion.validation));
        prepared.accepted();
        return result;
    }

    /** Explicit re-read of the current resource; unsaved text requires the host's prior approval. */
    public PreparedDocumentReopen prepareReopen(ViewId viewId, DocumentOpenMode mode, boolean discardUnsaved) {
        requireOwnerThread();
        requireIoIdle();
        DocumentCommandTarget target = commandTarget(viewId);
        DocumentBackend.Status status = backend.status();
        if (status.dirty() && !discardUnsaved) throw new IllegalStateException("Unsaved text requires explicit discard approval");
        if (status.binding().resource() == null || status.binding().disposition() != SaveDisposition.REPLACE_EXISTING) {
            throw new IllegalStateException("Reopen requires an existing resource");
        }
        return new PreparedDocumentReopen(this, target, status.binding().resource(), Objects.requireNonNull(mode, "mode"));
    }

    /** False means a cancelled, retired, changed or busy document; its current draft is retained. */
    public boolean completeReopen(PreparedDocumentReopen prepared, DocumentLoad loaded) {
        requireOwnerThread();
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.owner != this) throw new IllegalArgumentException("Reopen preparation belongs to another session");
        prepared.requireUnaccepted();
        DocumentCommandTarget target = prepared.target;
        if (prepared.cancelled() || io.busy() || !backend.ownsView(target.viewId())
            || backend.revision() != target.revision() || backend.stateRevision() != target.stateRevision()) {
            prepared.cancel();
            prepared.markAccepted();
            return false;
        }
        DocumentLoad accepted = prepared.accept(loaded);
        prepared.markAccepted();
        if (accepted == null) return false;
        backend.openPrepared(accepted);
        return true;
    }

    /**
     * Publishes a detached read on the owner thread. Snapshot publication remains
     * demand driven, as it is for attached frontend edits: call snapshot() on the
     * owner before submitting document-derived background work.
     */
    public void openPrepared(DocumentLoad loaded) {
        requireOwnerThread();
        requireIoIdle();
        backend.openPrepared(loaded);
    }

    /** Restores neutral draft data; its frontend restores preallocated viewport state after success. */
    public void restoreRecovery(ViewId viewId, RecoveryDocument checkpoint) {
        requireOwnerThread();
        requireIoIdle();
        backend.restoreRecovery(Objects.requireNonNull(viewId, "viewId"), Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    /** Captures immutable draft inputs even while a save is awaiting its outcome. */
    public RecoveryCheckpoint prepareRecovery(ViewId viewId) {
        requireOwnerThread();
        return backend.prepareRecovery(Objects.requireNonNull(viewId, "viewId"));
    }

    /** Captures immutable current-save inputs without calling a storage provider. */
    PreparedSave prepareSave() throws IOException {
        requireOwnerThread();
        requireIoIdle();
        return new PreparedSave(this, backend.prepareSave());
    }

    /** Captures a Save As plan. Run inspectTarget() on the host's I/O worker. */
    SavePreparation prepareSaveAsInput(DocumentResource resource, SaveDisposition disposition, boolean utf8)
        throws IOException {
        requireOwnerThread();
        requireIoIdle();
        return new SavePreparation(this, backend.prepareSaveAsInput(resource, disposition, utf8));
    }

    /** Approves exactly the detached observation while its owner state remains unchanged. */
    PreparedSave approveSaveAs(SaveTargetInspection inspection) throws IOException {
        requireOwnerThread();
        requireIoIdle();
        return approveSaveAsInternal(inspection);
    }

    private PreparedSave approveSaveAsInternal(SaveTargetInspection inspection) throws IOException {
        Objects.requireNonNull(inspection, "inspection");
        if (inspection.owner != this) throw new IllegalArgumentException("Save inspection belongs to another session");
        return new PreparedSave(this, backend.approveSaveAs(inspection.inspection));
    }

    /**
     * Completes the matching write on the owner. Hosts keep document edits locked
     * between capture and completion; failure leaves the draft and savepoint intact.
     */
    void completeSaved(PreparedSave prepared, SavedDocument saved) {
        requireOwnerThread();
        requireIoIdle();
        completeSavedInternal(prepared, saved);
    }

    private void completeSavedInternal(PreparedSave prepared, SavedDocument saved) {
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.owner != this) throw new IllegalArgumentException("Prepared save belongs to another session");
        backend.completeSaved(prepared.prepared, saved);
    }

    PreparedSave prepareCoordinatedSave(DocumentIoCoordinator coordinator) throws IOException {
        requireCoordinator(coordinator);
        return new PreparedSave(this, backend.prepareSave());
    }

    SavePreparation prepareCoordinatedSaveAs(DocumentIoCoordinator coordinator, DocumentResource resource,
                                           SaveDisposition disposition, boolean utf8) throws IOException {
        requireCoordinator(coordinator);
        return new SavePreparation(this, backend.prepareSaveAsInput(resource, disposition, utf8));
    }

    PreparedSave approveCoordinatedSaveAs(DocumentIoCoordinator coordinator, SaveTargetInspection inspection)
        throws IOException {
        requireCoordinator(coordinator);
        return approveSaveAsInternal(inspection);
    }

    void completeCoordinatedSave(DocumentIoCoordinator coordinator, PreparedSave prepared, SavedDocument saved) {
        requireCoordinator(coordinator);
        completeSavedInternal(prepared, saved);
    }

    private void requireCoordinator(DocumentIoCoordinator coordinator) {
        requireOwnerThread();
        if (coordinator != io) throw new IllegalArgumentException("Coordinator belongs to another session");
    }

    private void requireIoIdle() {
        if (io.busy()) throw new IllegalStateException("Document is busy saving");
    }

    /** Opaque owner capture; inspection reads no mutable document state. */
    static final class SavePreparation implements DocumentSaveOperations.SavePreparation {
        private final EditorDocumentSession owner;
        private final DocumentBackend.SavePreparation preparation;

        private SavePreparation(EditorDocumentSession owner, DocumentBackend.SavePreparation preparation) {
            this.owner = owner;
            this.preparation = preparation;
        }

        public SaveTargetInspection inspectTarget() throws IOException {
            return new SaveTargetInspection(owner, preparation.inspectTarget());
        }
        public SaveTargetInspection inspectTargetWithOperation(StorageOperation operation) throws IOException {
            return new SaveTargetInspection(owner, preparation.inspectTargetWithOperation(operation));
        }

        public SaveDisposition disposition() { return preparation.disposition(); }
    }

    /** An inspected target bound to one capture, ready for owner approval. */
    static final class SaveTargetInspection implements DocumentSaveOperations.SaveTargetInspection {
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
    static final class PreparedSave implements DocumentSaveOperations.PreparedSave {
        private final EditorDocumentSession owner;
        private final DocumentBackend.PreparedSave prepared;

        private PreparedSave(EditorDocumentSession owner, DocumentBackend.PreparedSave prepared) {
            this.owner = owner;
            this.prepared = prepared;
        }

        public SavedDocument write() throws IOException { return prepared.write(); }
        public SavedDocument writeWithOperation(StorageOperation operation) throws IOException {
            return prepared.writeWithOperation(operation);
        }
    }

    /** Preserves the live backend's history/savepoint rules behind the common save protocol. */
    private final class LiveSaveOperations implements DocumentSaveOperations {
        @Override public EditorDocumentSession.PreparedSave prepareSave() throws IOException {
            return prepareCoordinatedSave(io);
        }
        @Override public EditorDocumentSession.SavePreparation prepareSaveAsInput(DocumentResource resource, SaveDisposition disposition,
            boolean utf8) throws IOException {
            return prepareCoordinatedSaveAs(io, resource, disposition, utf8);
        }
        @Override public EditorDocumentSession.PreparedSave approveSaveAs(DocumentSaveOperations.SaveTargetInspection inspection)
            throws IOException {
            return approveSaveAs(inspection, false);
        }
        @Override public EditorDocumentSession.PreparedSave approveSaveAs(DocumentSaveOperations.SaveTargetInspection inspection,
            boolean replaceExternalChanges) throws IOException {
            requireCoordinator(io);
            if (!(inspection instanceof EditorDocumentSession.SaveTargetInspection captured)) {
                throw new IllegalArgumentException("Save inspection belongs to another subject");
            }
            if (captured.owner != EditorDocumentSession.this) throw new IllegalArgumentException("Save inspection belongs to another session");
            return new EditorDocumentSession.PreparedSave(EditorDocumentSession.this, backend.approveSaveAs(captured.inspection, replaceExternalChanges));
        }
        @Override public void completeSaved(DocumentSaveOperations.PreparedSave prepared, SavedDocument saved) {
            if (!(prepared instanceof EditorDocumentSession.PreparedSave captured)) {
                throw new IllegalArgumentException("Prepared save belongs to another subject");
            }
            completeCoordinatedSave(io, captured, saved);
        }
    }

    /**
     * On the owner thread, returns and publishes the current document on demand.
     * Unchanged revisions reuse the same immutable snapshot. Background callers receive the
     * last published snapshot without reading mutable document state; it may precede recent UI edits.
     */
    public DocumentSnapshot snapshot() {
        return isOwnerThread() ? publishIfChanged() : publishedSnapshot;
    }

    /** Captures text, exact file format, and resource version in the same owner turn without I/O. */
    public FrozenDocumentSource freezeSource() {
        requireOwnerThread();
        DocumentSnapshot text = snapshot();
        DocumentBackend.Status status = backend.status();
        return new FrozenDocumentSource(text, status.metadata(), status.binding(), backend.reopenMode());
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

        if (io.busy()) {
            return new WorkspaceEditResult.Rejected(WorkspaceEditResult.RejectionReason.DOCUMENT_BUSY, current, List.of());
        }

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
        requireIoIdle();
        WorkspaceEditResult.Applied result = backend.undo(this::prepareMutationResult);
        publishedSnapshot = result.snapshot();
        return result.changed();
    }

    /** Redoes exactly one mutation from the same document history as every attached view. */
    public boolean redo() {
        requireOwnerThread();
        requireIoIdle();
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

    public boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    void requireOwnerThread() {
        if (!isOwnerThread()) {
            throw new IllegalStateException("Editor document session mutation requires its owner thread");
        }
    }

}
