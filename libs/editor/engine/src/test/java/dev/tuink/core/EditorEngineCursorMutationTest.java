package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.unicode.GraphemeText;
import org.junit.jupiter.api.Test;

class EditorEngineCursorMutationTest {
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
    void navigationTargetsRespectGraphemesSelectionAndTypingHistoryWithoutChangingTheDocument() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "A👩‍💻B");
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        long revision = model.revision();
        long navigation = engine.navigationRevision();

        engine.navigateTo(new Position(0, 3), true);

        assertEquals(new Position(0, 1), state.getCursor());
        assertEquals(new Position(0, 0), state.getSelectionAnchor());
        assertTrue(engine.navigationRevision() > navigation);
        assertEquals(revision, model.revision());
        engine.navigateTo(state.getCursor(), false);
        assertNull(state.getSelectionAnchor());
        engine.insertInput("x");
        engine.navigateTo(state.getCursor(), false);
        engine.insertInput("y");
        assertTrue(engine.undo());
        assertEquals("Ax👩‍💻B", model.getLine(0));
        assertTrue(engine.undo());
        assertEquals("A👩‍💻B", model.getLine(0));
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
