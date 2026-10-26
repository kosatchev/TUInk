package dev.tuink.editor.document;

import java.util.Objects;

/** Small owner-thread view projection; contains no text or frontend viewport geometry. */
public record ViewSnapshot(DocumentId documentId, ViewId viewId, long revision, long viewRevision,
                           TextPosition cursor, TextPosition selectionAnchor) {
    public ViewSnapshot {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(viewId, "viewId");
        Objects.requireNonNull(cursor, "cursor");
        if (revision < 0 || viewRevision < 0) throw new IllegalArgumentException("Negative revision");
    }
}
