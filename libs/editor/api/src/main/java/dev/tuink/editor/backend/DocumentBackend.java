package dev.tuink.editor.backend;

import dev.tuink.editor.storage.DocumentSaveOperations;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.document.ViewSnapshot;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.recovery.RecoveryDocument;
import dev.tuink.editor.recovery.RecoveryCheckpoint;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.format.DocumentFormatSpec;
import dev.tuink.editor.format.PreparedFormatChange;
import java.util.function.Function;

/**
 * Internal host port consumed by the session owner. Implementations retain their
 * mutable document and registered views; the session never exposes either.
 * Except for stable identity/ownership queries and detached inspection/write,
 * calls require the backend's owner thread.
 */
public interface DocumentBackend extends DocumentSaveOperations {
    DocumentId documentId();
    boolean isOwnerThread();
    ViewId defaultViewId();
    boolean ownsView(ViewId viewId);
    /** Attaches a new logical editing position to the existing document/history. */
    ViewId attachView();
    /** Retires this identity permanently; document operations outlive individual views. */
    void detachView(ViewId viewId);
    long revision();
    /** Advances for document publications, including history/format changes without text edits. */
    long stateRevision();
    ViewSnapshot view(ViewId viewId);
    long viewRevision(ViewId viewId);
    DocumentSnapshot snapshot();
    Status status();
    DocumentCommandResult.Applied execute(DocumentCommandTarget target, DocumentCommand command);
    /** Captures immutable text and format; validation runs detached from the owner. */
    PreparedFormatChange prepareFormatChange(DocumentFormatSpec spec);
    /** Constant-time end-of-file projection without capturing document text. */
    boolean finalNewline();

    /** Prepare the result against staged text before publishing text/history. */
    <T> T applyWorkspaceEdit(WorkspaceEdit edit, Function<Boolean, T> prepareResult);
    <T> T undo(Function<Boolean, T> prepareResult);
    <T> T redo(Function<Boolean, T> prepareResult);

    void openPrepared(DocumentLoad loaded);
    RecoveryCheckpoint prepareRecovery(ViewId viewId);
    void restoreRecovery(ViewId viewId, RecoveryDocument checkpoint);
    record Status(ResourceBinding binding, DocumentFileMetadata metadata, boolean dirty, long recoveryRevision) {}
}
