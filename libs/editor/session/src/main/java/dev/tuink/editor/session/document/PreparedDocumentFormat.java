package dev.tuink.editor.session.document;

import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.format.DocumentFormatSpec;
import dev.tuink.editor.format.PreparedFormatChange;
import dev.tuink.editor.storage.StorageOperation;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Detached strict output validation, followed by one owner-thread admission. */
public final class PreparedDocumentFormat {
    final EditorDocumentSession owner;
    final DocumentCommandTarget target;
    final PreparedFormatChange prepared;
    private final DocumentFormatSpec spec;
    private final StorageOperation operation = new StorageOperation(StorageOperation.Phase.VERIFYING);
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Completion observed;
    private boolean accepted;

    PreparedDocumentFormat(EditorDocumentSession owner, DocumentCommandTarget target,
                           PreparedFormatChange prepared, DocumentFormatSpec spec) {
        this.owner = owner;
        this.target = target;
        this.prepared = prepared;
        this.spec = spec;
    }

    public DocumentFormatSpec spec() { return spec; }
    public StorageOperation.Progress progress() { return operation.snapshot(); }
    public boolean cancelled() { return operation.cancelled(); }

    /** Runs only against immutable captured text; no filesystem writes occur. */
    public Completion perform() throws IOException {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Format validation already performed");
        operation.checkCancelled();
        Completion result = new Completion(prepared.perform(operation));
        observed = result;
        return result;
    }

    public boolean cancel() {
        owner.requireOwnerThread();
        return !accepted && operation.requestCancellation();
    }

    void requireCompletion(Completion completion) {
        if (Objects.requireNonNull(completion, "completion") != observed) {
            throw new IllegalArgumentException("Completion belongs to another format preparation");
        }
        if (accepted) throw new IllegalStateException("Format completion has already been accepted");
    }

    void accepted() { accepted = true; }

    public static final class Completion {
        final PreparedFormatChange.Validation validation;
        private Completion(PreparedFormatChange.Validation validation) { this.validation = validation; }
    }
}
