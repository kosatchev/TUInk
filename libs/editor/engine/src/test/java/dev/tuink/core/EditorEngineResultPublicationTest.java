package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.storage.DocumentFileMetadata;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class EditorEngineResultPublicationTest {
    @Test
    void failedMandatoryResultPreparationRollsBackTextRevisionAndHistory() {
        Fixture fixture = fixture();
        long revision = fixture.model.revision();
        Position cursor = fixture.state.getCursor();
        List<TextReplacement> edit = List.of(new TextReplacement(new Position(0, 0), new Position(0, 1), "Z"));

        assertThrows(DocumentEditLimitException.class, () -> fixture.engine.applyTextReplacements(edit, changed -> {
            assertTrue(changed);
            assertEquals("Zriginal", fixture.model.getLine(0), "result reads prepared text");
            throw new OutOfMemoryError("mandatory snapshot allocation");
        }));

        assertEquals("original", fixture.model.getLine(0));
        assertEquals(revision, fixture.model.revision());
        assertEquals(cursor, fixture.state.getCursor());
        assertFalse(fixture.state.isDirty());
        assertFalse(fixture.engine.undo());
        String result = fixture.engine.applyTextReplacements(edit, changed -> fixture.model.getLine(0));
        assertEquals("Zriginal", result);
        assertTrue(fixture.engine.undo());
        assertEquals("original", fixture.model.getLine(0));
    }

    @Test
    void failedUndoAndRedoResultPreparationPreservesBothHistoryStacks() {
        Fixture fixture = fixture();
        fixture.engine.insertChar('X');
        long editedRevision = fixture.model.revision();
        assertThrows(DocumentEditLimitException.class, () -> fixture.engine.undo(changed -> {
            assertTrue(changed);
            assertEquals("original", fixture.model.getLine(0));
            throw new OutOfMemoryError("undo publication");
        }));
        assertEquals("Xoriginal", fixture.model.getLine(0));
        assertEquals(editedRevision, fixture.model.revision());
        assertTrue(fixture.engine.undo());
        long undoneRevision = fixture.model.revision();
        assertThrows(DocumentEditLimitException.class, () -> fixture.engine.redo(changed -> {
            assertTrue(changed);
            assertEquals("Xoriginal", fixture.model.getLine(0));
            throw new OutOfMemoryError("redo publication");
        }));
        assertEquals("original", fixture.model.getLine(0));
        assertEquals(undoneRevision, fixture.model.revision());
        assertTrue(fixture.engine.redo());
        assertEquals("Xoriginal", fixture.model.getLine(0));
    }

    @Test
    void emptyUndoAndRedoResultAllocationFailurePreservesNavigationViewsAndHistory() {
        for (boolean undoing : List.of(true, false)) {
            Fixture fixture = fixtureWithEmptyReplayBranch(undoing);
            Observed before = observe(fixture);
            EditorDocumentHistory history = fixture.engine.document().history;
            var undo = history.undoStack;
            var redo = history.redoStack;

            assertInstanceOf(DocumentEditLimitException.class,
                assertThrows(Throwable.class, () -> replay(fixture, undoing, changed -> {
                    assertFalse(changed);
                    throw new OutOfMemoryError("empty history result allocation");
                })));

            assertEquals(before, observe(fixture));
            assertSame(undo, history.undoStack);
            assertSame(redo, history.redoStack);
        }
    }

    @Test
    void emptyUndoAndRedoCallbackFailurePreservesNavigationViewsAndHistory() {
        for (boolean undoing : List.of(true, false)) {
            Fixture fixture = fixtureWithEmptyReplayBranch(undoing);
            Observed before = observe(fixture);
            EditorDocumentHistory history = fixture.engine.document().history;
            var undo = history.undoStack;
            var redo = history.redoStack;
            RuntimeException failure = new IllegalStateException("empty history result preparation");

            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> replay(fixture, undoing, changed -> {
                    assertFalse(changed);
                    throw failure;
                })));

            assertEquals(before, observe(fixture));
            assertSame(undo, history.undoStack);
            assertSame(redo, history.redoStack);
        }
    }

    @Test
    void emptyUndoAndRedoPrepareTheirResultBeforeInvalidatingNavigation() {
        for (boolean undoing : List.of(true, false)) {
            Fixture fixture = fixtureWithEmptyReplayBranch(undoing);
            Observed before = observe(fixture);
            Object expected = new Object();

            Object result = replay(fixture, undoing, changed -> {
                assertFalse(changed);
                assertEquals(before, observe(fixture), "Result preparation must see the unchanged state");
                return expected;
            });

            assertSame(expected, result);
            assertEquals(before.navigation() + 1, fixture.engine.navigationRevision());
            assertEquals(before.view(), fixture.state.viewMemento());
        }
    }

    private static <T> T replay(Fixture fixture, boolean undoing, Function<Boolean, T> prepareResult) {
        return undoing ? fixture.engine.undo(prepareResult) : fixture.engine.redo(prepareResult);
    }

    private static Fixture fixtureWithEmptyReplayBranch(boolean undoing) {
        Fixture fixture = fixture();
        fixture.engine.insertChar('X');
        if (undoing) assertTrue(fixture.engine.undo());
        fixture.engine.restoreSelection(new Position(0, 3), new Position(0, 1));
        fixture.state.setViewport(new ViewportState(0, 0, 1, 2));
        return fixture;
    }

    private static Observed observe(Fixture fixture) {
        EditorDocumentHistory history = fixture.engine.document().history;
        return new Observed(fixture.model.snapshotLines(), fixture.model.fileMetadata(),
            fixture.state.viewMemento(), fixture.model.revision(), fixture.model.contentEpoch(),
            fixture.engine.navigationRevision(), fixture.engine.recoveryRevision(), fixture.state.isDirty(),
            List.copyOf(history.undoStack), List.copyOf(history.redoStack), history.currentDocumentStateId,
            history.nextDocumentStateId, history.savedDocumentStateId, history.lastEditKind,
            history.lastEditAtNanos, history.lastEditCursorAfter, history.lastEditViewId);
    }

    private record Observed(List<String> lines, DocumentFileMetadata metadata, EditorViewMemento view,
        long revision, long epoch, long navigation, long recovery, boolean dirty,
        List<EditorEngine.HistoryEntry> undo, List<EditorEngine.HistoryEntry> redo,
        long stateId, long nextStateId, long savedStateId, EditorEngine.EditKind coalescingKind,
        long coalescingAt, Position coalescingCursor, long coalescingView) {}

    private static Fixture fixture() {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, "original");
        TextModel model = new TextModel(buffer);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model), new EditorClipboard());
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {}
}
