package dev.tuink.editor.session.document;

import dev.tuink.editor.storage.DocumentSaveOperations;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.DocumentSave;
import dev.tuink.editor.storage.DocumentSavePlan;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SaveAuthorization;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.SaveTarget;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.editor.storage.StorageOperation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Save lifetime for an immutable source after its editing owner has retired.
 * No editing document, view, engine, history, dirty state, or recovery participant is created.
 * Only a confirmed receipt advances its file binding and format; its raw text stays identical.
 */
public final class FrozenDocumentSession implements DocumentSaveSubject, AutoCloseable {
    private final Thread ownerThread = Thread.currentThread();
    private final DocumentIoCoordinator io;
    private final DocumentId documentId;
    private FrozenDocumentSource source;
    private long generation;

    private FrozenDocumentSession(FrozenDocumentSource source) {
        this.source = Objects.requireNonNull(source, "source");
        documentId = source.text().documentId();
        io = new DocumentIoCoordinator(this, new SaveOperations());
    }

    public static FrozenDocumentSession create(FrozenDocumentSource source) { return new FrozenDocumentSession(source); }

    public FrozenDocumentSource source() { requireOwner(); return source; }
    public long generation() { requireOwner(); return generation; }
    @Override public DocumentId documentId() { return documentId; }
    @Override public boolean isOwnerThread() { return Thread.currentThread() == ownerThread; }
    @Override public ResourceBinding binding() { requireOwner(); return source.binding(); }
    @Override public DocumentFileMetadata metadata() { requireOwner(); return source.metadata(); }
    @Override public DocumentIoCoordinator io() { return io; }

    /** Stops new work; a write past its publication fence still returns its real receipt to this owner. */
    @Override public void close() { io.stop(); }

    private void requireOwner() {
        if (!isOwnerThread()) throw new IllegalStateException("Frozen source belongs to its owner thread");
    }

    private Capture capture(DocumentResource resource, SaveDisposition disposition, boolean utf8) throws IOException {
        requireOwner();
        if (resource == null) throw new IOException("No file selected");
        if (generation == Long.MAX_VALUE) throw new IOException("Frozen source generation exhausted");
        try {
            DocumentFileMetadata metadata = source.metadata();
            if (utf8) metadata = new DocumentFileMetadata(StandardCharsets.UTF_8, BomKind.NONE,
                metadata.defaultLineSeparator(), metadata.lineSeparators());
            var request = new DocumentSave(resource, source.text().immutableLines(), metadata,
                source.binding().version(), disposition);
            return new Capture(this, source, generation, new DocumentSavePlan(request, source.binding()));
        } catch (OutOfMemoryError exhausted) {
            throw new IOException("Not enough memory to prepare the save", exhausted);
        }
    }

    private void requireUnchanged(Capture capture) {
        requireOwner();
        if (capture.owner != this || capture.source != source || capture.generation != generation) {
            throw new IllegalArgumentException("Save capture belongs to another frozen source state");
        }
    }

    private final class SaveOperations implements DocumentSaveOperations {
        @Override public FrozenDocumentSession.Write prepareSave() throws IOException {
            Capture capture = capture(source.binding().resource(), source.binding().disposition(), false);
            return new Write(capture, new SaveAuthorization.Current());
        }

        @Override public Capture prepareSaveAsInput(DocumentResource resource, SaveDisposition disposition,
            boolean utf8) throws IOException {
            return capture(resource, disposition, utf8);
        }

        @Override public FrozenDocumentSession.Write approveSaveAs(DocumentSaveOperations.SaveTargetInspection inspection) {
            if (!(inspection instanceof Inspection observed)) {
                throw new IllegalArgumentException("Inspection belongs to another save subject");
            }
            requireUnchanged(observed.capture);
            return new Write(observed.capture, new SaveAuthorization.Approved(observed.target));
        }

        @Override public void completeSaved(DocumentSaveOperations.PreparedSave prepared, SavedDocument receipt) {
            if (!(prepared instanceof Write write)) {
                throw new IllegalArgumentException("Prepared write belongs to another save subject");
            }
            requireUnchanged(write.capture);
            if (write.completed || !write.write.produced(receipt)) {
                throw new IllegalStateException("Receipt does not belong to this unchanged frozen source and write");
            }
            ResourceBinding binding = new ResourceBinding(receipt.resource(), SaveDisposition.REPLACE_EXISTING, receipt.baseline());
            FrozenDocumentSource next = new FrozenDocumentSource(source.text(), receipt.metadata(), binding,
                dev.tuink.editor.storage.DocumentOpenMode.forMetadata(receipt.metadata()));
            write.completed = true;
            source = next;
            generation++;
        }
    }

    private static final class Capture implements DocumentSaveOperations.SavePreparation {
        private final FrozenDocumentSession owner;
        private final FrozenDocumentSource source;
        private final long generation;
        private final DocumentSavePlan plan;

        private Capture(FrozenDocumentSession owner, FrozenDocumentSource source, long generation, DocumentSavePlan plan) {
            this.owner = owner;
            this.source = source;
            this.generation = generation;
            this.plan = plan;
        }

        @Override public Inspection inspectTarget() throws IOException {
            return inspectTargetWithOperation(StorageOperation.untracked());
        }
        @Override public Inspection inspectTargetWithOperation(StorageOperation operation) throws IOException {
            return new Inspection(this, plan.inspect(operation));
        }
        @Override public SaveDisposition disposition() { return plan.request().disposition(); }
    }

    private record Inspection(Capture capture, SaveTarget target) implements DocumentSaveOperations.SaveTargetInspection {
        @Override public DocumentResource requestedResource() { return target.requestedResource(); }
        @Override public DocumentResource physicalResource() { return target.physicalResource(); }
        @Override public boolean exists() { return target.exists(); }
        @Override public boolean currentTarget() { return target.currentTarget(); }
    }

    private static final class Write implements DocumentSaveOperations.PreparedSave {
        private final Capture capture;
        private final DocumentSavePlan.PreparedWrite write;
        private boolean completed;

        private Write(Capture capture, SaveAuthorization authorization) {
            this.capture = capture;
            write = capture.plan.prepare(authorization);
        }

        @Override public SavedDocument write() throws IOException { return write.write(); }
        @Override public SavedDocument writeWithOperation(StorageOperation operation) throws IOException {
            return write.write(operation);
        }
    }
}
