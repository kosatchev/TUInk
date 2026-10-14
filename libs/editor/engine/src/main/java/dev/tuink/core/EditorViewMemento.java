package dev.tuink.core;

import java.util.Objects;

/** Immutable view-local position associated with a document history entry. */
public record EditorViewMemento(long viewId, Position cursor, ViewportState viewport, Position selectionAnchor) {
    public EditorViewMemento {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(viewport, "viewport");
    }
}
