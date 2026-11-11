package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineMoveSelectionTest {
    @Test void movesBothDirectionsInOneUndoStepWithoutPublishingClipboard() {
        for (boolean backwards : List.of(false, true)) {
            var buffer = new SimpleLineBuffer(); buffer.setLine(0, "one TWO three");
            var model = new TextModel(buffer); var clipboard = new EditorClipboard();
            clipboard.setText("keep clipboard"); long publication = clipboard.revision();
            var engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model), clipboard);
            var start = new Position(0, 4); var end = new Position(0, 7);
            engine.restoreSelection(end, start);
            assertTrue(engine.moveSelectionTo(new Position(0, backwards ? 0 : 13), model.revision(), start, end));
            assertEquals(backwards ? "TWOone  three" : "one  threeTWO", model.getLine(0));
            assertEquals("TWO", model.getTextRange(engine.state().getSelectionAnchor(), engine.state().getCursor()));
            assertEquals("keep clipboard", clipboard.text()); assertEquals(publication, clipboard.revision());
            assertTrue(engine.undo()); assertEquals("one TWO three", model.getLine(0));
            assertEquals(start, engine.state().getSelectionAnchor()); assertEquals(end, engine.state().getCursor());
            assertFalse(engine.undo(), "The move must have exactly one history entry");
            assertTrue(engine.redo()); assertEquals(backwards ? "TWOone  three" : "one  threeTWO", model.getLine(0));
        }
    }

    @Test void multilineMoveRetainsMixedLineEndingsAndUnicodeInBothDirections() {
        for (boolean backwards : List.of(false, true)) {
            var buffer = new SimpleLineBuffer(); buffer.setLine(0, "α😀");
            buffer.insertLine(1, "éX"); buffer.insertLine(2, "tail");
            var model = new TextModel(buffer);
            model.restoreFileMetadata(model.fileMetadata().withLineSeparators(List.of("\r\n", "\n")));
            var metadata = model.fileMetadata();
            var engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
            var start = new Position(0, 1); var end = new Position(1, 2);
            engine.restoreSelection(end, start);
            assertTrue(engine.moveSelectionTo(backwards ? new Position(0, 0) : new Position(2, 4), model.revision(), start, end));
            assertEquals(backwards ? List.of("😀", "éαX", "tail") : List.of("αX", "tail😀", "é"), model.snapshotLines());
            assertEquals(backwards ? List.of("\r\n", "\n") : List.of("\n", "\r\n"), model.fileMetadata().lineSeparators());
            assertEquals("😀\né", model.getTextRange(engine.state().getSelectionAnchor(), engine.state().getCursor()));
            assertTrue(engine.undo()); assertEquals(List.of("α😀", "éX", "tail"), model.snapshotLines());
            assertEquals(metadata, model.fileMetadata()); assertTrue(engine.redo());
        }
    }

    @Test void selfDropBoundariesAndStaleRevisionCreateNoEditOrUndoEntry() {
        var buffer = new SimpleLineBuffer(); buffer.setLine(0, "abcdef");
        var model = new TextModel(buffer); var engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        var start = new Position(0, 1); var end = new Position(0, 4);
        engine.restoreSelection(end, start); long revision = model.revision();
        for (int column : List.of(1, 2, 4)) assertFalse(engine.moveSelectionTo(new Position(0, column), revision, start, end));
        assertFalse(engine.moveSelectionTo(new Position(0, 6), revision + 1, start, end));
        assertEquals(revision, model.revision()); assertFalse(engine.undo());
        engine.restoreSelection(new Position(0, 3), start);
        assertFalse(engine.moveSelectionTo(new Position(0, 6), revision, start, end));
        assertEquals("abcdef", model.getLine(0));
    }

    @Test void movingLineContentsKeepsAnEmptyLineBetweenCrAndLfAfterSaveAndReopen() throws Exception {
        var buffer = new SimpleLineBuffer(); buffer.setLine(0, "a");
        buffer.insertLine(1, "BLOCK"); buffer.insertLine(2, "z");
        var model = new TextModel(buffer);
        model.restoreFileMetadata(model.fileMetadata().withLineSeparators(List.of("\r", "\n")));
        var before = model.fileMetadata();
        var engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        var start = new Position(1, 0); var end = new Position(1, 5);
        engine.restoreSelection(end, start);
        assertTrue(engine.moveSelectionTo(new Position(0, 1), model.revision(), start, end));
        assertEquals(List.of("aBLOCK", "", "z"), model.snapshotLines());
        assertEquals(List.of("\r", "\r\n"), model.fileMetadata().lineSeparators());
        var bytes = new java.io.ByteArrayOutputStream();
        DocumentCodec.write(bytes, model.snapshotLines(), model.fileMetadata());
        var reopened = DocumentCodec.read(new java.io.ByteArrayInputStream(bytes.toByteArray()),
            dev.tuink.editor.storage.DocumentOpenMode.UTF_8, DocumentCodec.currentLoadLimits(), "moved text");
        assertEquals(model.snapshotLines(), reopened.lines());
        assertTrue(engine.undo()); assertEquals(List.of("a", "BLOCK", "z"), model.snapshotLines());
        assertEquals(before, model.fileMetadata());
    }
}
