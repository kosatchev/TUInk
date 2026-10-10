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

    /**
     * Returns a conservative first line that may have changed since an earlier
     * revision of this same document. Lines before the returned index retain
     * their text and positions. The index may equal {@link #lineCount()} when
     * all current lines are unchanged.
     *
     * <p>Implementations without the requested change history return zero.
     * Consumers must still discard all cached state when document identity
     * changes.</p>
     */
    default int firstChangedLineSince(long earlierRevision) {
        return 0;
    }
}
