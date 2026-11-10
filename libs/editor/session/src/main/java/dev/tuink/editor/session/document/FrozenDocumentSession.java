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
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.ImmutableText;
import dev.tuink.core.DocumentEditLimitException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Owns a retained source after its editing owner has retired, without creating an editing engine.
 * External decisions may replace its immutable source and use a bounded snapshot history.
 * The accepted disk version remains separate from the current text and editable format.
 * A host can checkpoint unsaved retained text through the ordinary recovery participant port.
 */
public final class FrozenDocumentSession implements DocumentSaveSubject, AutoCloseable {
    private static final int MAX_EXTERNAL_HISTORY = 100;
    private static final long MAX_EXTERNAL_HISTORY_BYTES = 64L * 1024 * 1024;
    private final Thread ownerThread = Thread.currentThread();
    private final DocumentIoCoordinator io;
    private final DocumentId documentId;
    private FrozenDocumentSource source;
    private long generation;
    private Content acceptedDisk;
    private boolean externalDirty;
    private Deque<ExternalEdit> externalUndo = new ArrayDeque<>();
    private Deque<ExternalEdit> externalRedo = new ArrayDeque<>();

    private record Content(List<ImmutableText> lines, DocumentFileMetadata metadata) {
        static Content of(FrozenDocumentSource source) { return new Content(source.text().immutableLines(), source.metadata()); }
        long weight() {
            long bytes = 128L + 16L * lines.size();
            for (var line : lines) bytes = Math.addExact(bytes, 2L * line.length());
            return bytes;
        }
    }
    private record ExternalEdit(Content before, Content after) {
        long weight() { return Math.addExact(before.weight(), after.weight()); }
    }

    private FrozenDocumentSession(FrozenDocumentSource source) {
        this.source = Objects.requireNonNull(source, "source");
        documentId = source.text().documentId();
        io = new DocumentIoCoordinator(this, new SaveOperations());
        acceptedDisk = Content.of(source);
        externalDirty = source.binding().resource() == null;
    }

    public static FrozenDocumentSession create(FrozenDocumentSource source) { return new FrozenDocumentSession(source); }

    public FrozenDocumentSource source() { requireOwner(); return source; }
    public long generation() { requireOwner(); return generation; }
    @Override public DocumentId documentId() { return documentId; }
    @Override public boolean isOwnerThread() { return Thread.currentThread() == ownerThread; }
    @Override public ResourceBinding binding() { requireOwner(); return source.binding(); }
    @Override public DocumentFileMetadata metadata() { requireOwner(); return source.metadata(); }
    @Override public DocumentIoCoordinator io() { return io; }
    @Override public DocumentOpenMode reopenMode() { requireOwner(); return source.reopenMode(); }
    @Override public boolean unsavedExternalChanges() { requireOwner(); return externalDirty; }
    @Override public boolean canUndoExternal() { requireOwner(); return editable() && !externalUndo.isEmpty(); }
    @Override public boolean canRedoExternal() { requireOwner(); return editable() && !externalRedo.isEmpty(); }

    @Override public boolean acceptExternal(ResourceBinding expected, DocumentLoad loaded, boolean update) {
        requireOwner();
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(loaded, "loaded");
        if (!editable() || !source.binding().equals(expected) || expected.resource() == null
            || expected.disposition() != SaveDisposition.REPLACE_EXISTING
            || loaded.disposition() != SaveDisposition.REPLACE_EXISTING || loaded.baseline() == null
            || loaded.resource().storage() != expected.resource().storage()
            || !loaded.resource().id().equals(expected.resource().id())) return false;
        try {
            requireGeneration();
            Content current = Content.of(source);
            Content disk = imported(loaded, current);
            boolean changed = !current.equals(disk);
            Deque<ExternalEdit> undo = externalUndo;
            Deque<ExternalEdit> redo = update ? new ArrayDeque<>() : externalRedo;
            if (update && changed) {
                ExternalEdit entry = new ExternalEdit(current, disk);
                if (entry.weight() > MAX_EXTERNAL_HISTORY_BYTES) throw new DocumentEditLimitException();
                undo = new ArrayDeque<>(externalUndo);
                undo.addFirst(entry);
                trimHistory(undo, redo);
            }
            DocumentSnapshot text = update && changed ? snapshot(disk) : source.text();
            var binding = new ResourceBinding(expected.resource(), SaveDisposition.REPLACE_EXISTING, loaded.baseline());
            FrozenDocumentSource next = new FrozenDocumentSource(text, update ? disk.metadata() : source.metadata(), binding,
                DocumentOpenMode.forMetadata(loaded.metadata()));
            source = next;
            acceptedDisk = disk;
            externalDirty = !update && changed;
            externalUndo = undo;
            externalRedo = redo;
            generation++;
            return true;
        } catch (OutOfMemoryError | ArithmeticException exhausted) { throw new DocumentEditLimitException(); }
    }

    @Override public boolean undoExternal() { return replayExternal(true); }
    @Override public boolean redoExternal() { return replayExternal(false); }

    private boolean replayExternal(boolean undoing) {
        requireOwner();
        if (!editable()) return false;
        Deque<ExternalEdit> from = undoing ? externalUndo : externalRedo;
        if (from.isEmpty()) return false;
        try {
            requireGeneration();
            ExternalEdit entry = from.getFirst();
            Content content = undoing ? entry.before() : entry.after();
            var next = new FrozenDocumentSource(snapshot(content), content.metadata(), source.binding(), source.reopenMode());
            Deque<ExternalEdit> undo = new ArrayDeque<>(externalUndo);
            Deque<ExternalEdit> redo = new ArrayDeque<>(externalRedo);
            (undoing ? undo : redo).removeFirst();
            (undoing ? redo : undo).addFirst(entry);
            boolean dirty = source.binding().resource() == null || !content.equals(acceptedDisk);
            source = next;
            externalDirty = dirty;
            externalUndo = undo;
            externalRedo = redo;
            generation++;
            return true;
        } catch (OutOfMemoryError | ArithmeticException exhausted) { throw new DocumentEditLimitException(); }
    }

    private boolean editable() { return !io.busy() && io.state() != DocumentIoCoordinator.State.STOPPED; }
    private void requireGeneration() {
        if (generation == Long.MAX_VALUE || source.text().revision() == Long.MAX_VALUE) throw new DocumentEditLimitException();
    }
    private DocumentSnapshot snapshot(Content content) {
        return DocumentSnapshot.fromImmutableLines(documentId, source.text().revision() + 1, content.lines());
    }
    private static Content imported(DocumentLoad loaded, Content current) {
        Runtime runtime = Runtime.getRuntime();
        long available = Math.max(0L, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
        long bytes = 128 * 1024L + 64L * loaded.lines().size();
        var lines = new ArrayList<ImmutableText>(loaded.lines().size());
        for (int index = 0; index < loaded.lines().size(); index++) {
            String line = loaded.lines().get(index);
            boolean shared = index < current.lines().size() && current.lines().get(index).contentEquals(line);
            if (!shared) bytes = Math.addExact(bytes, 4L * line.length());
            if (bytes > available / 2) throw new DocumentEditLimitException();
            lines.add(shared ? current.lines().get(index) : ImmutableText.of(line));
        }
        return new Content(List.copyOf(lines), loaded.metadata());
    }
    private static void trimHistory(Deque<ExternalEdit> undo, Deque<ExternalEdit> redo) {
        long bytes = 0L;
        for (ExternalEdit entry : undo) bytes = Math.addExact(bytes, entry.weight());
        for (ExternalEdit entry : redo) bytes = Math.addExact(bytes, entry.weight());
        while (undo.size() > MAX_EXTERNAL_HISTORY || bytes > MAX_EXTERNAL_HISTORY_BYTES)
            bytes -= undo.removeLast().weight();
    }

    @Override public boolean relocateResource(ResourceBinding expected, ResourceBinding replacement) {
        requireOwner();
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(replacement, "replacement");
        if (io.busy()) throw new IllegalStateException("Frozen source is busy saving");
        if (!source.binding().equals(expected)) return false;
        if (expected.equals(replacement)) return true;
        if (generation == Long.MAX_VALUE) throw new IllegalStateException("Frozen source generation exhausted");
        FrozenDocumentSource next = new FrozenDocumentSource(source.text(), source.metadata(), replacement,
            source.reopenMode());
        source = next;
        if (replacement.resource() == null) externalDirty = true;
        generation++;
        return true;
    }

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
            return approveSaveAs(inspection, false);
        }

        @Override public FrozenDocumentSession.Write approveSaveAs(DocumentSaveOperations.SaveTargetInspection inspection,
            boolean replaceExternalChanges) {
            if (!(inspection instanceof Inspection observed)) {
                throw new IllegalArgumentException("Inspection belongs to another save subject");
            }
            requireUnchanged(observed.capture);
            return new Write(observed.capture, new SaveAuthorization.Approved(observed.target, replaceExternalChanges));
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
            Content accepted = Content.of(next);
            write.completed = true;
            source = next;
            acceptedDisk = accepted;
            externalDirty = false;
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
