package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ExternalDocumentAcceptanceTest {
    @Test
    void updateIsOneTextAndFormatUndoWithoutRewindingTheAcceptedDiskOrOldHistory() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("document.txt", "original\r\nsecond".getBytes(StandardCharsets.UTF_8));
        var view = opened(storage, "document.txt");
        var engine = view.engine();
        var openedLines = engine.document().baselines.openLines;
        engine.insertInput("local ");
        var before = view.model().snapshotLines();
        var beforeFormat = view.model().fileMetadata();
        var expected = engine.resourceBinding();
        storage.put("document.txt", "\uFEFFexternal\nnext\r\n".getBytes(StandardCharsets.UTF_16LE));
        var disk = load(storage, "document.txt");
        int calls = storage.calls();

        assertTrue(engine.acceptExternal(expected, disk, true));

        assertEquals(calls, storage.calls());
        assertEquals(disk.lines(), view.model().snapshotLines());
        assertEquals(disk.metadata(), view.model().fileMetadata());
        assertFalse(view.state().isDirty());
        assertSame(openedLines, engine.document().baselines.openLines);
        assertEquals(disk.lines(), engine.document().baselines.savedLines.stream().map(Object::toString).toList());
        assertTrue(engine.undo());
        assertEquals(before, view.model().snapshotLines());
        assertEquals(beforeFormat, view.model().fileMetadata());
        assertEquals(disk.baseline(), view.model().fileBaseline());
        assertEquals(DocumentOpenMode.UTF_16LE, view.model().reopenMode());
        assertTrue(view.state().isDirty());
        assertTrue(engine.undo());
        assertEquals(List.of("original", "second"), view.model().snapshotLines());
        assertTrue(view.state().isDirty(), "A formerly saved state is dirty relative to the newly accepted disk");
        assertTrue(engine.redo());
        assertEquals(before, view.model().snapshotLines());
        assertTrue(engine.redo());
        assertEquals(disk.lines(), view.model().snapshotLines());
        assertFalse(view.state().isDirty());
    }

    @Test
    void keepPreservesTheBufferAndAuthorizesOnlyTheExactAcknowledgedDiskVersion() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("document.txt", "mine".getBytes(StandardCharsets.UTF_8));
        var view = opened(storage, "document.txt");
        var format = view.model().fileMetadata();
        storage.put("document.txt", "\uFEFFexternal".getBytes(StandardCharsets.UTF_16BE));
        var disk = load(storage, "document.txt");
        int calls = storage.calls();

        assertTrue(view.engine().acceptExternal(view.engine().resourceBinding(), disk, false));

        assertEquals(calls, storage.calls());
        assertEquals(List.of("mine"), view.model().snapshotLines());
        assertEquals(format, view.model().fileMetadata());
        assertEquals(DocumentOpenMode.UTF_16BE, view.model().reopenMode());
        assertTrue(view.state().isDirty());
        assertFalse(view.engine().undo(), "Keep does not add a text history entry");
        view.engine().save();
        assertArrayEquals("mine".getBytes(StandardCharsets.UTF_8), storage.bytes("document.txt"));
        assertFalse(view.state().isDirty());

        storage.put("document.txt", "accepted external".getBytes(StandardCharsets.UTF_8));
        disk = load(storage, "document.txt");
        assertTrue(view.engine().acceptExternal(view.engine().resourceBinding(), disk, false));
        storage.put("document.txt", "changed again".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, view.engine()::save);
        assertArrayEquals("changed again".getBytes(StandardCharsets.UTF_8), storage.bytes("document.txt"));
        assertEquals(List.of("mine"), view.model().snapshotLines());
    }

    @Test
    void keepDoesNotClearRedoButMakesEveryOldSavepointDirty() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("document.txt", "original".getBytes(StandardCharsets.UTF_8));
        var view = opened(storage, "document.txt");
        view.engine().insertInput("edit ");
        assertTrue(view.engine().undo());
        storage.put("document.txt", "outside".getBytes(StandardCharsets.UTF_8));
        assertTrue(view.engine().acceptExternal(view.engine().resourceBinding(), load(storage, "document.txt"), false));
        assertTrue(view.engine().redo());
        assertEquals(List.of("edit original"), view.model().snapshotLines());
        assertTrue(view.state().isDirty());
        assertTrue(view.engine().undo());
        assertTrue(view.state().isDirty());
    }

    @Test
    void matchingDirtyTextIsAcceptedCleanWithoutCreatingADuplicateUndoEntry() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("document.txt", "original".getBytes(StandardCharsets.UTF_8));
        var view = opened(storage, "document.txt");
        view.engine().insertInput("local ");
        long revision = view.model().revision();
        storage.put("document.txt", "local original".getBytes(StandardCharsets.UTF_8));
        var disk = load(storage, "document.txt");
        assertTrue(view.engine().acceptExternal(view.engine().resourceBinding(), disk, true));
        assertEquals(revision, view.model().revision());
        assertFalse(view.state().isDirty());
        assertTrue(view.engine().undo());
        assertEquals(List.of("original"), view.model().snapshotLines());
        assertTrue(view.state().isDirty());
        assertFalse(view.engine().undo());
        assertTrue(view.engine().redo());
        assertFalse(view.state().isDirty());
    }

    @Test
    void updateClearsRedoAndRecoveryRetainsTheAcceptedVersionAfterUndo() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("document.txt", "original".getBytes(StandardCharsets.UTF_8));
        var view = opened(storage, "document.txt");
        view.engine().insertInput("discarded ");
        view.engine().undo();
        storage.put("document.txt", "external".getBytes(StandardCharsets.UTF_8));
        var disk = load(storage, "document.txt");
        assertTrue(view.engine().acceptExternal(view.engine().resourceBinding(), disk, true));
        assertFalse(view.engine().redo());
        assertTrue(view.engine().undo());
        var recovery = view.engine().captureRecoverySnapshot();
        assertTrue(recovery.dirty());
        assertEquals(disk.baseline(), recovery.fileBaseline());
        var restored = new EditorDocument(new TextModel(new SimpleLineBuffer()))
            .attach(LogicalEditorTextLayout::new, new EditorClipboard());
        restored.engine().restoreRecoverySnapshot(recovery);
        assertEquals(List.of("original"), restored.model().snapshotLines());
        assertTrue(restored.state().isDirty());
        restored.engine().save();
        assertArrayEquals("original".getBytes(StandardCharsets.UTF_8), storage.bytes("document.txt"));
    }

    @Test
    void failedUpdateAllocationPublishesNeitherTextHistoryNorTheNewDiskVersion() throws Exception {
        var fail = new AtomicBoolean();
        var storage = new MemoryDocumentStorage();
        storage.put("document.txt", "original".getBytes(StandardCharsets.UTF_8));
        var view = new EditorDocument(new TextModel(new RefusingBuffer(fail)))
            .attach(LogicalEditorTextLayout::new, new EditorClipboard());
        view.engine().openPrepared(load(storage, "document.txt"));
        view.engine().insertInput("local ");
        var before = view.model().snapshotLines();
        var binding = view.engine().resourceBinding();
        long revision = view.model().revision();
        long state = view.engine().recoveryRevision();
        storage.put("document.txt", "external\nnext".getBytes(StandardCharsets.UTF_8));
        var disk = load(storage, "document.txt");
        fail.set(true);

        assertThrows(DocumentEditLimitException.class, () -> view.engine().acceptExternal(binding, disk, true));

        assertEquals(before, view.model().snapshotLines());
        assertEquals(binding, view.engine().resourceBinding());
        assertEquals(revision, view.model().revision());
        assertEquals(state, view.engine().recoveryRevision());
        assertTrue(view.engine().undo(), "The original history remains usable after an aborted update");
        assertEquals(List.of("original"), view.model().snapshotLines());
        assertFalse(view.engine().undo());
    }

    private static final class RefusingBuffer extends SimpleLineBuffer {
        private final AtomicBoolean fail;
        RefusingBuffer(AtomicBoolean fail) { this.fail = fail; }
        @Override public TextBuffer createEmptySibling() {
            if (fail.getAndSet(false)) throw new OutOfMemoryError("injected external-update allocation failure");
            return new RefusingBuffer(fail);
        }
    }

    private static TabSession opened(MemoryDocumentStorage storage, String name) throws Exception {
        var view = new EditorDocument(new TextModel(new SimpleLineBuffer()))
            .attach(LogicalEditorTextLayout::new, new EditorClipboard());
        view.engine().openPrepared(load(storage, name));
        return view;
    }

    private static DocumentLoad load(MemoryDocumentStorage storage, String name) throws Exception {
        return storage.load(storage.resource(name), DocumentOpenMode.DETECT, false);
    }
}
