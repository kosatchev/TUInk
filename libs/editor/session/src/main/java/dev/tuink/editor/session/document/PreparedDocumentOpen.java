package dev.tuink.editor.session.document;

import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One detached read followed by owner admission. The host resolves workspace
 * aliases and creates a document only after accepting the matching read result.
 * Cancelling an open never publishes a document or interrupts a provider call.
 */
public final class PreparedDocumentOpen {
    private static final int CAPTURED = 0;
    private static final int READING = 1;
    private static final int READY = 2;
    private static final int ACCEPTED = 3;
    private static final int FAILED = 4;
    private static final int CANCELLED = 5;

    private final Thread owner = Thread.currentThread();
    private final DocumentResource resource;
    private final DocumentOpenMode mode;
    private final boolean allowMissing;
    private final AtomicInteger state = new AtomicInteger(CAPTURED);
    private volatile DocumentLoad observed;

    public PreparedDocumentOpen(DocumentResource resource, DocumentOpenMode mode, boolean allowMissing) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.allowMissing = allowMissing;
    }

    public DocumentResource resource() { return resource; }
    public DocumentOpenMode mode() { return mode; }
    public boolean allowMissing() { return allowMissing; }

    /** Runs on the host's I/O worker; reads no mutable document or view. */
    public DocumentLoad read() throws IOException {
        if (!state.compareAndSet(CAPTURED, READING)) {
            if (state.get() == CANCELLED) throw new IOException("Open request was cancelled");
            throw new IllegalStateException("Open request has already been performed");
        }
        try {
            DocumentLoad loaded = Objects.requireNonNull(resource.storage().load(resource, mode, allowMissing),
                "Storage returned no document");
            observed = loaded;
            state.compareAndSet(READING, READY);
            return loaded;
        } catch (IOException | RuntimeException | Error failure) {
            state.compareAndSet(READING, FAILED);
            throw failure;
        }
    }

    /** Returns the admitted result, or null for a read abandoned before publication. */
    public DocumentLoad accept(DocumentLoad loaded) {
        requireOwner();
        if (loaded == null || loaded != observed) {
            throw new IllegalArgumentException("Read result belongs to another open request");
        }
        if (state.get() == CANCELLED) return null;
        if (!state.compareAndSet(READY, ACCEPTED)) {
            throw new IllegalStateException("Open result has already been accepted");
        }
        return loaded;
    }

    /** Abandons a pending read; an already accepted result belongs to its host. */
    public boolean cancel() {
        requireOwner();
        while (true) {
            int current = state.get();
            if (current == ACCEPTED || current == CANCELLED) return false;
            if (state.compareAndSet(current, CANCELLED)) return true;
        }
    }

    public boolean cancelled() { return state.get() == CANCELLED; }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Open admission belongs to its owner thread");
    }
}
