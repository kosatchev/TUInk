package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class CoreDocumentBackendTest {
    private static final DocumentId ID = new DocumentId("core-backend");

    @Test
    void adoptionKeepsExistingHistoryAndRegistersTheActualOriginView() {
        TabSession original = view("alpha", "beta");
        original.engine().moveCursorTo(new Position(1, 2));
        original.engine().insertInput("X");
        CoreDocumentBackend backend = CoreDocumentBackend.adopt(ID, original);
        var id = backend.defaultViewId();

        assertEquals(new ViewId(original.state().viewId()), id);
        assertEquals(id, backend.registerView(original));
        assertTrue(backend.status().dirty());
        assertEquals(new TextPosition(1, 3), backend.view(id).cursor());
        assertTrue(backend.<Boolean>undo(Boolean::valueOf));
        assertEquals(List.of("alpha", "beta"), backend.snapshot().lines());
        assertFalse(backend.status().dirty());
        assertEquals(new TextPosition(1, 2), backend.view(id).cursor());
    }

    @Test
    void registrationRejectsAnUnrelatedDocumentAndTheOriginalOwnerCannotBeReassigned() throws Exception {
        TabSession original = view("owned");
        CoreDocumentBackend backend = CoreDocumentBackend.adopt(ID, original);
        TabSession unrelated = view("other");

        assertThrows(IllegalArgumentException.class, () -> backend.registerView(unrelated));
        assertThrows(IllegalStateException.class, () -> worker(() -> CoreDocumentBackend.adopt(ID, original)));
        assertThrows(IllegalStateException.class, () -> worker(() -> backend.registerView(original)));
        assertThrows(IllegalStateException.class, () -> worker(backend::snapshot));
        assertThrows(IllegalStateException.class, () -> worker(() -> backend.view(backend.defaultViewId())));
        assertEquals(List.of("owned"), backend.snapshot().lines());
        assertEquals("other", unrelated.model().getLine(0));
    }

    @Test
    void commandDispatchUsesEachRegisteredOriginAndDoesNotMergeAcrossViews() {
        TabSession first = view("alpha", "beta");
        CoreDocumentBackend backend = CoreDocumentBackend.adopt(ID, first);
        TabSession second = first.engine().document().attach(LogicalEditorTextLayout::new, new EditorClipboard());
        ViewId secondId = backend.registerView(second);
        first.engine().moveCursorTo(new Position(0, 2));
        first.state().setTopLine(1);
        first.state().setLeftVisualColumn(3);
        var firstBefore = first.state().viewMemento();
        second.engine().restoreSelection(new Position(1, 2), new Position(1, 0));
        second.state().setTopLine(1);
        var secondBefore = second.state().viewMemento();

        backend.execute(target(backend, backend.defaultViewId()), new DocumentCommand.Input("X"));
        assertEquals(secondBefore, second.state().viewMemento());
        backend.execute(target(backend, secondId), new DocumentCommand.Input("Y"));
        assertEquals(List.of("alXpha", "Yta"), backend.snapshot().lines());
        backend.execute(target(backend, secondId), new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO));
        assertEquals(secondBefore, second.state().viewMemento());
        assertEquals(List.of("alXpha", "beta"), backend.snapshot().lines());
        backend.execute(target(backend, backend.defaultViewId()), new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO));
        assertEquals(firstBefore, first.state().viewMemento());
        assertEquals(secondBefore, second.state().viewMemento());
        assertEquals(List.of("alpha", "beta"), backend.snapshot().lines());
        assertFalse(backend.<Boolean>undo(Boolean::valueOf));
    }

    @Test
    void staleTextAndStaleViewTargetsAreRejectedBeforeMutation() {
        TabSession first = view("alpha");
        CoreDocumentBackend backend = CoreDocumentBackend.adopt(ID, first);
        DocumentCommandTarget initial = target(backend, backend.defaultViewId());
        backend.execute(initial, new DocumentCommand.Navigate(DocumentCommand.Navigation.RIGHT, false));
        assertEquals(initial.revision(), backend.revision());
        assertThrows(IllegalStateException.class, () -> backend.execute(initial, new DocumentCommand.Input("bad")));
        DocumentCommandTarget beforeText = target(backend, backend.defaultViewId());
        backend.execute(beforeText, new DocumentCommand.Input("X"));
        assertThrows(IllegalStateException.class, () -> backend.execute(beforeText, new DocumentCommand.Input("bad")));
        var current = target(backend, backend.defaultViewId());
        assertThrows(IllegalArgumentException.class, () -> backend.execute(
            new DocumentCommandTarget(new DocumentId("wrong"), current.viewId(), current.revision(),
                current.stateRevision(), current.viewRevision()),
            new DocumentCommand.Input("bad")));
        assertThrows(IllegalArgumentException.class, () -> backend.execute(
            new DocumentCommandTarget(ID, new ViewId(Long.MAX_VALUE), current.revision(),
                current.stateRevision(), current.viewRevision()),
            new DocumentCommand.Input("bad")));
        assertEquals(List.of("aXlpha"), backend.snapshot().lines());
        assertTrue(backend.<Boolean>undo(Boolean::valueOf));
        assertEquals(List.of("alpha"), backend.snapshot().lines());
        assertFalse(backend.<Boolean>undo(Boolean::valueOf));
    }

    @Test
    void rejectedMandatoryWorkspaceResultRestoresTextViewHistoryAndRedo() {
        TabSession view = view("alpha");
        CoreDocumentBackend backend = CoreDocumentBackend.adopt(ID, view);
        backend.execute(target(backend, backend.defaultViewId()), new DocumentCommand.Input("X"));
        assertTrue(backend.<Boolean>undo(Boolean::valueOf));
        var snapshot = backend.snapshot();
        var before = view.state().viewMemento();
        var position = new TextPosition(0, 0);
        WorkspaceEdit edit = new WorkspaceEdit(ID, snapshot.revision(),
            List.of(new TextEdit(new TextRange(position, position), "rejected")));

        assertThrows(DocumentEditLimitException.class, () -> backend.applyWorkspaceEdit(edit, changed -> {
            throw new OutOfMemoryError("mandatory command result");
        }));

        assertEquals(snapshot, backend.snapshot());
        assertEquals(before, view.state().viewMemento());
        assertFalse(backend.status().dirty());
        assertFalse(backend.<Boolean>undo(Boolean::valueOf));
        assertTrue(backend.<Boolean>redo(Boolean::valueOf));
        assertEquals(List.of("Xalpha"), backend.snapshot().lines());
    }

    @Test
    void storageWrappersRejectForeignBackendOperationsBeforeSavepointPublication() throws Exception {
        MemoryDocumentStorage storage = new MemoryDocumentStorage();
        storage.put("source.txt", "source".getBytes(StandardCharsets.UTF_8));
        var resource = storage.resource("source.txt");
        CoreDocumentBackend first = CoreDocumentBackend.create(ID, List.of(""));
        first.openPrepared(worker(() -> storage.load(resource, DocumentOpenMode.DETECT, false)));
        first.execute(target(first, first.defaultViewId()), new DocumentCommand.Input("draft "));
        CoreDocumentBackend other = CoreDocumentBackend.create(new DocumentId("other"), List.of("other"));
        var preparation = first.prepareSaveAsInput(storage.resource("copy.txt"), SaveDisposition.CREATE_NEW, false);
        var inspection = worker(preparation::inspectTarget);

        assertThrows(IllegalArgumentException.class, () -> other.approveSaveAs(inspection));
        var save = first.approveSaveAs(inspection);
        var receipt = worker(save::write);
        assertThrows(IllegalArgumentException.class, () -> other.completeSaved(save, receipt));
        assertTrue(first.status().dirty());
        assertFalse(other.status().dirty());
        first.completeSaved(save, receipt);
        assertFalse(first.status().dirty());
        assertThrows(IllegalStateException.class, () -> first.completeSaved(save, receipt));
        assertEquals("draft source", new String(storage.bytes("copy.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void formatSaveAndFormatOnlyHistoryInvalidateTargetsWithoutChangingTextRevision() throws Exception {
        MemoryDocumentStorage storage = new MemoryDocumentStorage();
        Charset legacy = Charset.forName("windows-1251");
        storage.put("legacy.txt", "Привет".getBytes(legacy));
        var resource = storage.resource("legacy.txt");
        CoreDocumentBackend backend = CoreDocumentBackend.create(ID, List.of(""));
        backend.openPrepared(worker(() -> storage.load(resource, DocumentOpenMode.DETECT, false)));
        var beforeSave = target(backend, backend.defaultViewId());
        var preparation = backend.prepareSaveAsInput(resource, SaveDisposition.REPLACE_EXISTING, true);
        var inspection = worker(preparation::inspectTarget);
        var prepared = backend.approveSaveAs(inspection);
        backend.completeSaved(prepared, worker(prepared::write));

        assertEquals(beforeSave.revision(), backend.revision());
        assertEquals(beforeSave.viewRevision(), backend.viewRevision(backend.defaultViewId()));
        assertTrue(beforeSave.stateRevision() < backend.stateRevision());
        assertEquals(StandardCharsets.UTF_8, backend.status().metadata().charset());
        assertThrows(IllegalStateException.class, () -> backend.execute(beforeSave, new DocumentCommand.Input("bad")));
        var beforeUndo = target(backend, backend.defaultViewId());
        var undo = backend.execute(beforeUndo, new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO));
        assertTrue(undo.performed());
        assertFalse(undo.textChanged());
        assertEquals(beforeUndo.revision(), backend.revision());
        assertTrue(beforeUndo.stateRevision() < backend.stateRevision());
        assertEquals(legacy, backend.status().metadata().charset());
        assertThrows(IllegalStateException.class, () -> backend.execute(beforeUndo, new DocumentCommand.Input("bad")));
        assertEquals(List.of("Привет"), backend.snapshot().lines());
        assertFalse(backend.<Boolean>undo(Boolean::valueOf));
    }

    @Test
    void recoveryRestoresNeutralDocumentDataAndKeepsTheDestinationViewport() {
        TabSession source = view("saved");
        source.engine().insertInput("draft ");
        source.state().setLeftVisualColumn(1);
        RecoverySnapshot checkpoint = source.engine().captureRecoverySnapshot();
        TabSession target = view("one", "two", "three");
        target.state().setTopLine(2);
        target.state().setLeftColumn(3);
        target.state().setLeftVisualColumn(4);
        CoreDocumentBackend backend = CoreDocumentBackend.adopt(ID, target);

        backend.restoreRecovery(backend.defaultViewId(), checkpoint);

        assertEquals(List.of("draft saved"), backend.snapshot().lines());
        assertEquals(checkpoint.caret(), backend.view(backend.defaultViewId()).cursor());
        assertEquals(0, target.state().getTopLine());
        assertEquals(3, target.state().getLeftColumn());
        assertEquals(4, target.state().getLeftVisualColumn());
        assertTrue(backend.status().dirty());
        assertFalse(backend.<Boolean>undo(Boolean::valueOf));
    }

    private static TabSession view(String... lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(lines));
        return new EditorDocument(model).attach(LogicalEditorTextLayout::new, new EditorClipboard());
    }

    private static DocumentCommandTarget target(CoreDocumentBackend backend, ViewId id) {
        var view = backend.view(id);
        return new DocumentCommandTarget(view.documentId(), view.viewId(), view.revision(),
            backend.stateRevision(), view.viewRevision());
    }

    private static <T> T worker(Callable<T> task) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(task).get();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        } finally {
            executor.shutdownNow();
        }
    }
}
