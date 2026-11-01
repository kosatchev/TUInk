package dev.tuink.editor.session.document;

import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import java.io.IOException;

/** One detached re-read of a bound document, fenced against text and document-state changes. */
public final class PreparedDocumentReopen {
    final EditorDocumentSession owner;
    final DocumentCommandTarget target;
    private final PreparedDocumentOpen read;
    private boolean accepted;

    PreparedDocumentReopen(EditorDocumentSession owner, DocumentCommandTarget target,
                           DocumentResource resource, DocumentOpenMode mode) {
        this.owner = owner;
        this.target = target;
        this.read = new PreparedDocumentOpen(resource, mode, false);
    }

    public DocumentResource resource() { return read.resource(); }
    public DocumentOpenMode mode() { return read.mode(); }
    public DocumentLoad read() throws IOException { return read.read(); }
    public boolean cancelled() { return read.cancelled(); }
    public boolean cancel() {
        owner.requireOwnerThread();
        return !accepted && read.cancel();
    }
    void requireUnaccepted() {
        if (accepted) throw new IllegalStateException("Reopen completion has already been accepted");
    }
    DocumentLoad accept(DocumentLoad loaded) { return read.accept(loaded); }
    void markAccepted() { accepted = true; }
}
