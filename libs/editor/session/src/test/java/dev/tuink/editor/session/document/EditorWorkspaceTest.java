package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

class EditorWorkspaceTest {
    @Test
    void membershipHasOneLiveReadOnlyProjectionAndSelectionUsesViewIdentity() {
        var workspace = new EditorWorkspace<String>();
        var first = session("first");
        var second = session("second");
        var third = session("third");
        var attachments = workspace.attachments();
        assertNull(workspace.active());
        assertEquals(-1, workspace.activeIndex());
        workspace.add(first, first.defaultViewId(), "first");
        workspace.add(second, second.defaultViewId(), "second");
        workspace.addAll(List.of(entry(third, "third")));
        assertSame(attachments, workspace.attachments());
        assertEquals(List.of("first", "second", "third"), attachments);
        assertEquals("second", workspace.active().attachment());
        assertThrows(UnsupportedOperationException.class, () -> attachments.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> workspace.entries().clear());
        workspace.remove(first.defaultViewId());
        assertEquals(0, workspace.activeIndex());
        assertSame(second, workspace.active().document());
        workspace.updateAttachment(second.defaultViewId(), "new second renderer");
        assertEquals("new second renderer", workspace.active().attachment());
        workspace.remove(second.defaultViewId());
        assertSame(third, workspace.active().document());
        workspace.remove(third.defaultViewId());
        assertTrue(attachments.isEmpty());
        assertNull(workspace.active());
        assertEquals(-1, workspace.activeIndex());
    }

    @Test
    void invalidBatchPublishesNeitherItsFirstItemNorAnyOwnership() {
        var workspace = new EditorWorkspace<String>();
        var existing = session("existing");
        workspace.add(existing, existing.defaultViewId(), "existing");
        var incoming = session("incoming");
        var before = workspace.entries();
        assertThrows(IllegalArgumentException.class,
            () -> workspace.addAll(List.of(entry(incoming, "first staged"), entry(existing, "duplicate"))));
        assertSame(before, workspace.entries());
        assertSame(existing, workspace.active().document());
        assertNull(incoming.workspace());
        assertTrue(incoming.ownsView(incoming.defaultViewId()));

        var collision = session("existing");
        assertThrows(IllegalArgumentException.class,
            () -> workspace.addAll(List.of(entry(incoming, "first staged"), entry(collision, "same document ID"))));
        assertSame(before, workspace.entries());
        assertNull(incoming.workspace());
        assertNull(collision.workspace());
    }

    @Test
    void rejectedAdmissionAllowsRetiringThePreparedViewOfAnOwnedDocument() {
        var workspace = new EditorWorkspace<String>();
        var document = EditorDocumentSession.create(new DocumentId("prepared-view"), List.of("abcdef"));
        var original = document.defaultViewId();
        workspace.add(document, original, "original");
        applied(document.execute(original, new DocumentCommand.MoveTo(new TextPosition(0, 3), false)));
        applied(document.execute(original, new DocumentCommand.Input("X")));
        applied(document.execute(original, new DocumentCommand.MoveTo(new TextPosition(0, 6), true)));
        var prepared = document.attachView();
        var target = document.commandTarget(prepared);
        var membership = workspace.entries();
        var selected = workspace.active();
        var snapshot = document.snapshot();
        var status = document.status();
        var originalView = document.view(original);

        assertThrows(IllegalArgumentException.class, () -> workspace.addAll(List.of(
            new EditorWorkspace.Entry<>(document, prepared, "prepared"),
            new EditorWorkspace.Entry<>(document, original, "duplicate"))));
        assertNull(workspace.find(prepared));
        assertTrue(document.ownsView(prepared));

        document.detachView(prepared);

        assertFalse(document.ownsView(prepared));
        assertSame(membership, workspace.entries());
        assertSame(selected, workspace.active());
        assertSame(workspace, document.workspace());
        assertSame(document, workspace.document(document.documentId()));
        assertSame(snapshot, document.snapshot());
        assertEquals(status, document.status());
        assertEquals(originalView, document.view(original));
        assertEquals(DocumentCommandResult.RejectionReason.UNKNOWN_VIEW,
            assertInstanceOf(DocumentCommandResult.Rejected.class,
                document.execute(target, new DocumentCommand.Input("stale"))).reason());
        assertThrows(IllegalArgumentException.class, () -> workspace.add(document, prepared, "retired"));
        assertThrows(IllegalStateException.class, () -> document.detachView(original));
        assertTrue(document.undo());
        assertEquals(List.of("abcdef"), document.snapshot().lines());
        assertTrue(document.redo());
        assertEquals(List.of("abcXdef"), document.snapshot().lines());
    }

    @Test
    void retiringAnUnadmittedViewLeavesTheCapturedSaveAndItsDocumentIntact() throws Exception {
        var storage = new MemoryDocumentStorage();
        var document = loaded(storage, "pending.txt");
        var workspace = new EditorWorkspace<String>();
        var original = document.defaultViewId();
        workspace.add(document, original, "original");
        applied(document.execute(original, new DocumentCommand.Input("draft ")));
        var prepared = document.attachView();
        var snapshot = document.snapshot();
        var status = document.status();
        var originalView = document.view(original);
        var membership = workspace.entries();
        int storageCalls = storage.calls();
        var work = document.io().beginSave();

        document.detachView(prepared);

        assertFalse(document.ownsView(prepared));
        assertSame(membership, workspace.entries());
        assertEquals(original, workspace.active().viewId());
        assertSame(snapshot, document.snapshot());
        assertEquals(status, document.status());
        assertEquals(originalView, document.view(original));
        assertTrue(document.io().busy());
        assertEquals(DocumentIoCoordinator.State.WRITING, document.io().state());
        assertThrows(IllegalStateException.class, () -> workspace.remove(original));
        assertThrows(IllegalStateException.class, () -> document.detachView(original));
        assertEquals(storageCalls, storage.calls(), "capture and view retirement must not call storage");
        assertArrayEquals("text".getBytes(StandardCharsets.UTF_8), storage.bytes("pending.txt"));
        assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED, document.io().cancel(work));
        assertFalse(document.io().busy());
        assertSame(snapshot, document.snapshot());
    }

    @Test
    void failingBatchMaterializationKeepsMembershipSelectionAndUnclaimedDraft() {
        var workspace = new EditorWorkspace<String>();
        var existing = session("existing");
        var incoming = session("incoming");
        workspace.add(existing, existing.defaultViewId(), "existing");
        var before = workspace.entries();
        OutOfMemoryError failure = new OutOfMemoryError("batch materialization");
        List<EditorWorkspace.Entry<String>> batch = new AbstractList<>() {
            @Override public int size() { return 2; }
            @Override public EditorWorkspace.Entry<String> get(int index) {
                if (index == 0) return entry(incoming, "incoming");
                throw failure;
            }
        };
        assertSame(failure, assertThrows(OutOfMemoryError.class, () -> workspace.addAll(batch)));
        assertSame(before, workspace.entries());
        assertSame(existing, workspace.active().document());
        assertNull(incoming.workspace());
        assertEquals(List.of("text"), incoming.snapshot().lines());
    }

    @Test
    void oneSessionCannotBeClaimedByTwoWorkspaces() {
        var original = new EditorWorkspace<String>();
        var other = new EditorWorkspace<String>();
        var shared = session("shared");
        var unattached = session("unattached");
        original.add(shared, shared.defaultViewId(), "original");
        ViewId secondView = shared.attachView();
        assertThrows(IllegalStateException.class, () -> other.addAll(List.of(
            entry(unattached, "unattached"), new EditorWorkspace.Entry<>(shared, secondView, "other view"))));
        assertNull(unattached.workspace());
        assertTrue(other.entries().isEmpty());
        assertSame(original, shared.workspace());
        assertThrows(IllegalStateException.class, () -> shared.detachView(shared.defaultViewId()));
        assertSame(shared, original.active().document());
    }

    @Test
    void retiringAViewRejectsOldCommandsAndNeverResurrectsItsIdentity() {
        var workspace = new EditorWorkspace<String>();
        var shared = session("retired");
        var originalId = shared.defaultViewId();
        var target = shared.commandTarget(originalId);
        workspace.add(shared, originalId, "original");
        var secondId = shared.attachView();
        workspace.add(shared, secondId, "second");
        workspace.remove(originalId);
        assertFalse(shared.ownsView(originalId));
        assertEquals(DocumentCommandResult.RejectionReason.UNKNOWN_VIEW,
            assertInstanceOf(DocumentCommandResult.Rejected.class,
                shared.execute(target, new DocumentCommand.Input("stale"))).reason());
        assertThrows(IllegalArgumentException.class, () -> workspace.add(shared, originalId, "resurrected"));
        assertThrows(IllegalArgumentException.class, () -> workspace.select(originalId));
        assertNull(workspace.remove(originalId));
        assertEquals(secondId, workspace.active().viewId());
        assertSame(shared, workspace.document(shared.documentId()));
    }

    @Test
    void viewsShareHistoryAndRetainIndependentPositionsAfterTheFirstViewCloses() {
        var workspace = new EditorWorkspace<String>();
        var shared = EditorDocumentSession.create(new DocumentId("shared-history"), List.of("abcdef"));
        var first = shared.defaultViewId();
        var second = shared.attachView();
        workspace.add(shared, first, "first layout");
        workspace.add(shared, second, "second layout");
        applied(shared.execute(first, new DocumentCommand.MoveTo(new TextPosition(0, 3), false)));
        applied(shared.execute(first, new DocumentCommand.Input("X")));
        applied(shared.execute(second, new DocumentCommand.MoveTo(new TextPosition(0, 1), false)));
        assertEquals(new TextPosition(0, 4), shared.view(first).cursor());
        assertEquals(new TextPosition(0, 1), shared.view(second).cursor());
        workspace.remove(first);
        applied(shared.execute(second, new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO)));
        assertEquals(List.of("abcdef"), shared.snapshot().lines());
        assertEquals(new TextPosition(0, 1), shared.view(second).cursor());
        applied(shared.execute(second, new DocumentCommand.Edit(DocumentCommand.EditAction.REDO)));
        assertEquals(List.of("abcXdef"), shared.snapshot().lines());
        assertEquals(new TextPosition(0, 1), shared.view(second).cursor());
        assertEquals(shared.snapshot().lines(), shared.prepareRecovery(second).materialize().lines());
    }

    @Test
    void aStartedWritePublishesToItsDocumentAfterItsOriginalViewCloses() throws Exception {
        var storage = new MemoryDocumentStorage();
        var document = loaded(storage, "saved.txt");
        var workspace = new EditorWorkspace<String>();
        var originalId = document.defaultViewId();
        var remainingId = document.attachView();
        workspace.add(document, originalId, "first");
        workspace.add(document, remainingId, "second");
        applied(document.execute(originalId, new DocumentCommand.Input("draft ")));
        var work = document.io().beginSave();
        workspace.remove(originalId);
        assertThrows(IllegalStateException.class, () -> workspace.remove(remainingId));
        assertSame(document, workspace.active().document());
        var completion = worker(work::perform);
        assertInstanceOf(DocumentIoCoordinator.Saved.class, document.io().accept(work, completion));
        assertFalse(document.status().dirty());
        assertEquals("draft text", new String(storage.bytes("saved.txt"), StandardCharsets.UTF_8));
        assertTrue(document.undo());
        assertEquals(List.of("text"), document.snapshot().lines());
        workspace.remove(remainingId);
        assertNull(document.workspace());
        assertNull(workspace.document(document.documentId()));
        assertTrue(document.redo(), "document history survives view retirement");
        ViewId reattached = document.attachView();
        assertNotEquals(originalId, reattached);
        assertNotEquals(remainingId, reattached);
        workspace.add(document, reattached, "new layout");
        assertEquals(List.of("draft text"), document.snapshot().lines());
    }

    @Test
    void inspectionAndApprovalRemainBoundToTheCapturedEngineAfterViewDetachment() throws Exception {
        var storage = new MemoryDocumentStorage();
        var document = loaded(storage, "source");
        var workspace = new EditorWorkspace<String>();
        var first = document.defaultViewId();
        var second = document.attachView();
        workspace.add(document, first, "first");
        workspace.add(document, second, "second");
        applied(document.execute(first, new DocumentCommand.Input("draft ")));
        var inspect = document.io().beginSaveAs(storage.resource("copy"), SaveDisposition.CREATE_NEW, false);
        workspace.remove(first);
        assertThrows(IllegalStateException.class, () -> workspace.remove(second));
        var confirmation = assertInstanceOf(DocumentIoCoordinator.Approval.class,
            document.io().accept(inspect, worker(inspect::perform))).confirmation();
        var write = assertInstanceOf(DocumentIoCoordinator.Ready.class, document.io().approve(confirmation)).work();
        assertInstanceOf(DocumentIoCoordinator.Saved.class, document.io().accept(write, worker(write::perform)));
        assertEquals(storage.resource("copy"), document.status().binding().resource());
        assertEquals("draft text", new String(storage.bytes("copy"), StandardCharsets.UTF_8));
        assertFalse(document.status().dirty());
        assertEquals(second, workspace.active().viewId());
    }

    @Test
    void foreignThreadsCannotReadLiveMembershipOrMutateIt() throws Exception {
        var workspace = new EditorWorkspace<String>();
        var document = session("owner");
        workspace.add(document, document.defaultViewId(), "editor");
        worker(() -> {
            assertFalse(workspace.isOwnerThread());
            assertThrows(IllegalStateException.class, workspace::active);
            assertThrows(IllegalStateException.class, () -> workspace.attachments().get(0));
            assertThrows(IllegalStateException.class, () -> workspace.remove(document.defaultViewId()));
            return null;
        });
        var foreign = worker(() -> EditorDocumentSession.create(new DocumentId("foreign"), List.of("foreign")));
        assertThrows(IllegalStateException.class, () -> workspace.add(foreign, foreign.defaultViewId(), "foreign"));
        assertEquals(List.of("editor"), workspace.attachments());
        assertEquals("text", workspace.active().document().snapshot().line(0));
    }

    private static EditorDocumentSession session(String id) {
        return EditorDocumentSession.create(new DocumentId(id), List.of("text"));
    }
    private static EditorWorkspace.Entry<String> entry(EditorDocumentSession session, String attachment) {
        return new EditorWorkspace.Entry<>(session, session.defaultViewId(), attachment);
    }
    private static void applied(DocumentCommandResult result) { assertInstanceOf(DocumentCommandResult.Applied.class, result); }
    private static EditorDocumentSession loaded(MemoryDocumentStorage storage, String name) throws Exception {
        storage.put(name, "text".getBytes(StandardCharsets.UTF_8));
        var session = session(name);
        session.openPrepared(worker(() -> storage.load(storage.resource(name), DocumentOpenMode.DETECT, false)));
        return session;
    }
    private static <T> T worker(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Thread thread = new Thread(task, "workspace-test-worker");
        thread.start();
        return task.get();
    }
}
