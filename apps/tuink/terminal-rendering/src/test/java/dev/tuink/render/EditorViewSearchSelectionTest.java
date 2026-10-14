package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;

import org.junit.jupiter.api.Test;

class EditorViewSearchSelectionTest {
    @Test
    void viewportCalculationDoesNotRewriteExactSearchSelectionOffsets() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "e\u0301");
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TerminalEditorTextLayout(model));

        assertTrue(engine.findNext("e"));
        assertEquals(new Position(0, 0), state.getSelectionAnchor());
        assertEquals(new Position(0, 1), state.getCursor());

        EditorViewNavigation.ensureCursorVisible(engine, 2, 8, false, false);

        assertEquals(new Position(0, 0), state.getSelectionAnchor());
        assertEquals(new Position(0, 1), state.getCursor());
    }
}
