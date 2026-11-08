package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.*;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

class ExternalSubjectAcceptanceTest {
    @Test
    void liveAdmissionRejectsStaleBindingsForeignReadsBusyAndStoppedOwnersWithoutIo() throws Exception {
        var storage = new MemoryDocumentStorage();
        var initial = put(storage, "document.txt", "original");
        var live = EditorDocumentSession.create(new DocumentId("live"), List.of(""));
        live.openPrepared(initial);
        var expected = live.binding();
        var disk = put(storage, "document.txt", "external");
        var other = put(storage, "other.txt", "foreign");
        int calls = storage.calls();
        assertFalse(live.acceptExternal(expected, other, true));
        assertEquals(calls, storage.calls());
        var save = live.io().beginSave();
        assertFalse(live.acceptExternal(expected, disk, true));
        live.io().cancel(save);
        assertThrows(IllegalStateException.class, () -> worker(() -> live.acceptExternal(expected, disk, true)));
        assertTrue(live.acceptExternal(expected, disk, true));
        assertFalse(live.acceptExternal(expected, initial, true));
        assertEquals(disk.lines(), live.snapshot().lines());
        live.io().stop();
        assertFalse(live.acceptExternal(live.binding(), initial, true));
    }

    @Test
    void liveSessionPublishesFreshTextAndDecoderWithoutCallingItsStorageProvider() throws Exception {
        var storage = new MemoryDocumentStorage();
        var initial = put(storage, "document.txt", "original");
        var live = EditorDocumentSession.create(new DocumentId("live"), List.of(""));
        live.openPrepared(initial);
        live.execute(live.defaultViewId(), new DocumentCommand.Input("local "));
        var before = live.snapshot();
        storage.put("document.txt", "\uFEFFexternal".getBytes(StandardCharsets.UTF_16LE));
        var disk = storage.load(storage.resource("document.txt"), DocumentOpenMode.DETECT, false);
        int calls = storage.calls();
        assertTrue(live.acceptExternal(live.binding(), disk, true));
        assertEquals(calls, storage.calls());
        assertEquals(disk.lines(), live.snapshot().lines());
        assertFalse(live.unsavedExternalChanges());
        assertTrue(live.undo());
        assertEquals(before.lines(), live.snapshot().lines());
        assertTrue(live.unsavedExternalChanges());
        assertEquals(DocumentOpenMode.UTF_16LE, live.reopenMode());
        assertEquals(disk.baseline(), live.binding().version());
        assertTrue(live.redo());
        assertFalse(live.status().dirty());
    }

    @Test
    void frozenUpdateAndReplayKeepAcceptedDiskStateAndUseUniqueMonotonicTextRevisions() throws Exception {
        var storage = new MemoryDocumentStorage();
        var initial = put(storage, "document.md", "original\r\n");
        var frozen = frozen(initial);
        var before = frozen.source();
        storage.put("document.md", "\uFEFFexternal\nnext".getBytes(StandardCharsets.UTF_16BE));
        var disk = storage.load(storage.resource("document.md"), DocumentOpenMode.DETECT, false);
        int calls = storage.calls();
        assertTrue(frozen.acceptExternal(frozen.binding(), disk, true));
        long updated = frozen.source().text().revision();
        assertEquals(calls, storage.calls());
        assertEquals(disk.lines(), frozen.source().text().lines());
        assertFalse(frozen.unsavedExternalChanges());
        assertTrue(frozen.canUndoExternal());
        assertTrue(frozen.undoExternal());
        long undone = frozen.source().text().revision();
        assertTrue(undone > updated);
        assertEquals(before.text().lines(), frozen.source().text().lines());
        assertEquals(before.metadata(), frozen.metadata());
        assertEquals(disk.baseline(), frozen.binding().version());
        assertEquals(DocumentOpenMode.UTF_16BE, frozen.reopenMode());
        assertTrue(frozen.unsavedExternalChanges());
        assertTrue(frozen.redoExternal());
        assertTrue(frozen.source().text().revision() > undone);
        assertEquals(disk.lines(), frozen.source().text().lines());
        assertFalse(frozen.unsavedExternalChanges());
    }

    @Test
    void frozenKeepPreservesTextAndDoesNotOverwriteAnUnacknowledgedLaterChange() throws Exception {
        var storage = new MemoryDocumentStorage();
        var frozen = frozen(put(storage, "document.md", "mine"));
        var before = frozen.source();
        var disk = put(storage, "document.md", "external");
        int calls = storage.calls();
        assertTrue(frozen.acceptExternal(frozen.binding(), disk, false));
        assertEquals(calls, storage.calls());
        assertSame(before.text(), frozen.source().text());
        assertEquals(before.metadata(), frozen.metadata());
        assertTrue(frozen.unsavedExternalChanges());
        assertFalse(frozen.canUndoExternal());
        var work = frozen.io().beginSave();
        assertInstanceOf(DocumentIoCoordinator.Saved.class, frozen.io().accept(work, worker(work::perform)));
        assertArrayEquals("mine".getBytes(StandardCharsets.UTF_8), storage.bytes("document.md"));
        assertFalse(frozen.unsavedExternalChanges());

        disk = put(storage, "document.md", "accepted");
        frozen.acceptExternal(frozen.binding(), disk, false);
        put(storage, "document.md", "changed again");
        work = frozen.io().beginSave();
        assertInstanceOf(DocumentIoCoordinator.Failed.class, frozen.io().accept(work, worker(work::perform)));
        assertTrue(frozen.unsavedExternalChanges());
        assertArrayEquals("changed again".getBytes(StandardCharsets.UTF_8), storage.bytes("document.md"));
    }

    @Test
    void savingAnUndoneFrozenUpdateAdvancesTheDiskSavepointWithoutErasingRedo() throws Exception {
        var storage = new MemoryDocumentStorage();
        var frozen = frozen(put(storage, "document.md", "original"));
        var disk = put(storage, "document.md", "external");
        frozen.acceptExternal(frozen.binding(), disk, true);
        frozen.undoExternal();
        var work = frozen.io().beginSave();
        assertInstanceOf(DocumentIoCoordinator.Saved.class, frozen.io().accept(work, worker(work::perform)));
        var saved = frozen.binding();
        assertFalse(frozen.unsavedExternalChanges());
        assertTrue(frozen.canRedoExternal());
        assertTrue(frozen.redoExternal());
        assertEquals(saved, frozen.binding());
        assertTrue(frozen.unsavedExternalChanges());
        assertTrue(frozen.undoExternal());
        assertFalse(frozen.unsavedExternalChanges());
    }

    @Test
    void frozenNoOpUpdateAddsNoEntryAndBusyStoppedOrForeignReadsCannotChangeItsSnapshot() throws Exception {
        var storage = new MemoryDocumentStorage();
        var initial = put(storage, "document.md", "original");
        var frozen = frozen(initial);
        var before = frozen.source();
        assertTrue(frozen.acceptExternal(frozen.binding(), initial, true));
        assertSame(before.text(), frozen.source().text());
        assertFalse(frozen.canUndoExternal());
        var expected = frozen.binding();
        var disk = put(storage, "document.md", "external");
        var foreign = put(storage, "other.md", "foreign");
        assertFalse(frozen.acceptExternal(expected, foreign, true));
        var work = frozen.io().beginSave();
        assertFalse(frozen.acceptExternal(expected, disk, true));
        assertFalse(frozen.undoExternal());
        frozen.io().cancel(work);
        assertThrows(IllegalStateException.class, () -> worker(() -> frozen.acceptExternal(expected, disk, true)));
        frozen.close();
        assertFalse(frozen.acceptExternal(expected, disk, true));
    }

    private static FrozenDocumentSession frozen(DocumentLoad initial) {
        return FrozenDocumentSession.create(new FrozenDocumentSource(
            new DocumentSnapshot(new DocumentId("frozen"), 3, initial.lines()), initial.metadata(),
            new ResourceBinding(initial.resource(), initial.disposition(), initial.baseline()),
            DocumentOpenMode.forMetadata(initial.metadata())));
    }
    private static DocumentLoad put(MemoryDocumentStorage storage, String name, String text) throws Exception {
        storage.put(name, text.getBytes(StandardCharsets.UTF_8));
        return storage.load(storage.resource(name), DocumentOpenMode.DETECT, false);
    }
    private static <T> T worker(Callable<T> operation) throws Exception {
        var task = new FutureTask<>(operation);
        var thread = new Thread(task);
        thread.start(); thread.join(5000);
        assertFalse(thread.isAlive());
        try { return task.get(); }
        catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof Exception cause) throw cause;
            throw (Error) failed.getCause();
        }
    }
}
