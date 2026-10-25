package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.DocumentSave;
import dev.tuink.editor.storage.DocumentStorage;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.ResourceId;
import dev.tuink.editor.storage.SaveAuthorization;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.SaveTarget;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.editor.storage.StorageCapability;
import dev.tuink.editor.storage.StorageWriteException;
import dev.tuink.verification.MemoryDocumentStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Provider publication is insufficient acknowledgement when its receipt names another resource. */
class SaveReceiptResourceIdentityTest {
    private enum Operation { CURRENT, SAVE_AS }
    private enum ReceiptFault { NONE, WRONG_ID, FOREIGN_STORAGE, ID_ALLOCATION_FAILURE }

    @ParameterizedTest @EnumSource(Operation.class)
    void wrongResourceIdAfterPublicationCannotAdoptABindingOrSavepoint(Operation operation) throws Exception {
        assertUnconfirmed(operation, ReceiptFault.WRONG_ID);
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void foreignProviderReceiptAfterPublicationCannotAdoptABindingOrSavepoint(Operation operation) throws Exception {
        assertUnconfirmed(operation, ReceiptFault.FOREIGN_STORAGE);
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void receiptValidationAllocationFailureAfterPublicationRetainsAnUnknownOutcome(Operation operation) throws Exception {
        assertUnconfirmed(operation, ReceiptFault.ID_ALLOCATION_FAILURE);
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void aMatchingReceiptStillCompletesTheExpectedOperation(Operation operation) throws Exception {
        ReceiptStorage storage = new ReceiptStorage(ReceiptFault.NONE);
        EditorDocumentSession session = editedSession(storage);
        EditorDocumentSession.PreparedSave prepared = prepare(session, storage, operation);

        SavedDocument saved = worker(prepared::write);

        assertTrue(session.status().dirty(), "The worker must not publish the owner's savepoint");
        session.completeSaved(prepared, saved);
        assertFalse(session.status().dirty());
        assertEquals(storage.resource(targetName(operation)), session.status().binding().resource());
        assertArrayEquals(expectedBytes(operation), storage.delegate.bytes(targetName(operation)));
        if (operation == Operation.SAVE_AS) {
            assertTrue(session.undo(), "Approved UTF-8 conversion retains its format undo entry");
            assertEquals(StandardCharsets.UTF_16LE, session.status().metadata().charset());
        }
    }

    private static void assertUnconfirmed(Operation operation, ReceiptFault fault) throws Exception {
        ReceiptStorage storage = new ReceiptStorage(fault);
        EditorDocumentSession session = editedSession(storage);
        var beforeContent = session.snapshot();
        var beforeStatus = session.status();
        EditorDocumentSession.PreparedSave prepared = prepare(session, storage, operation);

        StorageWriteException failure = assertThrows(StorageWriteException.class, () -> worker(prepared::write));

        assertEquals(StorageWriteException.Outcome.UNKNOWN, failure.outcome());
        assertEquals(storage.resource(targetName(operation)), failure.resource());
        assertArrayEquals(expectedBytes(operation), storage.delegate.bytes(targetName(operation)),
            "The underlying provider really committed before returning the malformed receipt");
        assertSame(beforeContent, session.snapshot());
        assertEquals(beforeStatus, session.status());
        int calls = storage.delegate.calls();
        assertThrows(IllegalStateException.class, () -> session.completeSaved(prepared, storage.returnedReceipt));
        assertThrows(IllegalStateException.class, () -> worker(prepared::write));
        assertEquals(calls, storage.delegate.calls(), "Rejected completion/replay must perform no further I/O");
        if (fault == ReceiptFault.ID_ALLOCATION_FAILURE) {
            assertTrue(List.of(failure.getSuppressed()).contains(storage.receiptIdFailure));
        }

        assertTrue(session.redo(), "The preexisting redo branch must survive a rejected receipt");
        assertEquals(List.of("draft ", "original"), session.snapshot().lines());
        assertTrue(session.undo());
        assertEquals(beforeContent.lines(), session.snapshot().lines());
        assertTrue(session.undo());
        assertEquals(List.of("original"), session.snapshot().lines());
        assertFalse(session.status().dirty(), "The original savepoint must remain reachable");
        assertTrue(session.redo());
        assertEquals(beforeContent.lines(), session.snapshot().lines());
        assertTrue(session.status().dirty());
        assertEquals(beforeStatus.binding(), session.status().binding());
        assertEquals(beforeStatus.metadata(), session.status().metadata(), "Failed conversion must not enter format history");
        if (operation == Operation.SAVE_AS) {
            assertArrayEquals(originalBytes(), storage.delegate.bytes("source.txt"));
        }
    }

    private static EditorDocumentSession editedSession(ReceiptStorage storage) throws Exception {
        storage.delegate.put("source.txt", originalBytes());
        EditorDocumentSession session = EditorDocumentSession.create(new DocumentId("receipt-identity"), List.of(""));
        session.openPrepared(worker(() -> storage.load(storage.resource("source.txt"), DocumentOpenMode.DETECT, false)));
        edit(session, 0, "draft ");
        edit(session, 6, "\n");
        assertTrue(session.undo());
        return session;
    }

    private static EditorDocumentSession.PreparedSave prepare(EditorDocumentSession session, ReceiptStorage storage,
                                                               Operation operation) throws Exception {
        if (operation == Operation.CURRENT) return session.prepareSave();
        var captured = session.prepareSaveAsInput(storage.resource("copy.txt"), SaveDisposition.CREATE_NEW, true);
        return session.approveSaveAs(worker(captured::inspectTarget));
    }

    private static void edit(EditorDocumentSession session, int column, String inserted) {
        TextPosition at = new TextPosition(0, column);
        assertInstanceOf(WorkspaceEditResult.Applied.class, session.applyWorkspaceEdit(new WorkspaceEdit(
            session.documentId(), session.snapshot().revision(), List.of(new TextEdit(new TextRange(at, at), inserted)))));
    }

    private static String targetName(Operation operation) { return operation == Operation.CURRENT ? "source.txt" : "copy.txt"; }
    private static byte[] originalBytes() { return "\uFEFForiginal".getBytes(StandardCharsets.UTF_16LE); }
    private static byte[] expectedBytes(Operation operation) {
        return operation == Operation.CURRENT ? "\uFEFFdraft original".getBytes(StandardCharsets.UTF_16LE)
            : "draft original".getBytes(StandardCharsets.UTF_8);
    }

    private static <T> T worker(Callable<T> operation) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            try { return executor.submit(operation).get(); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof Exception exception) throw exception;
                throw (Error) failure.getCause();
            }
        } finally { executor.shutdownNow(); }
    }

    /** A delegating provider that corrupts only its acknowledgement after a real storage write. */
    private static final class ReceiptStorage implements DocumentStorage {
        private final MemoryDocumentStorage delegate = new MemoryDocumentStorage();
        private final ReceiptFault fault;
        private final OutOfMemoryError receiptIdFailure = new OutOfMemoryError("receipt identity allocation");
        private volatile SavedDocument returnedReceipt;

        ReceiptStorage(ReceiptFault fault) { this.fault = fault; }
        DocumentResource resource(String name) { return wrap(delegate.resource(name)); }
        private DocumentResource wrap(DocumentResource resource) { return new Handle(resource, this, false); }
        private Handle own(DocumentResource resource) throws IOException {
            if (!(resource instanceof Handle handle) || handle.owner != this) throw new IOException("Foreign resource");
            return handle;
        }

        @Override public DocumentLoad load(DocumentResource resource, DocumentOpenMode mode, boolean allowMissing)
            throws IOException {
            DocumentLoad loaded = delegate.load(own(resource).delegate, mode, allowMissing);
            return new DocumentLoad(wrap(loaded.resource()), loaded.lines(), loaded.metadata(), loaded.baseline(), loaded.disposition());
        }

        @Override public SaveTarget inspect(DocumentResource resource, ResourceBinding current) throws IOException {
            ResourceBinding binding = current;
            if (current.resource() instanceof Handle handle && handle.owner == this) {
                binding = new ResourceBinding(handle.delegate, current.disposition(), current.version());
            }
            SaveTarget target = delegate.inspect(own(resource).delegate, binding);
            return new Ticket(target, this, wrap(target.requestedResource()), wrap(target.physicalResource()));
        }

        @Override public boolean isCurrentTarget(DocumentResource resource, ResourceBinding current) throws IOException {
            return inspect(resource, current).currentTarget();
        }

        @Override public SavedDocument write(DocumentSave request, SaveAuthorization authorization) throws IOException {
            SaveAuthorization approved = authorization;
            if (authorization instanceof SaveAuthorization.Approved approval) {
                if (!(approval.target() instanceof Ticket ticket) || ticket.owner != this) throw new IOException("Foreign ticket");
                approved = new SaveAuthorization.Approved(ticket.delegate);
            }
            DocumentSave captured = new DocumentSave(own(request.resource()).delegate, request.lines(), request.metadata(),
                request.baseline(), request.disposition());
            SavedDocument saved = delegate.write(captured, approved);
            DocumentResource receiptResource = switch (fault) {
                case NONE -> wrap(saved.resource());
                case WRONG_ID -> resource("unrelated.txt");
                case FOREIGN_STORAGE -> saved.resource();
                case ID_ALLOCATION_FAILURE -> new Handle(saved.resource(), this, true);
            };
            returnedReceipt = new SavedDocument(receiptResource, saved.metadata(), saved.baseline(), saved.publicationKind());
            return returnedReceipt;
        }

        private record Handle(DocumentResource delegate, ReceiptStorage owner, boolean failIdentity) implements DocumentResource {
            @Override public ResourceId id() { if (failIdentity) throw owner.receiptIdFailure; return delegate.id(); }
            @Override public String displayName() { return delegate.displayName(); }
            @Override public String nameHint() { return delegate.nameHint(); }
            @Override public DocumentStorage storage() { return owner; }
        }

        private record Ticket(SaveTarget delegate, ReceiptStorage owner, DocumentResource requestedResource,
                              DocumentResource physicalResource) implements SaveTarget {
            @Override public boolean exists() { return delegate.exists(); }
            @Override public boolean currentTarget() { return delegate.currentTarget(); }
            @Override public Set<StorageCapability> capabilities() { return delegate.capabilities(); }
        }
    }
}
