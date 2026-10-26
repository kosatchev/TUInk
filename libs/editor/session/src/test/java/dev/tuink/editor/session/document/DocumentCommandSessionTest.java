package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorDocument;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextModel;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocumentCommandSessionTest {
    @Test
    void typingPasteSearchAndHistoryUseTheSamePublicCommandGate() {
        var session = EditorDocumentSession.create(new DocumentId("public-commands"), List.of("alpha beta", "tail"));
        var view = session.defaultViewId();
        assertTrue(applied(session.execute(view, new DocumentCommand.Input("X"))).textChanged());
        assertTrue(applied(session.execute(view, new DocumentCommand.Input("Y"))).performed());
        assertTrue(applied(session.execute(view, edit(DocumentCommand.EditAction.UNDO))).performed());
        assertEquals("alpha beta", session.snapshot().line(0), "typing retains its coalesced history entry");
        applied(session.execute(view, edit(DocumentCommand.EditAction.REDO)));
        applied(session.execute(view, new DocumentCommand.Paste("(literal)\r\n")));
        assertEquals(List.of("XY(literal)", "alpha beta", "tail"), session.snapshot().lines());
        var replaced = applied(session.execute(view, new DocumentCommand.Search(
            DocumentCommand.SearchAction.REPLACE_ALL, "alpha", "Ω😀", SearchOptions.DEFAULT)));
        assertEquals(1, replaced.affectedCount());
        assertTrue(replaced.textChanged());
        assertEquals("Ω😀 beta", session.snapshot().line(1));
        applied(session.execute(view, edit(DocumentCommand.EditAction.UNDO)));
        assertEquals("alpha beta", session.snapshot().line(1));
        applied(session.execute(view, edit(DocumentCommand.EditAction.UNDO)));
        assertEquals(List.of("XYalpha beta", "tail"), session.snapshot().lines());
    }

    @Test
    void staleDocumentAndViewTargetsAreRejectedBeforeMutation() {
        var session = EditorDocumentSession.create(new DocumentId("stale"), List.of("text"));
        var view = session.defaultViewId();
        var initial = session.commandTarget(view);
        applied(session.execute(view, new DocumentCommand.Navigate(DocumentCommand.Navigation.RIGHT, false)));
        assertRejected(session.execute(initial, new DocumentCommand.Input("rejected")),
            DocumentCommandResult.RejectionReason.STALE_VIEW);
        var beforeText = session.commandTarget(view);
        applied(session.execute(view, new DocumentCommand.Input("X")));
        assertRejected(session.execute(beforeText, edit(DocumentCommand.EditAction.UNDO)),
            DocumentCommandResult.RejectionReason.STALE_DOCUMENT);
        assertEquals("tXext", session.snapshot().line(0));
        var now = session.commandTarget(view);
        assertRejected(session.execute(new DocumentCommandTarget(new DocumentId("other"), view,
            now.revision(), now.stateRevision(), now.viewRevision()), edit(DocumentCommand.EditAction.UNDO)),
            DocumentCommandResult.RejectionReason.WRONG_DOCUMENT);
        assertRejected(session.execute(new DocumentCommandTarget(session.documentId(), new ViewId(Long.MAX_VALUE),
            now.revision(), now.stateRevision(), now.viewRevision()), edit(DocumentCommand.EditAction.UNDO)),
            DocumentCommandResult.RejectionReason.UNKNOWN_VIEW);
        assertEquals("tXext", session.snapshot().line(0));
        applied(session.execute(view, edit(DocumentCommand.EditAction.UNDO)));
        assertEquals("text", session.snapshot().line(0));
    }

    @Test
    void foreignThreadCannotExecuteAndStillSeesOnlyThePublishedSnapshot() throws Exception {
        var session = EditorDocumentSession.create(new DocumentId("owner"), List.of("original"));
        var view = session.defaultViewId();
        var initial = session.snapshot();
        applied(session.execute(view, new DocumentCommand.Input("draft ")));
        var target = session.commandTarget(view);
        var result = new AtomicReference<DocumentCommandResult>();
        var failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                assertEquals(view, session.defaultViewId());
                assertSame(initial, session.snapshot());
                result.set(session.execute(target, edit(DocumentCommand.EditAction.UNDO)));
            } catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        worker.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertRejected(result.get(), DocumentCommandResult.RejectionReason.WRONG_OWNER_THREAD);
        assertEquals("draft original", session.snapshot().line(0));
    }

    @Test
    void formatOnlySaveAndUndoInAnotherViewInvalidateDeferredCommands() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("original.txt", "\ufefforiginal".getBytes(StandardCharsets.UTF_16LE));
        var first = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
        var backend = CoreDocumentBackend.adopt(new DocumentId("format-history"), first);
        var second = first.engine().document().attach(LogicalEditorTextLayout::new, new EditorClipboard());
        var secondId = backend.registerView(second);
        var session = EditorDocumentSession.connect(backend);
        var firstId = session.defaultViewId();
        session.openPrepared(storage.load(storage.resource("original.txt"), DocumentOpenMode.DETECT, false));
        applied(session.execute(firstId, new DocumentCommand.Input("draft ")));
        var beforeSave = session.commandTarget(firstId);
        var preparation = session.prepareSaveAsInput(storage.resource("utf8.txt"), SaveDisposition.CREATE_NEW, true);
        var save = session.approveSaveAs(preparation.inspectTarget());
        session.completeSaved(save, save.write());
        var afterSave = session.commandTarget(firstId);
        assertEquals(beforeSave.revision(), afterSave.revision());
        assertEquals(beforeSave.viewRevision(), afterSave.viewRevision());
        assertNotEquals(beforeSave.stateRevision(), afterSave.stateRevision());
        assertRejected(session.execute(beforeSave, edit(DocumentCommand.EditAction.UNDO)),
            DocumentCommandResult.RejectionReason.STALE_DOCUMENT_STATE);
        assertEquals(StandardCharsets.UTF_8, session.status().metadata().charset());
        assertEquals("draft original", session.snapshot().line(0));

        applied(session.execute(secondId, edit(DocumentCommand.EditAction.UNDO)));
        assertEquals(StandardCharsets.UTF_16LE, session.status().metadata().charset());
        assertEquals(afterSave.revision(), session.commandTarget(firstId).revision());
        assertRejected(session.execute(afterSave, edit(DocumentCommand.EditAction.UNDO)),
            DocumentCommandResult.RejectionReason.STALE_DOCUMENT_STATE);
        assertEquals("draft original", session.snapshot().line(0));
        applied(session.execute(firstId, edit(DocumentCommand.EditAction.UNDO)));
        assertEquals("original", session.snapshot().line(0));
    }

    @Test
    void clearingACollapsedSelectionInvalidatesAnAlreadyCapturedTarget() {
        var session = EditorDocumentSession.create(new DocumentId("selection"), List.of("text"));
        var view = session.defaultViewId();
        var origin = new TextPosition(0, 0);
        applied(session.execute(view, new DocumentCommand.Select(DocumentCommand.SelectionAction.BEGIN, origin, null)));
        var selected = session.commandTarget(view);
        applied(session.execute(view, new DocumentCommand.Select(DocumentCommand.SelectionAction.FINISH, null, null)));
        assertRejected(session.execute(selected, new DocumentCommand.Input("rejected")),
            DocumentCommandResult.RejectionReason.STALE_VIEW);
        assertNull(session.view(view).selectionAnchor());
        assertEquals("text", session.snapshot().line(0));
    }

    @ParameterizedTest
    @EnumSource(value = DocumentCommand.SearchAction.class, names = {"NEXT", "PREVIOUS", "REPLACE"})
    void searchSelectionInvalidatesAPreviousPositionalCommandWithoutChangingText(DocumentCommand.SearchAction action) {
        var session = EditorDocumentSession.create(new DocumentId("search-view"), List.of("alpha alpha"));
        var view = session.defaultViewId();
        var before = session.commandTarget(view);
        var result = applied(session.execute(view, new DocumentCommand.Search(action, "alpha", "alpha", SearchOptions.DEFAULT)));
        assertFalse(result.textChanged());
        assertTrue(result.viewChanged());
        assertRejected(session.execute(before, new DocumentCommand.Input("rejected")),
            DocumentCommandResult.RejectionReason.STALE_VIEW);
        assertEquals("alpha alpha", session.snapshot().line(0));
    }

    @Test
    void registeredViewsShareHistoryAndKeepTheirOwnSelection() {
        var model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("abcdef"));
        TabSession first = new EditorDocument(model).attach(LogicalEditorTextLayout::new, new EditorClipboard());
        var backend = CoreDocumentBackend.adopt(new DocumentId("two-views"), first);
        var second = first.engine().document().attach(LogicalEditorTextLayout::new, new EditorClipboard());
        var secondId = backend.registerView(second);
        var session = EditorDocumentSession.connect(backend);
        var firstId = session.defaultViewId();
        applied(session.execute(firstId, new DocumentCommand.Select(DocumentCommand.SelectionAction.RESTORE,
            new TextPosition(0, 3), new TextPosition(0, 1))));
        applied(session.execute(firstId, new DocumentCommand.Input("X")));
        applied(session.execute(secondId, new DocumentCommand.Select(DocumentCommand.SelectionAction.RESTORE,
            new TextPosition(0, 2), new TextPosition(0, 0))));
        var selection = session.view(secondId);
        applied(session.execute(secondId, edit(DocumentCommand.EditAction.UNDO)));
        assertEquals("abcdef", session.snapshot().line(0));
        assertEquals(selection.cursor(), session.view(secondId).cursor());
        assertEquals(selection.selectionAnchor(), session.view(secondId).selectionAnchor());
        applied(session.execute(firstId, edit(DocumentCommand.EditAction.REDO)));
        assertEquals("aXdef", session.snapshot().line(0));
        applied(session.execute(firstId, edit(DocumentCommand.EditAction.UNDO)));
        assertEquals(new TextPosition(0, 3), session.view(firstId).cursor());
        assertEquals(new TextPosition(0, 1), session.view(firstId).selectionAnchor());
    }

    private static DocumentCommand.Edit edit(DocumentCommand.EditAction action) { return new DocumentCommand.Edit(action); }
    private static DocumentCommandResult.Applied applied(DocumentCommandResult result) {
        return assertInstanceOf(DocumentCommandResult.Applied.class, result);
    }
    private static void assertRejected(DocumentCommandResult result, DocumentCommandResult.RejectionReason reason) {
        assertEquals(reason, assertInstanceOf(DocumentCommandResult.Rejected.class, result).reason());
    }
}
