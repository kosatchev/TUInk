package dev.tuink.editor.command;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.ViewId;
import java.util.Objects;

/** Guards text, semantic document state and the originating view before execution. */
public record DocumentCommandTarget(DocumentId documentId, ViewId viewId, long revision,
                                    long stateRevision, long viewRevision) {
    public DocumentCommandTarget {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(viewId, "viewId");
        if (revision < 0 || stateRevision < 0 || viewRevision < 0) throw new IllegalArgumentException("Negative revision");
    }
}
