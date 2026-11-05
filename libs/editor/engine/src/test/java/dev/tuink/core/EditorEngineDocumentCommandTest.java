package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.verification.MemoryDocumentStorage;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineDocumentCommandTest {
    @Test
    void typingAndHistoryReturnBoundedReceiptsWithoutDocumentSnapshots() {
        SnapshotCountingModel model = new SnapshotCountingModel();
        model.replaceLines(List.of("original", "tail"));
        FaultEngine engine = new FaultEngine(model, new EditorState(), new EditorClipboard());
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        model.snapshots = 0;

        var first = execute(engine, new DocumentCommand.Input("X"));
        var second = execute(engine, new DocumentCommand.Input("Y"));
        assertTrue(first.textChanged());
        assertTrue(first.viewChanged());
        assertTrue(first.performed());
        assertEquals(1, first.affectedCount());
        assertEquals(model.revision(), second.revision());
        assertEquals("XYoriginal", model.getLine(0));
        assertTrue(execute(engine, edit(DocumentCommand.EditAction.UNDO)).performed());
        assertEquals("original", model.getLine(0), "typing retains its coalesced undo step");
        assertTrue(execute(engine, edit(DocumentCommand.EditAction.REDO)).performed());
        assertEquals("XYoriginal", model.getLine(0));
        assertEquals(0, model.snapshots, "command receipts must not capture whole-document text");
    }

    @Test
    void failingMandatoryResultsPreserveTextViewsClipboardHistoryAndCoalescing() {
        for (DocumentCommand command : List.of(
            new DocumentCommand.Input("Y"),
            new DocumentCommand.Input("😀"),
            new DocumentCommand.Input("("),
            new DocumentCommand.Paste("pasted"),
            edit(DocumentCommand.EditAction.NEWLINE),
            edit(DocumentCommand.EditAction.BACKSPACE),
            edit(DocumentCommand.EditAction.DELETE_FORWARD),
            edit(DocumentCommand.EditAction.COPY),
            edit(DocumentCommand.EditAction.CUT),
            edit(DocumentCommand.EditAction.PASTE),
            edit(DocumentCommand.EditAction.SELECT_ALL),
            edit(DocumentCommand.EditAction.UNDO),
            edit(DocumentCommand.EditAction.REDO),
            edit(DocumentCommand.EditAction.TOGGLE_LINE_COMMENT),
            new DocumentCommand.Navigate(DocumentCommand.Navigation.LEFT, false),
            new DocumentCommand.MoveTo(new TextPosition(1, 2), true),
            search(DocumentCommand.SearchAction.NEXT, "original", ""),
            search(DocumentCommand.SearchAction.REPLACE, "original", "changed"),
            search(DocumentCommand.SearchAction.REPLACE_ALL, "original", "changed")
        )) {
            Fixture fixture = fixture();
            FaultEngine engine = fixture.engine;
            engine.insertChar('X');
            engine.insertNewline();
            List<String> redoLines = engine.model().snapshotLines();
            assertTrue(engine.undo());
            TabSession other = engine.document().attach(LogicalEditorTextLayout::new, fixture.clipboard);
            other.engine().moveDocumentEnd(false);
            EditorViewMemento otherBefore = other.state().viewMemento();
            engine.restoreSelection(new Position(0, 1), new Position(0, 0));
            fixture.clipboard.setText("keep clipboard");
            long clipboardRevision = fixture.clipboard.revision();
            List<String> before = engine.model().snapshotLines();
            var metadata = engine.model().fileMetadata();
            EditorViewMemento view = engine.state().viewMemento();
            long revision = engine.model().revision();
            long recovery = engine.recoveryRevision();
            long navigation = engine.navigationRevision();
            boolean dirty = engine.state().isDirty();
            EditorDocumentHistory history = engine.document().history;
            var undoStack = history.undoStack;
            var redoStack = history.redoStack;
            EditorEngine.EditKind coalescing = history.lastEditKind;
            long coalescingAt = history.lastEditAtNanos;

            engine.failResult = true;
            assertThrows(DocumentEditLimitException.class, () -> execute(engine, command), command.toString());
            engine.failResult = false;

            assertEquals(before, engine.model().snapshotLines(), command.toString());
            assertEquals(metadata, engine.model().fileMetadata(), command.toString());
            assertEquals(revision, engine.model().revision(), command.toString());
            assertEquals(recovery, engine.recoveryRevision(), command.toString());
            assertEquals(navigation, engine.navigationRevision(), command.toString());
            assertEquals(dirty, engine.state().isDirty(), command.toString());
            assertEquals(view, engine.state().viewMemento(), command.toString());
            assertEquals(otherBefore, other.state().viewMemento(), command.toString());
            assertEquals("keep clipboard", fixture.clipboard.text(), command.toString());
            assertEquals(clipboardRevision, fixture.clipboard.revision(), "Failed command must not publish: " + command);
            assertSame(undoStack, history.undoStack, command.toString());
            assertSame(redoStack, history.redoStack, command.toString());
            assertEquals(coalescing, history.lastEditKind, command.toString());
            assertEquals(coalescingAt, history.lastEditAtNanos, command.toString());
            assertTrue(execute(engine, edit(DocumentCommand.EditAction.REDO)).performed(), command.toString());
            assertEquals(redoLines, engine.model().snapshotLines(), command.toString());
            assertTrue(execute(engine, edit(DocumentCommand.EditAction.UNDO)).performed(), command.toString());
            assertEquals(before, engine.model().snapshotLines(), command.toString());
        }
    }

    @Test
    void failedNavigationResultDoesNotBreakATypingGroup() {
        Fixture fixture = fixture();
        execute(fixture.engine, new DocumentCommand.Input("X"));
        fixture.engine.failResult = true;
        assertThrows(DocumentEditLimitException.class, () -> execute(fixture.engine,
            new DocumentCommand.Navigate(DocumentCommand.Navigation.LEFT, false)));
        fixture.engine.failResult = false;
        execute(fixture.engine, new DocumentCommand.Input("Y"));

        assertTrue(execute(fixture.engine, edit(DocumentCommand.EditAction.UNDO)).performed());
        assertEquals("original", fixture.engine.model().getLine(0));
        assertFalse(execute(fixture.engine, edit(DocumentCommand.EditAction.UNDO)).performed());
    }

    @Test
    void successfulCopyAndCutPublishOnceButPasteAndHistoryDoNotRepublishTheClipboard() {
        Fixture fixture = fixture();
        long initial = fixture.clipboard.revision();
        assertTrue(execute(fixture.engine, edit(DocumentCommand.EditAction.COPY)).performed());
        String copied = fixture.clipboard.text();
        assertEquals(initial + 1, fixture.clipboard.revision());
        assertTrue(execute(fixture.engine, edit(DocumentCommand.EditAction.COPY)).performed());
        assertEquals(copied, fixture.clipboard.text());
        assertEquals(initial + 2, fixture.clipboard.revision(), "Same-text copy remains a publication");
        assertTrue(execute(fixture.engine, edit(DocumentCommand.EditAction.CUT)).performed());
        assertEquals(copied, fixture.clipboard.text());
        long cutRevision = fixture.clipboard.revision();
        assertEquals(initial + 3, cutRevision);
        assertTrue(execute(fixture.engine, edit(DocumentCommand.EditAction.UNDO)).performed());
        assertTrue(execute(fixture.engine, edit(DocumentCommand.EditAction.REDO)).performed());
        assertTrue(execute(fixture.engine, edit(DocumentCommand.EditAction.PASTE)).performed());
        assertTrue(execute(fixture.engine, new DocumentCommand.Paste("external")).performed());
        assertEquals(copied, fixture.clipboard.text());
        assertEquals(cutRevision, fixture.clipboard.revision(), "Pasting and replaying history never export a new copy");
    }

    @Test
    void copyingAndCuttingAnEmptyLastLineAreNotPublications() {
        Fixture fixture = fixture();
        fixture.engine.model().replaceLines(List.of(""));
        fixture.clipboard.setText("keep");
        long revision = fixture.clipboard.revision();
        assertFalse(execute(fixture.engine, edit(DocumentCommand.EditAction.COPY)).performed());
        assertFalse(execute(fixture.engine, edit(DocumentCommand.EditAction.CUT)).performed());
        assertEquals("keep", fixture.clipboard.text());
        assertEquals(revision, fixture.clipboard.revision());
    }

    @Test
    void boundaryNoOpsAreAppliedWithoutReportingAnOperation() {
        Fixture fixture = fixture();
        for (DocumentCommand command : List.of(
            new DocumentCommand.Input(""),
            new DocumentCommand.Navigate(DocumentCommand.Navigation.LEFT, false),
            edit(DocumentCommand.EditAction.BACKSPACE),
            edit(DocumentCommand.EditAction.UNDO),
            edit(DocumentCommand.EditAction.REDO)
        )) {
            var result = execute(fixture.engine, command);
            assertFalse(result.textChanged(), command.toString());
            assertFalse(result.viewChanged(), command.toString());
            assertFalse(result.performed(), command.toString());
            assertEquals(0, result.affectedCount(), command.toString());
        }
    }

    @Test
    void specializedPublicationReceiptsMatchCommittedRevisionAndExactCounts() {
        Fixture fixture = fixture();
        var pasted = execute(fixture.engine, new DocumentCommand.Paste("original "));
        assertEquals(fixture.engine.model().revision(), pasted.revision());
        var replaced = execute(fixture.engine,
            search(DocumentCommand.SearchAction.REPLACE_ALL, "original", "changed"));
        assertEquals(2, replaced.affectedCount());
        assertEquals(fixture.engine.model().revision(), replaced.revision());
        var commented = execute(fixture.engine, edit(DocumentCommand.EditAction.TOGGLE_LINE_COMMENT));
        assertEquals(DocumentCommandResult.CommentAction.COMMENTED, commented.commentAction());
        assertEquals(1, commented.affectedCount());
        assertTrue(commented.textChanged());
        assertEquals(fixture.engine.model().revision(), commented.revision());
    }

    @Test
    void selectingWordNavigationKeepsTheOriginAnchor() {
        Fixture fixture = fixture();
        var result = execute(fixture.engine,
            new DocumentCommand.Navigate(DocumentCommand.Navigation.WORD_RIGHT, true));
        assertTrue(result.viewChanged());
        assertEquals(new Position(0, 0), fixture.engine.state().getSelectionAnchor());
        assertEquals(new Position(0, 8), fixture.engine.state().getCursor());
        execute(fixture.engine, new DocumentCommand.Navigate(DocumentCommand.Navigation.WORD_LEFT, true));
        assertEquals(new Position(0, 0), fixture.engine.state().getSelectionAnchor());
        assertEquals(new Position(0, 0), fixture.engine.state().getCursor());
    }

    @Test
    void finishingACollapsedSelectionAdvancesItsViewGuardExactlyOnce() {
        Fixture fixture = fixture();
        fixture.engine.beginSelectionAt(new Position(0, 2));
        long before = fixture.engine.navigationRevision();
        long textRevision = fixture.engine.model().revision();
        var finish = new DocumentCommand.Select(DocumentCommand.SelectionAction.FINISH, null, null);

        var first = execute(fixture.engine, finish);

        assertTrue(first.viewChanged());
        assertTrue(first.performed());
        assertNull(fixture.engine.state().getSelectionAnchor());
        assertEquals(before + 1, fixture.engine.navigationRevision());
        assertEquals(textRevision, fixture.engine.model().revision());
        var repeated = execute(fixture.engine, finish);
        assertFalse(repeated.viewChanged());
        assertFalse(repeated.performed());
        assertEquals(before + 1, fixture.engine.navigationRevision());

        fixture.engine.beginSelectionAt(new Position(0, 2));
        fixture.engine.extendSelectionTo(new Position(0, 4));
        long extended = fixture.engine.navigationRevision();
        var selected = execute(fixture.engine, finish);
        assertFalse(selected.viewChanged());
        assertEquals(new Position(0, 2), fixture.engine.state().getSelectionAnchor());
        assertEquals(extended, fixture.engine.navigationRevision());
    }

    @Test
    void searchSelectionAndIdenticalReplacementInvalidateDeferredViewTargetsWithoutTextChanges() {
        Fixture fixture = fixture();
        fixture.engine.model().replaceLines(List.of("one one", "tail"));
        long revision = fixture.engine.model().revision();
        CoreDocumentBackend backend = CoreDocumentBackend.adopt(new DocumentId("search"),
            new TabSession(fixture.engine.model(), fixture.engine.state(), fixture.engine.textLayout(), fixture.engine));
        for (DocumentCommand.SearchAction action : List.of(DocumentCommand.SearchAction.NEXT,
            DocumentCommand.SearchAction.NEXT, DocumentCommand.SearchAction.PREVIOUS,
            DocumentCommand.SearchAction.REPLACE)) {
            DocumentCommandTarget before = new DocumentCommandTarget(backend.documentId(), backend.defaultViewId(),
                backend.revision(), fixture.engine.recoveryRevision(), backend.viewRevision(backend.defaultViewId()));

            var result = backend.execute(before, search(action, "one", "one"));

            assertTrue(result.performed(), action.toString());
            assertFalse(result.textChanged(), action.toString());
            assertEquals(revision, fixture.engine.model().revision());
            assertEquals(before.viewRevision() + 1, backend.viewRevision(backend.defaultViewId()), action.toString());
            assertThrows(IllegalStateException.class,
                () -> backend.execute(before, new DocumentCommand.Input("rejected")), action.toString());
        }
        assertEquals(new Position(0, 4), fixture.engine.state().getSelectionAnchor());
        assertEquals(new Position(0, 7), fixture.engine.state().getCursor());
    }

    @Test
    void refusedSearchDoesNotAdvanceTheViewGuardOrBreakATypingGroup() {
        Fixture fixture = fixture();
        fixture.engine.model().replaceLines(List.of("a".repeat(2_000)));
        execute(fixture.engine, new DocumentCommand.Input("X"));
        EditorViewMemento before = fixture.engine.state().viewMemento();
        long revision = fixture.engine.model().revision();
        long navigation = fixture.engine.navigationRevision();
        var command = new DocumentCommand.Search(DocumentCommand.SearchAction.NEXT, "a".repeat(64) + "?a", "",
            new SearchOptions(false, SearchOptions.MatchMode.WILDCARD));

        assertThrows(SearchLimitException.class, () -> SearchWorkBudget.run(0,
            () -> execute(fixture.engine, command)));

        assertEquals(before, fixture.engine.state().viewMemento());
        assertEquals(navigation, fixture.engine.navigationRevision());
        assertEquals(revision, fixture.engine.model().revision());
        execute(fixture.engine, new DocumentCommand.Input("Y"));
        assertTrue(execute(fixture.engine, edit(DocumentCommand.EditAction.UNDO)).performed());
        assertEquals("a".repeat(2_000), fixture.engine.model().getLine(0));
    }

    @Test
    void resolvedNavigationNormalizesItsPreviousCaretBeforeStartingShiftSelection() {
        Fixture fixture = fixture();
        fixture.engine.model().replaceLines(List.of("a\u0301", "next"));
        fixture.engine.restoreSelection(new Position(0, 1), null);

        var result = execute(fixture.engine, new DocumentCommand.MoveTo(new TextPosition(1, 1), true));

        assertTrue(result.viewChanged());
        assertEquals(new Position(0, 0), fixture.engine.state().getSelectionAnchor());
        assertEquals(new Position(1, 1), fixture.engine.state().getCursor());
    }

    @Test
    void foreignHistoryReceiptIncludesTheInitiatingSelectionsPreparedClampAndFailurePublishesNeitherView() {
        Fixture fixture = fixture();
        FaultEngine initiating = fixture.engine;
        initiating.model().replaceLines(List.of("a"));
        TabSession author = initiating.document().attach(LogicalEditorTextLayout::new, fixture.clipboard);
        author.engine().moveEnd();
        execute(author.engine(), new DocumentCommand.Input("bcdef"));
        initiating.restoreSelection(new Position(0, 5), new Position(0, 3));
        initiating.state().setViewport(new ViewportState(0, 0, 2, 3));
        CoreDocumentBackend backend = CoreDocumentBackend.adopt(new DocumentId("foreign-history"),
            new TabSession(initiating.model(), initiating.state(), initiating.textLayout(), initiating));
        DocumentCommandTarget before = new DocumentCommandTarget(backend.documentId(), backend.defaultViewId(),
            backend.revision(), backend.stateRevision(), backend.viewRevision(backend.defaultViewId()));
        EditorViewMemento originBefore = initiating.state().viewMemento();
        EditorViewMemento authorBefore = author.state().viewMemento();
        long authorNavigation = author.engine().navigationRevision();
        var history = initiating.document().history;
        var undo = history.undoStack;
        var redo = history.redoStack;

        initiating.failResult = true;
        assertThrows(DocumentEditLimitException.class,
            () -> backend.execute(before, edit(DocumentCommand.EditAction.UNDO)));
        initiating.failResult = false;

        assertEquals(List.of("abcdef"), initiating.model().snapshotLines());
        assertEquals(before.revision(), backend.revision());
        assertEquals(before.stateRevision(), backend.stateRevision());
        assertEquals(before.viewRevision(), backend.viewRevision(backend.defaultViewId()));
        assertEquals(originBefore, initiating.state().viewMemento());
        assertEquals(authorBefore, author.state().viewMemento());
        assertEquals(authorNavigation, author.engine().navigationRevision());
        assertSame(undo, history.undoStack);
        assertSame(redo, history.redoStack);

        var undone = backend.execute(before, edit(DocumentCommand.EditAction.UNDO));

        assertTrue(undone.performed());
        assertTrue(undone.textChanged());
        assertTrue(undone.viewChanged(), "The receipt must include the initiating selection's pending clamp");
        assertEquals(List.of("a"), initiating.model().snapshotLines());
        assertEquals(new Position(0, 1), initiating.state().getCursor());
        assertEquals(new Position(0, 1), initiating.state().getSelectionAnchor());
        assertEquals(originBefore.viewport(), initiating.state().viewport());
        assertEquals(new Position(0, 1), author.state().getCursor());
        assertEquals(authorBefore.viewport(), author.state().viewport());
        var redone = execute(initiating, edit(DocumentCommand.EditAction.REDO));
        assertTrue(redone.performed());
        assertFalse(redone.viewChanged(), "An unchanged foreign caret must not report a view change");
        assertEquals(List.of("abcdef"), initiating.model().snapshotLines());
        assertEquals(new Position(0, 1), initiating.state().getCursor());
        assertEquals(new Position(0, 1), initiating.state().getSelectionAnchor());
    }

    private static DocumentCommand.Edit edit(DocumentCommand.EditAction action) {
        return new DocumentCommand.Edit(action);
    }

    private static DocumentCommand.Search search(DocumentCommand.SearchAction action, String query, String replacement) {
        return new DocumentCommand.Search(action, query, replacement, SearchOptions.DEFAULT);
    }

    private static DocumentCommandResult.Applied execute(EditorEngine engine, DocumentCommand command) {
        return engine.executeCommand(new DocumentCommandTarget(new DocumentId("commands"),
            new ViewId(engine.state().viewId()), engine.model().revision(), engine.recoveryRevision(),
            engine.navigationRevision()), command);
    }

    private static Fixture fixture() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("original", "tail"));
        EditorState state = new EditorState();
        state.setCurrentResource(new MemoryDocumentStorage().resource("Document.java"));
        EditorClipboard clipboard = new EditorClipboard();
        FaultEngine engine = new FaultEngine(model, state, clipboard);
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        return new Fixture(engine, clipboard);
    }

    private record Fixture(FaultEngine engine, EditorClipboard clipboard) {}

    private static final class FaultEngine extends EditorEngine {
        private boolean failResult;

        private FaultEngine(TextModel model, EditorState state, EditorClipboard clipboard) {
            super(model, state, new LogicalEditorTextLayout(model), clipboard);
        }

        @Override protected DocumentCommandResult.Applied prepareCommandResult(DocumentCommandTarget target,
            long revision, boolean textChanged, boolean viewChanged, boolean performed, int affectedCount,
            DocumentCommandResult.CommentAction commentAction) {
            if (failResult) throw new OutOfMemoryError("mandatory document command result");
            return super.prepareCommandResult(target, revision, textChanged, viewChanged, performed,
                affectedCount, commentAction);
        }
    }

    private static final class SnapshotCountingModel extends TextModel {
        private int snapshots;
        private SnapshotCountingModel() { super(new SimpleLineBuffer()); }
        @Override public List<LineText> snapshotTextLines() {
            snapshots++;
            return super.snapshotTextLines();
        }
        @Override public List<String> snapshotLines() {
            snapshots++;
            return super.snapshotLines();
        }
    }
}
