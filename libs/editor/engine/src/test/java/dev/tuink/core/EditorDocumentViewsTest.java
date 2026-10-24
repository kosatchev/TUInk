package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.diff.jgit.JgitLineDiff;
import dev.tuink.editor.diff.LineDiff;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorDocumentViewsTest {
    @Test
    void invalidInitialComponentsDoNotClaimAnOtherwiseUsableModel() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorTextLayout layout = new LogicalEditorTextLayout(model);

        assertThrows(NullPointerException.class, () -> new EditorEngine(model, state, layout, null));
        assertThrows(NullPointerException.class,
            () -> new EditorEngine(model, state, layout, new EditorClipboard(), (LineDiff) null));

        EditorEngine engine = new EditorEngine(model, state, layout, new EditorClipboard());
        engine.insertInput("still usable");
        assertEquals("still usable", model.getLine(0));
    }

    @Test
    void aViewStateCannotBeReboundToAnotherDocumentAndFailedBindingDoesNotClaimItsModel() {
        TextModel firstModel = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine first = new EditorEngine(firstModel, state, new LogicalEditorTextLayout(firstModel));
        first.insertInput("first");
        TextModel secondModel = new TextModel(new SimpleLineBuffer());
        EditorTextLayout secondLayout = new LogicalEditorTextLayout(secondModel);

        assertThrows(IllegalArgumentException.class, () -> new EditorEngine(secondModel, state, secondLayout));

        EditorEngine second = new EditorEngine(secondModel, new EditorState(), secondLayout);
        assertFalse(second.state().isDirty());
        assertTrue(first.state().isDirty());
        second.insertInput("second");
        assertTrue(second.undo());
        assertEquals("first", firstModel.getLine(0));
    }

    @Test
    void attachedViewsShareOneDocumentButKeepIndependentPositionsAndLayout() {
        EditorDocument document = document("first", "second", "third");
        TabSession first = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        TabSession second = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        first.engine().moveCursorTo(new Position(1, 3));
        first.state().setTopLine(1);
        first.state().setLeftVisualColumn(2);
        second.engine().moveCursorTo(new Position(2, 1));
        second.state().setTopLine(2);

        assertSame(first.model(), second.model());
        assertSame(first.engine().document(), second.engine().document());
        assertSame(first.state().documentState(), second.state().documentState());
        assertNotSame(first.state(), second.state());
        assertNotSame(first.textLayout(), second.textLayout());
        assertEquals(new Position(1, 3), first.state().getCursor());
        assertEquals(new Position(2, 1), second.state().getCursor());
        assertEquals(1, first.state().getTopLine());
        assertEquals(2, second.state().getTopLine());
        assertEquals(2, first.state().getLeftVisualColumn());
        assertEquals(0, second.state().getLeftVisualColumn());
    }

    @Test
    void editsAcrossViewsUseOneUndoRedoChainAndNeverCoalesceAcrossViews() {
        EditorDocument document = document("ab");
        TabSession first = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        TabSession second = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        first.engine().insertChar('X');
        second.engine().insertChar('Y');
        assertEquals("YXab", document.model().getLine(0));
        assertTrue(first.state().isDirty());
        assertTrue(second.state().isDirty());

        assertTrue(second.engine().undo());
        assertEquals("Xab", document.model().getLine(0));
        assertTrue(first.engine().undo());
        assertEquals("ab", document.model().getLine(0));
        assertFalse(first.engine().undo());
        assertFalse(second.state().isDirty());
        assertTrue(first.engine().redo());
        assertEquals("Xab", document.model().getLine(0));
        assertTrue(second.engine().redo());
        assertEquals("YXab", document.model().getLine(0));
        assertFalse(second.engine().redo());
    }

    @Test
    void originViewUndoRestoresItsMementoWhileOtherViewsKeepTheirOwnViewport() {
        EditorDocument document = document("alpha", "beta", "gamma");
        TabSession first = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        TabSession other = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        first.engine().moveCursorTo(new Position(1, 2));
        first.state().setTopLine(1);
        first.state().setLeftVisualColumn(3);
        other.engine().moveCursorTo(new Position(2, 1));
        other.state().setTopLine(2);
        other.state().setLeftVisualColumn(1);
        EditorViewMemento before = first.state().viewMemento();
        EditorViewMemento otherBefore = other.state().viewMemento();
        first.engine().insertInput("inserted");
        first.engine().moveHome();
        first.state().setTopLine(0);

        assertTrue(first.engine().undo());

        assertEquals(before, first.state().viewMemento());
        assertEquals(otherBefore, other.state().viewMemento());
    }

    @Test
    void undoFromAnotherViewRestoresTextWithoutApplyingTheOriginatingViewMemento() {
        EditorDocument document = document("alpha", "beta", "gamma");
        TabSession first = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        TabSession other = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        first.engine().moveCursorTo(new Position(0, 2));
        first.engine().insertInput("X");
        other.engine().restoreSelection(new Position(2, 2), new Position(1, 0));
        other.state().setTopLine(2);
        EditorViewMemento otherBefore = other.state().viewMemento();

        assertTrue(other.engine().undo());

        assertEquals(List.of("alpha", "beta", "gamma"), document.model().snapshotLines());
        assertEquals(otherBefore, other.state().viewMemento());
        assertTrue(other.engine().redo());
        assertEquals(List.of("alXpha", "beta", "gamma"), document.model().snapshotLines());
        assertEquals(otherBefore, other.state().viewMemento());
    }

    @Test
    void undoAndRedoFromAnotherViewPreserveExactSearchSelectionsForOperationEntries() {
        assertForeignHistoryPreservesSearchSelection(false);
    }

    @Test
    void undoAndRedoFromAnotherViewPreserveExactSearchSelectionsForSnapshotEntries() {
        assertForeignHistoryPreservesSearchSelection(true);
    }

    @Test
    void foreignUndoStillClampsPositionsThatWouldSplitASurrogatePair() {
        EditorDocument document = document("😀");
        TabSession first = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        TabSession other = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        first.engine().selectAll();
        first.engine().pasteText("a");
        assertTrue(other.engine().findNext("a"));

        assertTrue(other.engine().undo());

        assertEquals("😀", document.model().getLine(0));
        assertEquals(new Position(0, 0), other.state().getCursor());
        assertEquals(new Position(0, 0), other.state().getSelectionAnchor());
        assertFalse(other.state().hasSelection());
    }

    private static void assertForeignHistoryPreservesSearchSelection(boolean snapshotEntry) {
        EditorDocument document = document("e\u0301", "alpha");
        TabSession first = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        TabSession other = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        first.engine().moveCursorTo(new Position(1, 0));
        if (snapshotEntry) {
            first.engine().applyTextReplacements(List.of(new TextReplacement(
                new Position(1, 0), new Position(1, 0), "X")));
        } else {
            first.engine().insertInput("X");
        }
        assertTrue(other.engine().findNext("e"));
        assertEquals(new Position(0, 1), other.state().getCursor());
        assertEquals(new Position(0, 0), other.state().getSelectionAnchor());
        other.state().setTopLine(1);
        other.state().setLeftVisualColumn(2);
        EditorViewMemento before = other.state().viewMemento();

        assertTrue(other.engine().undo());

        assertEquals(List.of("e\u0301", "alpha"), document.model().snapshotLines());
        assertEquals(before, other.state().viewMemento());
        assertTrue(other.engine().redo());
        assertEquals(List.of("e\u0301", "Xalpha"), document.model().snapshotLines());
        assertEquals(before, other.state().viewMemento());
    }

    @Test
    void snapshotAndOperationEntriesShareHistoryWithoutCapturingAnEngine() {
        EditorDocument document = document("alpha", "beta");
        TabSession first = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        TabSession other = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        first.engine().insertInput("X");
        other.engine().applyTextReplacements(List.of(new TextReplacement(new Position(1, 0), new Position(1, 4), "B")));

        assertTrue(first.engine().undo());
        assertEquals(List.of("Xalpha", "beta"), document.model().snapshotLines());
        assertTrue(other.engine().undo());
        assertEquals(List.of("alpha", "beta"), document.model().snapshotLines());
        assertFalse(first.engine().undo());
        for (EditorEngine.HistoryEntry entry : document.history.redoStack) {
            for (var field : entry.getClass().getDeclaredFields()) {
                assertFalse(field.isSynthetic(), "history entries must not capture an originating engine");
            }
        }
    }

    @Test
    void attachingAnotherViewPreservesDirtyHistorySavepointAndOpenBaselines(@TempDir Path directory) throws Exception {
        EditorDocument document = document("alpha");
        TabSession first = document.attach(LogicalEditorTextLayout::new, new EditorClipboard(), new JgitLineDiff());
        Path file = directory.resolve("shared.txt");
        first.engine().saveAs(file);
        first.engine().insertInput("X");
        long generation = document.baselines.generation;

        TabSession second = document.attach(LogicalEditorTextLayout::new, new EditorClipboard(), new JgitLineDiff());

        assertEquals(generation, document.baselines.generation);
        assertTrue(second.state().isDirty());
        assertEquals(file, second.state().getCurrentFile());
        first.engine().setGutterDiffMode(GutterDiffMode.DISABLED);
        assertEquals(GutterDiffFlag.NONE, first.engine().gutterDiffMarkerForLine(0).flag());
        assertEquals(GutterDiffFlag.MODIFIED, second.engine().gutterDiffMarkerForLine(0).flag(),
            "a new view compares with the shared saved baseline using its own diff mode/cache");
        assertTrue(second.engine().undo());
        assertEquals("alpha", document.model().getLine(0));
        assertFalse(first.state().isDirty());
        assertFalse(second.state().isDirty());
        assertTrue(first.engine().redo());
        second.engine().save();
        assertEquals("Xalpha", Files.readString(file));
        assertFalse(first.state().isDirty());
        assertFalse(second.state().isDirty());
    }

    @Test
    void recoveryRestoresSharedMetadataAndClampsOtherViewsWithoutReplacingTheirViewport() {
        EditorDocument document = document("alpha", "beta", "gamma");
        TabSession first = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        TabSession other = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        first.engine().moveCursorTo(new Position(1, 2));
        RecoverySnapshot snapshot = first.engine().captureRecoverySnapshot();
        first.engine().insertInput("X");
        other.engine().moveCursorTo(new Position(2, 3));
        other.state().setTopLine(2);

        first.engine().restoreRecoverySnapshot(snapshot);

        assertEquals(List.of("alpha", "beta", "gamma"), other.model().snapshotLines());
        assertEquals(snapshot.cursor(), first.state().getCursor());
        assertEquals(new Position(2, 3), other.state().getCursor());
        assertEquals(2, other.state().getTopLine());
        assertFalse(other.engine().undo());
    }

    @Test
    void aModelCannotAcquireASecondDocumentHistory() {
        EditorDocument document = document("alpha");
        TabSession view = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        assertThrows(IllegalArgumentException.class, () -> new EditorEngine(view.model(), new EditorState(),
            new LogicalEditorTextLayout(view.model())));
    }

    private static EditorDocument document(String... lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(lines));
        return new EditorDocument(model);
    }
}
