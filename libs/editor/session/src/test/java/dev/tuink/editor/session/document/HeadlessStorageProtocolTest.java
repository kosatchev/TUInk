package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.DocumentEditLimitException;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorDocument;
import dev.tuink.core.LineText;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.*;
import dev.tuink.editor.storage.*;
import dev.tuink.verification.MemoryDocumentStorage;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HeadlessStorageProtocolTest {
    @Test
    void publicSessionRoundTripDoesNotUseAFileProviderAndOwnerPhasesDoNoIo() throws Exception {
        MemoryDocumentStorage storage = new MemoryDocumentStorage();
        storage.put("source.java", "original\r\ntail\r".getBytes(StandardCharsets.UTF_8));
        var source = storage.resource("source.java");
        var session = session();
        DocumentLoad loaded = worker(() -> storage.load(source, DocumentOpenMode.DETECT, false));
        int calls = storage.calls();
        session.openPrepared(loaded);
        assertEquals(calls, storage.calls());
        assertEquals(List.of("original", "tail", ""), session.snapshot().lines());
        prepend(session, "draft ");
        long revision = session.snapshot().revision();
        var operation = session.prepareSave();
        assertEquals(calls, storage.calls());
        var receipt = worker(operation::write);
        assertTrue(session.status().dirty(), "worker commit does not publish an owner savepoint");
        calls = storage.calls();
        session.completeSaved(operation, receipt);
        assertEquals(calls, storage.calls());
        assertFalse(session.status().dirty());
        assertEquals(revision, session.snapshot().revision());
        assertArrayEquals("draft original\r\ntail\r".getBytes(StandardCharsets.UTF_8), storage.bytes("source.java"));
        assertTrue(session.undo());
        assertTrue(session.status().dirty());
        assertTrue(session.redo());
        assertFalse(session.status().dirty());

        var view = CoreSessionBridge.attach(session, LogicalEditorTextLayout::new, new EditorClipboard());
        prepend(session, "unsaved ");
        var checkpoint = view.engine().captureRecoverySnapshot();
        var recovered = session();
        var recoveredView = CoreSessionBridge.attach(recovered, LogicalEditorTextLayout::new, new EditorClipboard());
        recoveredView.engine().restoreRecoverySnapshot(checkpoint);
        assertEquals(session.snapshot().lines(), recovered.snapshot().lines());
        var recoveredSave = recovered.prepareSave();
        recovered.completeSaved(recoveredSave, worker(recoveredSave::write));
        assertEquals("unsaved draft original\r\ntail\r", new String(storage.bytes("source.java"), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @EnumSource(value = MemoryDocumentStorage.WriteFailure.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void failureAndUncertainCommitRetainDraftBindingSavepointAndHistory(MemoryDocumentStorage.WriteFailure failure)
        throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "document.txt", "original");
        prepend(session, "draft ");
        var content = session.snapshot();
        var status = session.status();
        storage.failNextWrite(failure);
        var operation = session.prepareSave();
        IOException error = assertThrows(IOException.class, () -> worker(operation::write));
        if (failure == MemoryDocumentStorage.WriteFailure.AFTER_COMMIT) {
            assertEquals(StorageWriteException.Outcome.UNKNOWN, assertInstanceOf(StorageWriteException.class, error).outcome());
            assertEquals("draft original", new String(storage.bytes("document.txt"), StandardCharsets.UTF_8));
        } else assertEquals("original", new String(storage.bytes("document.txt"), StandardCharsets.UTF_8));
        assertEquals(content, session.snapshot());
        assertEquals(status, session.status());
        assertTrue(session.undo());
        assertEquals("original", session.snapshot().line(0));
        assertFalse(session.status().dirty());
        assertTrue(session.redo());
        assertEquals("draft original", session.snapshot().line(0));
        assertTrue(session.status().dirty());
    }

    @Test
    void saveAsApprovalAndCompletionAreBoundToTheOriginalOperation() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "original.txt", "text");
        prepend(session, "draft ");
        int calls = storage.calls();
        var captured = session.prepareSaveAsInput(storage.resource("new.txt"), SaveDisposition.CREATE_NEW, false);
        assertEquals(calls, storage.calls());
        var inspected = worker(captured::inspectTarget);
        calls = storage.calls();
        var approved = session.approveSaveAs(inspected);
        assertEquals(calls, storage.calls());
        SavedDocument receipt = worker(approved::write);
        var foreignCopy = new SavedDocument(receipt.resource(), receipt.metadata(), receipt.baseline(), receipt.publicationKind());
        assertThrows(IllegalStateException.class, () -> session.completeSaved(approved, foreignCopy));
        assertTrue(session.status().dirty());
        session.completeSaved(approved, receipt);
        assertThrows(IllegalStateException.class, () -> session.completeSaved(approved, receipt));
        assertThrows(IllegalStateException.class, () -> worker(approved::write));
        assertEquals(storage.resource("new.txt"), session.status().binding().resource());
        assertEquals("text", new String(storage.bytes("original.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void staleApprovalAndRevokedPermissionDoNotPublishOwnerState() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "document.txt", "text");
        var plan = session.prepareSaveAsInput(storage.resource("copy.txt"), SaveDisposition.CREATE_NEW, false);
        var inspected = worker(plan::inspectTarget);
        prepend(session, "changed ");
        assertThrows(IOException.class, () -> session.approveSaveAs(inspected));
        assertNull(storage.bytes("copy.txt"));
        var status = session.status();
        var content = session.snapshot();
        var save = session.prepareSave();
        storage.writable(false);
        assertThrows(IOException.class, () -> worker(save::write));
        assertEquals(status, session.status());
        assertEquals(content, session.snapshot());
    }

    @Test
    void approvalRejectsIdenticalByteReplacementWhileCurrentSaveRetainsByteBaselineSemantics() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "document.txt", "original");
        prepend(session, "draft ");
        var capture = session.prepareSaveAsInput(storage.resource("document.txt"), SaveDisposition.REPLACE_EXISTING, false);
        var inspected = worker(capture::inspectTarget);
        storage.put("document.txt", "original".getBytes(StandardCharsets.UTF_8));
        var approved = session.approveSaveAs(inspected);
        assertThrows(IOException.class, () -> worker(approved::write));
        assertTrue(session.status().dirty());
        var current = session.prepareSave();
        session.completeSaved(current, worker(current::write));
        assertFalse(session.status().dirty());
    }

    @Test
    void exportToAnotherProviderDoesNotTreatItsResourceAsAnOwnedSource() throws Exception {
        var source = new MemoryDocumentStorage();
        var destination = new MemoryDocumentStorage();
        var session = loaded(source, "document.txt", "original");
        prepend(session, "export ");
        var capture = session.prepareSaveAsInput(destination.resource("document.txt"), SaveDisposition.CREATE_NEW, false);
        var inspected = worker(capture::inspectTarget);
        assertFalse(inspected.currentTarget());
        var approved = session.approveSaveAs(inspected);
        session.completeSaved(approved, worker(approved::write));
        assertEquals("original", new String(source.bytes("document.txt"), StandardCharsets.UTF_8));
        assertEquals("export original", new String(destination.bytes("document.txt"), StandardCharsets.UTF_8));
        assertSame(destination, session.status().binding().resource().storage());
    }

    @Test
    void encodingFailureDoesNotAddFormatUndoAndSuccessfulConversionDoes() throws Exception {
        var storage = new MemoryDocumentStorage();
        Charset legacy = Charset.forName("windows-1251");
        storage.put("legacy.txt", "Привет".getBytes(legacy));
        var session = session();
        session.openPrepared(worker(() -> storage.load(storage.resource("legacy.txt"), DocumentOpenMode.DETECT, false)));
        prepend(session, "😀");
        var before = session.status();
        var failed = session.prepareSave();
        assertThrows(IOException.class, () -> worker(failed::write));
        assertEquals(before, session.status());
        var conversion = session.prepareSaveAsInput(storage.resource("legacy.txt"), SaveDisposition.REPLACE_EXISTING, true);
        var inspected = worker(conversion::inspectTarget);
        var save = session.approveSaveAs(inspected);
        session.completeSaved(save, worker(save::write));
        assertEquals(StandardCharsets.UTF_8, session.status().metadata().charset());
        assertEquals("😀Привет", new String(storage.bytes("legacy.txt"), StandardCharsets.UTF_8));
        assertTrue(session.undo());
        assertEquals(legacy, session.status().metadata().charset());
        assertEquals("😀Привет", session.snapshot().line(0));
        assertTrue(session.undo());
        assertEquals("Привет", session.snapshot().line(0));
    }

    @Test
    void failingOpenPublicationRetainsPreviousContentBindingBaselineAndRedo() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("old.txt", "old".getBytes(StandardCharsets.UTF_8));
        storage.put("new.txt", "new".getBytes(StandardCharsets.UTF_8));
        class FailingModel extends TextModel {
            boolean fail;
            FailingModel() { super(new SimpleLineBuffer()); }
            @Override public List<LineText> snapshotTextLines() {
                if (fail) throw new OutOfMemoryError("open baseline allocation");
                return super.snapshotTextLines();
            }
        }
        var model = new FailingModel();
        var session = new EditorDocumentSession(new DocumentId("open-rollback"), new EditorDocument(model));
        session.openPrepared(worker(() -> storage.load(storage.resource("old.txt"), DocumentOpenMode.DETECT, false)));
        prepend(session, "draft ");
        assertTrue(session.undo());
        var content = session.snapshot();
        var status = session.status();
        var incoming = worker(() -> storage.load(storage.resource("new.txt"), DocumentOpenMode.DETECT, false));
        model.fail = true;
        try { assertThrows(DocumentEditLimitException.class, () -> session.openPrepared(incoming)); }
        finally { model.fail = false; }
        assertEquals(content, session.snapshot());
        assertEquals(status, session.status());
        assertTrue(session.redo());
        assertEquals("draft old", session.snapshot().line(0));
    }

    @Test
    void foreignThreadCannotCaptureApproveOrCompleteOwnerOperations() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "document.txt", "text");
        int calls = storage.calls();
        assertThrows(IllegalStateException.class, () -> worker(session::status));
        assertThrows(IllegalStateException.class, () -> worker(session::prepareSave));
        assertEquals(calls, storage.calls());
        var capture = session.prepareSaveAsInput(storage.resource("copy.txt"), SaveDisposition.CREATE_NEW, false);
        var inspected = worker(capture::inspectTarget);
        calls = storage.calls();
        assertThrows(IllegalStateException.class, () -> worker(() -> session.approveSaveAs(inspected)));
        assertEquals(calls, storage.calls());
        var prepared = session.approveSaveAs(inspected);
        var receipt = worker(prepared::write);
        calls = storage.calls();
        assertThrows(IllegalStateException.class, () -> worker(() -> {
            session.completeSaved(prepared, receipt);
            return null;
        }));
        assertEquals(calls, storage.calls());
        assertEquals(storage.resource("document.txt"), session.status().binding().resource());
        session.completeSaved(prepared, receipt);
        assertEquals(storage.resource("copy.txt"), session.status().binding().resource());
    }

    private static EditorDocumentSession session() {
        return EditorDocumentSession.create(new DocumentId("headless"), List.of(""));
    }

    private static EditorDocumentSession loaded(MemoryDocumentStorage storage, String name, String content) throws Exception {
        storage.put(name, content.getBytes(StandardCharsets.UTF_8));
        var session = session();
        session.openPrepared(worker(() -> storage.load(storage.resource(name), DocumentOpenMode.DETECT, false)));
        return session;
    }

    private static void prepend(EditorDocumentSession session, String text) {
        var start = new TextPosition(0, 0);
        assertInstanceOf(WorkspaceEditResult.Applied.class, session.applyWorkspaceEdit(new WorkspaceEdit(
            session.documentId(), session.snapshot().revision(), List.of(new TextEdit(new TextRange(start, start), text)))));
    }

    private static <T> T worker(Callable<T> action) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            try { return executor.submit(action).get(); }
            catch (ExecutionException failed) {
                if (failed.getCause() instanceof Exception cause) throw cause;
                throw (Error) failed.getCause();
            }
        } finally { executor.shutdownNow(); }
    }
}
