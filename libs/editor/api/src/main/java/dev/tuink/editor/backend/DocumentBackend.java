package dev.tuink.editor.backend;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.document.ViewSnapshot;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.recovery.RecoveryDocument;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.editor.storage.SaveDisposition;
import java.io.IOException;
import java.util.function.Function;

/**
 * Internal host port consumed by the session owner. Implementations retain their
 * mutable document and registered views; the session never exposes either.
 * Except for stable identity/ownership queries and detached inspection/write,
 * calls require the backend's owner thread.
 */
public interface DocumentBackend {
    DocumentId documentId();
    boolean isOwnerThread();
    ViewId defaultViewId();
    boolean ownsView(ViewId viewId);
    long revision();
    /** Advances for document publications, including history/format changes without text edits. */
    long stateRevision();
    ViewSnapshot view(ViewId viewId);
    long viewRevision(ViewId viewId);
    DocumentSnapshot snapshot();
    Status status();
    DocumentCommandResult.Applied execute(DocumentCommandTarget target, DocumentCommand command);

    /** Prepare the result against staged text before publishing text/history. */
    <T> T applyWorkspaceEdit(WorkspaceEdit edit, Function<Boolean, T> prepareResult);
    <T> T undo(Function<Boolean, T> prepareResult);
    <T> T redo(Function<Boolean, T> prepareResult);

    void openPrepared(DocumentLoad loaded);
    void restoreRecovery(ViewId viewId, RecoveryDocument checkpoint);
    PreparedSave prepareSave() throws IOException;
    SavePreparation prepareSaveAsInput(DocumentResource resource, SaveDisposition disposition, boolean utf8)
        throws IOException;
    PreparedSave approveSaveAs(SaveTargetInspection inspection) throws IOException;
    void completeSaved(PreparedSave prepared, SavedDocument saved);

    record Status(ResourceBinding binding, DocumentFileMetadata metadata, boolean dirty, long recoveryRevision) {}

    interface PreparedSave { SavedDocument write() throws IOException; }
    interface SavePreparation {
        SaveTargetInspection inspectTarget() throws IOException;
        SaveDisposition disposition();
    }
    interface SaveTargetInspection {
        DocumentResource requestedResource();
        DocumentResource physicalResource();
        boolean exists();
        boolean currentTarget();
    }
}
