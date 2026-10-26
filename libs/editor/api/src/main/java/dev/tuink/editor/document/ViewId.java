package dev.tuink.editor.document;

/** Stable identity of one editing view, distinct from its document and resource. */
public record ViewId(long value) {
    public ViewId {
        if (value <= 0) throw new IllegalArgumentException("View identity must be positive");
    }
}
