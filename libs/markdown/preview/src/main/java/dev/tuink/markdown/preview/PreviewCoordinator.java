package dev.tuink.markdown.preview;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import java.util.Objects;

/** Owner-thread lifecycle gate. Execution and wakeup belong to the frontend. */
public final class PreviewCoordinator implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private long generation;
    private Request current;
    private boolean closed;

    public record Request(DocumentSnapshot snapshot, long generation) {
        public Request { Objects.requireNonNull(snapshot); }
    }

    public Request prepare(DocumentSnapshot snapshot) {
        requireOwner();
        if (closed) throw new IllegalStateException("Preview is closed");
        return current = new Request(snapshot, ++generation);
    }

    public boolean accepts(Request request, DocumentId document, long revision) {
        requireOwner();
        return !closed && current == request && request.snapshot().documentId().equals(document)
            && request.snapshot().revision() == revision;
    }

    public void cancel() { requireOwner(); current = null; generation++; }
    @Override public void close() { requireOwner(); cancel(); closed = true; }
    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Preview state belongs to its owner thread");
    }
}
