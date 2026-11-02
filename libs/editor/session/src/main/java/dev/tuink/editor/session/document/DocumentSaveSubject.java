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
}
