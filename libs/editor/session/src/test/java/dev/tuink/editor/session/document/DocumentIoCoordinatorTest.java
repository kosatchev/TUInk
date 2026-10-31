package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.TabSession;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.storage.*;
import dev.tuink.verification.MemoryDocumentStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocumentIoCoordinatorTest {
    @Test
    void currentSaveUsesOnlyDetachedIoAndPublishesTheOriginalSessionOnce() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "source", "original");
        input(session, "draft ");
        var other = loaded(storage, "other", "other");
        int calls = storage.calls();
        var io = session.io();
        assertSame(io, session.io());
        var work = io.beginSave();
        assertEquals(calls, storage.calls());
        assertEquals(DocumentIoCoordinator.State.WRITING, io.state());
        var completion = worker(work::perform);
        assertTrue(completion.succeeded());
        assertEquals(DocumentIoCoordinator.CancelResult.DRAINING_WRITE, io.cancel(work));
        assertTrue(work.publishing());
        assertFalse(work.progress().cancellable());
        assertTrue(session.status().dirty());
        assertEquals("draft original", text(storage, "source"));
        calls = storage.calls();
        var saved = assertInstanceOf(DocumentIoCoordinator.Saved.class, io.accept(work, completion));
        assertEquals(session.documentId(), saved.documentId());
        assertEquals(work.operationId(), saved.operationId());
        assertEquals(calls, storage.calls());
        assertFalse(session.status().dirty());
        assertEquals(List.of("other"), other.snapshot().lines());
        assertInstanceOf(DocumentIoCoordinator.Ignored.class, io.accept(work, completion));
        assertThrows(IllegalStateException.class, work::perform);
        assertFalse(io.busy());
        assertTrue(session.undo());
        assertTrue(session.status().dirty());
        assertTrue(session.redo());
        assertFalse(session.status().dirty());
    }

    @Test
    void inspectionAndExactApprovalShareOneOperationWithoutOwnerIo() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "source", "source");
        storage.put("destination", "destination".getBytes(StandardCharsets.UTF_8));
        input(session, "draft ");
        var io = session.io();
        int calls = storage.calls();
        var inspect = io.beginSaveAs(storage.resource("destination"), SaveDisposition.REPLACE_EXISTING, false);
        assertEquals(calls, storage.calls());
        var completed = worker(inspect::perform);
        calls = storage.calls();
        var confirmation = assertInstanceOf(DocumentIoCoordinator.Approval.class,
            io.accept(inspect, completed)).confirmation();
        assertTrue(confirmation.requiresOverwriteConfirmation());
        assertEquals(storage.resource("destination"), confirmation.requestedResource());
        assertEquals(storage.resource("destination"), confirmation.physicalResource());
        assertEquals("destination", text(storage, "destination"));
        var write = assertInstanceOf(DocumentIoCoordinator.Ready.class, io.approve(confirmation)).work();
        assertEquals(inspect.operationId(), write.operationId());
        assertEquals(calls, storage.calls());
        assertInstanceOf(DocumentIoCoordinator.Ignored.class, io.approve(confirmation));
        assertInstanceOf(DocumentIoCoordinator.Saved.class, io.accept(write, worker(write::perform)));
        assertEquals("draft source", text(storage, "destination"));
        assertEquals("source", text(storage, "source"));
        assertEquals(storage.resource("destination"), session.status().binding().resource());
    }

    @ParameterizedTest
    @EnumSource(value = MemoryDocumentStorage.WriteFailure.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void definiteAndUnknownFailuresKeepTheDraftBindingHistoryAndSavepoint(MemoryDocumentStorage.WriteFailure failpoint)
        throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "source", "original");
        input(session, "draft ");
        var snapshot = session.snapshot();
        var status = session.status();
        storage.failNextWrite(failpoint);
        var work = session.io().beginSave();
        var completion = worker(work::perform);
        assertFalse(completion.succeeded());
        var failed = assertInstanceOf(DocumentIoCoordinator.Failed.class, session.io().accept(work, completion));
        boolean unknown = failpoint == MemoryDocumentStorage.WriteFailure.AFTER_COMMIT;
        assertEquals(unknown ? DocumentIoCoordinator.FailureKind.UNKNOWN
            : DocumentIoCoordinator.FailureKind.FAILED_BEFORE_COMMIT, failed.kind());
        assertSame(completion.failure(), failed.failure());
        assertEquals(unknown ? "draft original" : "original", text(storage, "source"));
        assertEquals(snapshot, session.snapshot());
        assertEquals(status, session.status());
        assertFalse(session.io().busy());
        assertEquals(snapshot.lines(), session.prepareRecovery(session.defaultViewId()).materialize().lines());
        assertTrue(session.undo());
        assertFalse(session.status().dirty());
        assertTrue(session.redo());
        assertTrue(session.status().dirty());
    }

    @Test
    void allRegisteredViewsAndTrustedMutationEntrypointsRespectTheSaveLock() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("source", "word word".getBytes(StandardCharsets.UTF_8));
        TabSession first = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
        var backend = CoreDocumentBackend.adopt(new DocumentId("shared"), first);
        TabSession second = first.engine().document().attach(LogicalEditorTextLayout::new, new EditorClipboard());
        var secondId = backend.registerView(second);
        var session = EditorDocumentSession.connect(backend);
        var loaded = worker(() -> storage.load(storage.resource("source"), DocumentOpenMode.DETECT, false));
        session.openPrepared(loaded);
        input(session, "draft ");
        var work = session.io().beginSave();
        assertBusy(session.execute(secondId, new DocumentCommand.Input("forbidden")));
        assertBusy(session.execute(secondId, new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO)));
        assertBusy(session.execute(secondId, new DocumentCommand.Search(DocumentCommand.SearchAction.REPLACE_ALL,
            "word", "other", SearchOptions.DEFAULT)));
        assertInstanceOf(DocumentCommandResult.Applied.class, session.execute(secondId,
            new DocumentCommand.Navigate(DocumentCommand.Navigation.DOCUMENT_END, false)));
        assertInstanceOf(DocumentCommandResult.Applied.class, session.execute(secondId,
            new DocumentCommand.Edit(DocumentCommand.EditAction.SELECT_ALL)));
        assertInstanceOf(DocumentCommandResult.Applied.class, session.execute(secondId,
            new DocumentCommand.Edit(DocumentCommand.EditAction.COPY)));
        assertInstanceOf(DocumentCommandResult.Applied.class, session.execute(secondId,
            new DocumentCommand.Search(DocumentCommand.SearchAction.NEXT, "word", "", SearchOptions.DEFAULT)));
        var snapshot = session.snapshot();
        var edit = new WorkspaceEdit(snapshot.documentId(), snapshot.revision(), List.of(
            new TextEdit(new TextRange(new TextPosition(0, 0), new TextPosition(0, 0)), "forbidden")));
        assertEquals(WorkspaceEditResult.RejectionReason.DOCUMENT_BUSY,
            assertInstanceOf(WorkspaceEditResult.Rejected.class, session.applyWorkspaceEdit(edit)).reason());
        assertThrows(IllegalStateException.class, session::undo);
        assertThrows(IllegalStateException.class, session::redo);
        assertThrows(IllegalStateException.class, session::prepareSave);
        assertThrows(IllegalStateException.class, () -> session.prepareSaveAsInput(storage.resource("copy"),
            SaveDisposition.CREATE_NEW, false));
        assertThrows(IllegalStateException.class, () -> session.openPrepared(loaded));
        var recovery = session.prepareRecovery(secondId).materialize();
        assertThrows(IllegalStateException.class, () -> session.restoreRecovery(secondId, recovery));
        assertEquals(List.of("draft word word"), session.snapshot().lines());
        assertInstanceOf(DocumentIoCoordinator.Saved.class, session.io().accept(work, worker(work::perform)));
        input(session, "after ");
        assertTrue(session.status().dirty());
    }

    @Test
    void foreignMismatchedAndWrongThreadCompletionsCannotPublish() throws Exception {
        var storage = new MemoryDocumentStorage();
        var first = loaded(storage, "first", "one");
        var second = loaded(storage, "second", "two");
        input(first, "draft ");
        input(second, "draft ");
        var a = first.io().beginSave();
        var b = second.io().beginSave();
        var aCompletion = worker(a::perform);
        var bCompletion = worker(b::perform);
        assertThrows(IllegalArgumentException.class, () -> second.io().accept(a, aCompletion));
        assertThrows(IllegalArgumentException.class, () -> first.io().cancel(b));
        assertThrows(IllegalArgumentException.class, () -> first.io().accept(a, bCompletion));
        assertThrows(IllegalStateException.class, () -> worker(() -> first.io().accept(a, aCompletion)));
        assertThrows(IllegalStateException.class, () -> worker(() -> first.io().beginSave()));
        assertTrue(first.status().dirty());
        assertTrue(second.status().dirty());
        assertInstanceOf(DocumentIoCoordinator.Saved.class, first.io().accept(a, aCompletion));
        assertInstanceOf(DocumentIoCoordinator.Saved.class, second.io().accept(b, bCompletion));
    }

    @Test
    void cancelledInspectionAndItsLateReplyCannotAffectTheNextOperation() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "source", "text");
        input(session, "draft ");
        var old = session.io().beginSaveAs(storage.resource("old"), SaveDisposition.CREATE_NEW, false);
        var oldCompletion = worker(old::perform);
        assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED, session.io().cancel());
        var current = session.io().beginSaveAs(storage.resource("new"), SaveDisposition.CREATE_NEW, false);
        assertTrue(current.operationId() > old.operationId());
        assertInstanceOf(DocumentIoCoordinator.Ignored.class, session.io().accept(old, oldCompletion));
        assertEquals(DocumentIoCoordinator.State.INSPECTING, session.io().state());
        var confirmation = assertInstanceOf(DocumentIoCoordinator.Approval.class,
            session.io().accept(current, worker(current::perform))).confirmation();
        assertFalse(confirmation.requiresOverwriteConfirmation());
        assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED, session.io().cancel());
        assertInstanceOf(DocumentIoCoordinator.Ignored.class, session.io().approve(confirmation));
        assertNull(storage.bytes("new"));
        assertNull(storage.bytes("old"));
        assertTrue(session.status().dirty());
        input(session, "after ");
    }

    @Test
    void anExclusiveCreateTargetThatAppearsBeforeInspectionIsNotOverwritten() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "source", "text");
        input(session, "draft ");
        var inspect = session.io().beginSaveAs(storage.resource("copy"), SaveDisposition.CREATE_NEW, false);
        storage.put("copy", "external".getBytes(StandardCharsets.UTF_8));
        var failed = assertInstanceOf(DocumentIoCoordinator.Failed.class,
            session.io().accept(inspect, worker(inspect::perform)));
        assertEquals(DocumentIoCoordinator.FailureKind.FAILED_BEFORE_COMMIT, failed.kind());
        assertEquals("external", text(storage, "copy"));
        assertTrue(session.status().dirty());
        assertFalse(session.io().busy());
    }

    @Test
    void staleWorkAndConfirmationCancellationCannotReleaseANewerOperation() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "source", "text");
        input(session, "draft ");
        var io = session.io();
        var oldWork = io.beginSaveAs(storage.resource("old"), SaveDisposition.CREATE_NEW, false);
        var oldConfirmation = assertInstanceOf(DocumentIoCoordinator.Approval.class,
            io.accept(oldWork, worker(oldWork::perform))).confirmation();
        assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED, io.cancel(oldConfirmation));
        var newWork = io.beginSave();

        assertEquals(DocumentIoCoordinator.CancelResult.IDLE, io.cancel(oldWork));
        assertEquals(DocumentIoCoordinator.CancelResult.IDLE, io.cancel(oldConfirmation));
        assertEquals(DocumentIoCoordinator.State.WRITING, io.state());
        assertBusy(session.execute(session.defaultViewId(), new DocumentCommand.Input("forbidden")));
        assertInstanceOf(DocumentIoCoordinator.Saved.class, io.accept(newWork, worker(newWork::perform)));
        assertEquals("draft text", text(storage, "source"));
        assertNull(storage.bytes("old"));
        assertFalse(session.status().dirty());
    }

    @Test
    void anOldInspectionAndDialogCannotCancelTheWriteTheyAlreadyApproved() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "source", "text");
        input(session, "draft ");
        var io = session.io();
        var inspect = io.beginSaveAs(storage.resource("copy"), SaveDisposition.CREATE_NEW, false);
        var confirmation = assertInstanceOf(DocumentIoCoordinator.Approval.class,
            io.accept(inspect, worker(inspect::perform))).confirmation();
        var write = assertInstanceOf(DocumentIoCoordinator.Ready.class, io.approve(confirmation)).work();

        assertEquals(DocumentIoCoordinator.CancelResult.IDLE, io.cancel(inspect));
        assertEquals(DocumentIoCoordinator.CancelResult.IDLE, io.cancel(confirmation));
        assertBusy(session.execute(session.defaultViewId(), new DocumentCommand.Input("forbidden")));
        assertInstanceOf(DocumentIoCoordinator.Saved.class, io.accept(write, worker(write::perform)));
        assertEquals("draft text", text(storage, "copy"));
        assertFalse(session.status().dirty());
    }

    @Test
    void executorRejectionAndQueuedCancellationCannotStartExternalWrites() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "source", "text");
        input(session, "draft ");
        int calls = storage.calls();
        var rejected = session.io().beginSave();
        IOException error = new IOException("worker refused submission");
        var failed = assertInstanceOf(DocumentIoCoordinator.Failed.class, session.io().reject(rejected, error));
        assertSame(error, failed.failure());
        assertEquals(DocumentIoCoordinator.FailureKind.FAILED_BEFORE_COMMIT, failed.kind());
        assertInstanceOf(DocumentIoCoordinator.Ignored.class, session.io().accept(rejected, worker(rejected::perform)));
        var cancelled = session.io().beginSave();
        assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED, session.io().cancel());
        assertFalse(worker(cancelled::perform).succeeded());
        assertEquals(calls, storage.calls());
        assertEquals("text", text(storage, "source"));
        assertTrue(session.status().dirty());
        assertFalse(session.io().busy());
    }

    @ParameterizedTest
    @EnumSource(value = BlockingStorage.Phase.class, names = {"OPAQUE_WRITE", "PUBLICATION"})
    void stoppingDuringPublicationDrainsItBeforeReleasingTheDraftLock(BlockingStorage.Phase phase) throws Exception {
        var memory = new MemoryDocumentStorage();
        var storage = new BlockingStorage(memory, phase);
        memory.put("source", "text".getBytes(StandardCharsets.UTF_8));
        var resource = storage.resource("source");
        var session = EditorDocumentSession.create(new DocumentId("draining"), List.of(""));
        session.openPrepared(worker(() -> storage.load(resource, DocumentOpenMode.DETECT, false)));
        input(session, "draft ");
        var work = session.io().beginSave();
        AtomicReference<DocumentIoCoordinator.Completion> completion = new AtomicReference<>();
        Thread writer = new Thread(() -> completion.set(work.perform()), "coordinator-draining-test");
        writer.start();
        try {
            assertTrue(storage.started.await(5, TimeUnit.SECONDS));
            assertTrue(work.publishing());
            assertEquals(DocumentIoCoordinator.CancelResult.DRAINING_WRITE, session.io().stop());
            assertEquals(DocumentIoCoordinator.State.WRITING, session.io().state());
            assertBusy(session.execute(session.defaultViewId(), new DocumentCommand.Input("forbidden")));
            assertThrows(IllegalStateException.class, () -> session.io().reject(work, new IOException("fake rejection")));
        } finally {
            storage.release.countDown();
            writer.join(5_000L);
        }
        assertFalse(writer.isAlive());
        assertTrue(session.status().dirty());
        assertInstanceOf(DocumentIoCoordinator.Saved.class, session.io().accept(work, completion.get()));
        assertFalse(session.status().dirty());
        assertEquals(DocumentIoCoordinator.State.STOPPED, session.io().state());
        assertThrows(IllegalStateException.class, () -> session.io().beginSave());
        assertEquals("draft text", text(memory, "source"));
    }

    @Test
    void cancellingStartedPreparationRetainsDraftHistoryAndAllowsAnotherSave() throws Exception {
        var memory = new MemoryDocumentStorage();
        var storage = new BlockingStorage(memory, BlockingStorage.Phase.PREPARATION);
        memory.put("source", "original".getBytes(StandardCharsets.UTF_8));
        var session = EditorDocumentSession.create(new DocumentId("preparing"), List.of(""));
        session.openPrepared(worker(() -> storage.load(storage.resource("source"), DocumentOpenMode.DETECT, false)));
        input(session, "draft ");
        var work = session.io().beginSave();
        AtomicReference<DocumentIoCoordinator.Completion> completion = new AtomicReference<>();
        Thread writer = new Thread(() -> completion.set(work.perform()), "coordinator-preparation-test");
        writer.start();
        try {
            assertTrue(storage.started.await(5, TimeUnit.SECONDS));
            assertEquals(StorageOperation.Phase.STAGING, work.progress().phase());
            assertTrue(work.progress().cancellable());
            assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED, session.io().cancel(work));
            assertFalse(session.io().busy());
            input(session, "newer ");
        } finally {
            storage.release.countDown();
            writer.join(5_000);
        }
        assertFalse(writer.isAlive());
        assertTrue(completion.get().cancelled());
        assertInstanceOf(DocumentIoCoordinator.Ignored.class, session.io().accept(work, completion.get()));
        assertEquals("original", text(memory, "source"));
        assertEquals(List.of("draft newer original"), session.snapshot().lines());
        assertTrue(session.status().dirty());
        assertTrue(session.undo());
        assertTrue(session.redo());
        var next = session.io().beginSave();
        assertInstanceOf(DocumentIoCoordinator.Saved.class, session.io().accept(next, worker(next::perform)));
        assertEquals("draft newer original", text(memory, "source"));
        assertFalse(session.status().dirty());
        assertEquals(DocumentIoCoordinator.CancelResult.IDLE, session.io().cancel(next));
    }

    @Test
    void aDeferredCancelAfterADefinitePublicationFailureReleasesThePreparation() throws Exception {
        var memory = new MemoryDocumentStorage();
        var storage = new BlockingStorage(memory, BlockingStorage.Phase.RETRY_AFTER_PUBLICATION);
        memory.put("source", "original".getBytes(StandardCharsets.UTF_8));
        var session = EditorDocumentSession.create(new DocumentId("fallback"), List.of(""));
        session.openPrepared(worker(() -> storage.load(storage.resource("source"), DocumentOpenMode.DETECT, false)));
        input(session, "draft ");
        var work = session.io().beginSave();
        AtomicReference<DocumentIoCoordinator.Completion> completion = new AtomicReference<>();
        Thread writer = new Thread(() -> completion.set(work.perform()), "coordinator-fallback-test");
        writer.start();
        try {
            assertTrue(storage.started.await(5, TimeUnit.SECONDS));
            assertEquals(DocumentIoCoordinator.CancelResult.DRAINING_WRITE, session.io().cancel(work));
            assertTrue(session.io().busy());
        } finally {
            storage.release.countDown();
            writer.join(5_000);
        }
        assertFalse(writer.isAlive());
        assertTrue(completion.get().cancelled());
        assertInstanceOf(DocumentIoCoordinator.Ignored.class, session.io().accept(work, completion.get()));
        assertFalse(session.io().busy());
        assertTrue(session.status().dirty());
        assertEquals("original", text(memory, "source"));
    }

    @Test
    void stoppingInspectionPreventsApprovalAndDoesNotDiscardItsDraft() throws Exception {
        var storage = new MemoryDocumentStorage();
        var session = loaded(storage, "source", "text");
        input(session, "draft ");
        var inspect = session.io().beginSaveAs(storage.resource("copy"), SaveDisposition.CREATE_NEW, false);
        var completed = worker(inspect::perform);
        assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED, session.io().stop());
        assertInstanceOf(DocumentIoCoordinator.Ignored.class, session.io().accept(inspect, completed));
        assertEquals(DocumentIoCoordinator.State.STOPPED, session.io().state());
        assertNull(storage.bytes("copy"));
        assertTrue(session.status().dirty());
        assertEquals(List.of("draft text"), session.prepareRecovery(session.defaultViewId()).materialize().lines());
    }

    @Test
    void confirmedWriteWithAnUnpublishableSavepointRetainsTheNewerDraft() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("source", "text".getBytes(StandardCharsets.UTF_8));
        TabSession view = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
        var session = EditorDocumentSession.connect(CoreDocumentBackend.adopt(new DocumentId("trusted-host"), view));
        session.openPrepared(worker(() -> storage.load(storage.resource("source"), DocumentOpenMode.DETECT, false)));
        input(session, "draft ");
        var work = session.io().beginSave();
        var completion = worker(work::perform);
        // A trusted composition host bypassed the session boundary. Never mark its newer text saved.
        view.engine().insertInput("newer ");
        var failed = assertInstanceOf(DocumentIoCoordinator.Failed.class, session.io().accept(work, completion));
        assertEquals(DocumentIoCoordinator.FailureKind.CONFIRMED_NOT_PUBLISHED, failed.kind());
        assertTrue(session.status().dirty());
        assertEquals(List.of("draft newer text"), session.snapshot().lines());
        assertEquals("draft text", text(storage, "source"));
        assertFalse(session.io().busy());
    }

    private static void assertBusy(DocumentCommandResult result) {
        assertEquals(DocumentCommandResult.RejectionReason.DOCUMENT_BUSY,
            assertInstanceOf(DocumentCommandResult.Rejected.class, result).reason());
    }

    private static void input(EditorDocumentSession session, String text) {
        assertInstanceOf(DocumentCommandResult.Applied.class,
            session.execute(session.defaultViewId(), new DocumentCommand.Input(text)));
    }

    private static EditorDocumentSession loaded(MemoryDocumentStorage storage, String name, String text) throws Exception {
        storage.put(name, text.getBytes(StandardCharsets.UTF_8));
        var session = EditorDocumentSession.create(new DocumentId(name), List.of(""));
        session.openPrepared(worker(() -> storage.load(storage.resource(name), DocumentOpenMode.DETECT, false)));
        return session;
    }

    private static String text(MemoryDocumentStorage storage, String name) {
        return new String(storage.bytes(name), StandardCharsets.UTF_8);
    }

    private static <T> T worker(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { value.set(action.call()); }
            catch (Throwable thrown) { failure.set(thrown); }
        }, "document-coordinator-test");
        thread.start();
        thread.join(5_000L);
        assertFalse(thread.isAlive(), "worker did not finish");
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() instanceof Error error) throw error;
        return value.get();
    }

    /** Delays the actual provider write after the coordinator has atomically claimed it. */
    private static final class BlockingStorage implements DocumentStorage {
        private enum Phase { OPAQUE_WRITE, PREPARATION, PUBLICATION, RETRY_AFTER_PUBLICATION }
        private final MemoryDocumentStorage delegate;
        private final Phase phase;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private BlockingStorage(MemoryDocumentStorage delegate) { this(delegate, Phase.OPAQUE_WRITE); }
        private BlockingStorage(MemoryDocumentStorage delegate, Phase phase) {
            this.delegate = delegate;
            this.phase = phase;
        }
        private DocumentResource resource(String name) { return new Handle(delegate.resource(name), this); }
        private DocumentResource unwrap(DocumentResource resource) { return ((Handle) resource).delegate; }
        private DocumentResource wrap(DocumentResource resource) { return new Handle(resource, this); }
        @Override public DocumentLoad load(DocumentResource resource, DocumentOpenMode mode, boolean missing) throws IOException {
            var loaded = delegate.load(unwrap(resource), mode, missing);
            return new DocumentLoad(wrap(loaded.resource()), loaded.lines(), loaded.metadata(), loaded.baseline(), loaded.disposition());
        }
        @Override public SaveTarget inspect(DocumentResource resource, ResourceBinding binding) throws IOException {
            throw new IOException("Not used by the current-save test");
        }
        @Override public boolean isCurrentTarget(DocumentResource resource, ResourceBinding binding) { return false; }
        @Override public SavedDocument write(DocumentSave request, SaveAuthorization authorization) throws IOException {
            awaitRelease();
            return writeDelegate(request, authorization);
        }
        @Override public SavedDocument write(DocumentSave request, SaveAuthorization authorization, StorageOperation operation)
            throws IOException {
            if (phase == Phase.OPAQUE_WRITE) return DocumentStorage.super.write(request, authorization, operation);
            if (phase == Phase.PREPARATION) operation.progress(StorageOperation.Phase.STAGING, 0, -1);
            else operation.beginPublication();
            awaitRelease();
            if (phase == Phase.RETRY_AFTER_PUBLICATION) operation.resumePreparation();
            operation.beginPublication();
            return writeDelegate(request, authorization);
        }
        private void awaitRelease() throws IOException {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("write was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }
        private SavedDocument writeDelegate(DocumentSave request, SaveAuthorization authorization) throws IOException {
            var unwrapped = new DocumentSave(unwrap(request.resource()), request.lines(), request.metadata(),
                request.baseline(), request.disposition());
            var saved = delegate.write(unwrapped, authorization);
            return new SavedDocument(wrap(saved.resource()), saved.metadata(), saved.baseline(), saved.publicationKind());
        }
        private record Handle(DocumentResource delegate, BlockingStorage storage) implements DocumentResource {
            @Override public ResourceId id() { return delegate.id(); }
            @Override public String displayName() { return delegate.displayName(); }
            @Override public String nameHint() { return delegate.nameHint(); }
        }
    }
}
