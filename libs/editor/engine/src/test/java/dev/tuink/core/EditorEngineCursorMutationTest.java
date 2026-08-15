package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
