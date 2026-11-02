package dev.tuink.editor.storage;

import java.io.IOException;
import java.util.Objects;

/**
 * Immutable provider input shared by live and frozen sources. This plan has no editor owner.
 * A host runs inspection and the separately prepared one-shot write on its I/O worker.
 */
public final class DocumentSavePlan {
    private final DocumentSave request;
    private final ResourceBinding originalBinding;

    public DocumentSavePlan(DocumentSave request, ResourceBinding originalBinding) {
        this.request = Objects.requireNonNull(request, "request");
        this.originalBinding = Objects.requireNonNull(originalBinding, "originalBinding");
    }

    public DocumentSave request() { return request; }
    public ResourceBinding originalBinding() { return originalBinding; }

    public SaveTarget inspect(StorageOperation operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        try {
            operation.checkCancelled();
            DocumentStorage storage = request.resource().storage();
            SaveTarget target = storage.inspect(request.resource(), originalBinding, operation);
            operation.checkCancelled();
            if (target.requestedResource().storage() != storage || target.physicalResource().storage() != storage) {
                throw new IOException("Storage returned a target from another provider");
            }
            return target;
        } catch (OutOfMemoryError exhausted) {
            throw new IOException("Not enough memory to inspect the Save As target", exhausted);
        }
    }

    /** Prepares mandatory receipt-validation outcomes before a provider may publish bytes. */
    public PreparedWrite prepare(SaveAuthorization authorization) {
        return new PreparedWrite(request, Objects.requireNonNull(authorization, "authorization"));
    }

    /** One detached write with an exact receipt; publication of an owner savepoint is separate. */
    public static final class PreparedWrite {
        private final DocumentSave request;
        private final SaveAuthorization authorization;
        private final DocumentStorage storage;
        private final ResourceId expectedResourceId;
        private final StorageWriteException unconfirmedSave;
        private volatile SavedDocument receipt;
        private boolean started;

        private PreparedWrite(DocumentSave request, SaveAuthorization authorization) {
            this.request = request;
            this.authorization = authorization;
            storage = request.resource().storage();
            DocumentResource expected = authorization instanceof SaveAuthorization.Approved approved
                ? approved.target().requestedResource() : request.resource();
            expectedResourceId = expected.id();
            unconfirmedSave = new StorageWriteException("Storage did not confirm the expected resource and document"
                + ". Verify its contents before retrying; the source is retained.", null,
                StorageWriteException.Outcome.UNKNOWN, expected);
        }

        public SavedDocument write() throws IOException { return write(StorageOperation.untracked()); }

        public synchronized SavedDocument write(StorageOperation operation) throws IOException {
            Objects.requireNonNull(operation, "operation");
            if (started) throw new IllegalStateException("A prepared save may be written only once");
            started = true;
            operation.checkCancelled();
            SavedDocument saved;
            try {
                saved = storage.write(request, authorization, operation);
            } catch (OutOfMemoryError exhausted) {
                throw unconfirmed(exhausted);
            }
            boolean matches;
            try {
                matches = saved != null && saved.resource().storage() == storage
                    && expectedResourceId.equals(saved.resource().id())
                    && saved.metadata().isCompatibleWithLineCount(request.lines().size());
            } catch (RuntimeException | Error invalidReceipt) {
                throw unconfirmed(invalidReceipt);
            }
            if (!matches) throw unconfirmedSave;
            receipt = saved;
            return saved;
        }

        public boolean produced(SavedDocument saved) { return saved != null && saved == receipt; }

        private StorageWriteException unconfirmed(Throwable cause) {
            try { unconfirmedSave.addSuppressed(cause); }
            catch (RuntimeException | Error unavailable) { /* The mandatory outcome was allocated before I/O. */ }
            return unconfirmedSave;
        }
    }
}
