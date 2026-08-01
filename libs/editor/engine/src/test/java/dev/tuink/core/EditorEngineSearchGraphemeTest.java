package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EditorEngineSearchGraphemeTest {
    @Test
    void viewportCalculationDoesNotRewriteExactSearchSelectionOffsets() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "e\u0301");
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));

        assertTrue(engine.findNext("e"));
        assertEquals(new Position(0, 0), state.getSelectionAnchor());
        assertEquals(new Position(0, 1), state.getCursor());

        engine.ensureCursorVisible(2, 8, false, false);

        assertEquals(new Position(0, 0), state.getSelectionAnchor());
        assertEquals(new Position(0, 1), state.getCursor());
    }
}
