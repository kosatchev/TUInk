package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EditorEngineUnicodeInteractionTest {
    @Test
    void wordSelectionExpandsToCompleteCombiningCluster() {
        Fixture fixture = fixture("cafe\u0301 noir");

        assertTrue(fixture.engine.selectWordAt(new Position(0, 4)));

        assertEquals(new Position(0, 0), fixture.state.getSelectionAnchor());
        assertEquals(new Position(0, 5), fixture.state.getCursor());
    }

    @Test
    void supplementaryInputIsOneUndoableEdit() {
        Fixture fixture = fixture("");

        fixture.engine.insertInput("😀");
        assertEquals("😀", fixture.model.getLine(0));
        assertEquals(new Position(0, 2), fixture.state.getCursor());

        assertTrue(fixture.engine.undo());
        assertEquals("", fixture.model.getLine(0));
    }

    @Test
    void undoOfSeparatelyEnteredCombiningMarkPreservesBaseCharacter() {
        Fixture fixture = fixture("");
        fixture.engine.insertInput("e");
        fixture.engine.moveLeft();
        fixture.engine.moveRight();
        fixture.engine.insertInput("\u0301");

        assertEquals("e\u0301", fixture.model.getLine(0));
        assertTrue(fixture.engine.undo());
        assertEquals("e", fixture.model.getLine(0));
        assertEquals(new Position(0, 1), fixture.state.getCursor());
    }

    private static Fixture fixture(String text) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, text);
        TextModel model = new TextModel(buffer);
        EditorState state = new EditorState();
        return new Fixture(model, state, new EditorEngine(model, state, new TestEditorTextLayout(model)));
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
    }
}
