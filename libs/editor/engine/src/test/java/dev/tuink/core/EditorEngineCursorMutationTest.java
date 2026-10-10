package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.unicode.GraphemeText;
import org.junit.jupiter.api.Test;

class EditorEngineCursorMutationTest {
    @Test
    void pointerMutationResetsThePreferredVerticalColumn() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "0123456789\nx\nabcdefghij");
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));

        engine.moveCursorTo(new Position(0, 8));
        engine.moveDown();
        assertEquals(new Position(1, 1), state.getCursor());

        engine.beginSelectionAt(new Position(1, 0));
        engine.moveDown();

        assertEquals(new Position(2, 0), state.getCursor());
    }

    @Test
    void publicCursorBoundaryNeverLeavesStateInsideAGrapheme() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "A😀B");
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));

        engine.moveCursorTo(new Position(0, 2));
        assertEquals(new Position(0, 1), state.getCursor());

        engine.beginSelectionAt(new Position(0, 2));
        engine.extendSelectionTo(new Position(0, 4));
        assertEquals(new Position(0, 1), state.getSelectionAnchor());
        assertEquals(new Position(0, 4), state.getCursor());
    }

    @Test
    void smartHomeTreatsWhitespaceWithACombiningMarkAsOneContentGrapheme() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "  \u0301abc");
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));

        engine.moveEnd();
        engine.moveHomeSmart();
        assertEquals(new Position(0, 1), state.getCursor());
        assertTrue(GraphemeText.isBoundary(model.getLine(0), state.getCursor().col()));
        engine.moveHomeSmart();
        assertEquals(new Position(0, 0), state.getCursor());
        engine.moveHomeSmart();
        assertEquals(new Position(0, 1), state.getCursor());

        engine.backspace();
        assertEquals(" \u0301abc", model.getLine(0));
        engine.moveEnd();
        engine.moveHomeSmart();
        assertEquals(new Position(0, 0), state.getCursor());
        engine.backspace();
        assertEquals(" \u0301abc", model.getLine(0));
        engine.moveRight();
        engine.backspace();
        assertEquals("abc", model.getLine(0));
    }
}
