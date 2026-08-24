package dev.tuink.editor.session.event;

import dev.tuink.editor.document.DocumentId;
import java.util.Objects;

/** An asynchronous result tied to the document revision used to compute it. */
public record RevisionedExternalEvent<T>(
    DocumentId documentId,
    long sourceRevision,
    T payload
) {
    public RevisionedExternalEvent {
        Objects.requireNonNull(documentId, "documentId");
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("Source revision must be non-negative");
        }
        Objects.requireNonNull(payload, "payload");
    }
}
