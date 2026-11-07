package dev.tuink.editor.session.document;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.ResourceBinding;

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
}
