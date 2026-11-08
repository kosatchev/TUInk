package dev.tuink.editor.session.document;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;

/** Owner facade for saving a live document or an immutable source without requiring an editing view. */
public interface DocumentSaveSubject {
    DocumentId documentId();
    boolean isOwnerThread();
    ResourceBinding binding();
    DocumentFileMetadata metadata();
    DocumentIoCoordinator io();

    /**
     * Accepts a host-confirmed move or removal against the captured file binding.
     * Performs no storage operation. A changed binding rejects the late result;
     * an active save must finish before a host starts the filesystem change.
     * An unbound replacement retains the text and requires an explicit Save As.
     */
    boolean relocateResource(ResourceBinding expected, ResourceBinding replacement);

    /** Accepts the exact detached disk read without performing I/O; Update is one undoable text/format change. */
    default boolean acceptExternal(ResourceBinding expected, DocumentLoad loaded, boolean update) { return false; }

    /** Decoder of the accepted disk bytes, independent of the buffer's editable output format. */
    default DocumentOpenMode reopenMode() { return DocumentOpenMode.forMetadata(metadata()); }

    /** Immutable sources can own external-update history without constructing an editing document. */
    default boolean canUndoExternal() { return false; }
    default boolean canRedoExternal() { return false; }
    default boolean undoExternal() { return false; }
    default boolean redoExternal() { return false; }
    default boolean unsavedExternalChanges() { return false; }
}
