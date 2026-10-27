package dev.tuink.editor.session.recovery;

import dev.tuink.editor.document.DocumentId;

/** The owner-thread document boundary; frontend-specific checkpoint data stays opaque. */
public interface RecoveryParticipant<C> {
    DocumentId documentId();
    Status status();
    PreparedCheckpoint<C> prepareCheckpoint();

    record Status(boolean dirty, long revision) {}
}
