package dev.tuink.editor.document;

/**
 * Read-only access to document text at a revision.
 *
 * <p>The view deliberately exposes no editing operations or presentation-specific
 * coordinates. Implementations may be immutable snapshots or live read-only
 * adapters; consumers that cache derived data must key it by both
 * {@link #documentId()} and {@link #revision()}.</p>
 */
public interface DocumentView {
    DocumentId documentId();

    long revision();

    int lineCount();

    String line(int index);
}
