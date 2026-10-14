package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EditorEngineSelectionBoundaryTest {
    @Test
    void forwardNavigationAtTheDocumentBoundaryClearsSelectionBeforeTyping() {
        for (Consumer<EditorEngine> navigate : List.<Consumer<EditorEngine>>of(
            EditorEngine::moveRight, EditorEngine::moveWordRight, target -> target.navigateTo(target.state().getCursor(), false)
        )) {
            Fixture fixture = fixture();
            Position end = new Position(1, 4);
            fixture.engine.restoreSelection(end, new Position(0, 0));
            long revision = fixture.model.revision();

            navigate.accept(fixture.engine);

            assertEquals(end, fixture.state.getCursor());
            assertNull(fixture.state.getSelectionAnchor());
            assertEquals(revision, fixture.model.revision());
            assertFalse(fixture.state.isDirty());
            assertFalse(fixture.engine.undo());
            fixture.engine.insertInput("X");
            assertEquals(List.of("alpha", "betaX"), fixture.model.snapshotLines());
        }
    }

    @Test
    void backwardNavigationAtTheDocumentBoundaryClearsSelectionBeforeTyping() {
        for (Consumer<EditorEngine> navigate : List.<Consumer<EditorEngine>>of(
            EditorEngine::moveLeft, EditorEngine::moveWordLeft, target -> target.navigateTo(target.state().getCursor(), false)
        )) {
            Fixture fixture = fixture();
            Position start = new Position(0, 0);
            fixture.engine.restoreSelection(start, new Position(1, 4));
            long revision = fixture.model.revision();

            navigate.accept(fixture.engine);

            assertEquals(start, fixture.state.getCursor());
            assertNull(fixture.state.getSelectionAnchor());
            assertEquals(revision, fixture.model.revision());
            assertFalse(fixture.state.isDirty());
            assertFalse(fixture.engine.undo());
            fixture.engine.insertInput("X");
            assertEquals(List.of("Xalpha", "beta"), fixture.model.snapshotLines());
        }
    }

    @Test
    void aRepeatedNavigationTargetClearsSelectionWithoutMovingTheCursor() {
        Fixture fixture = fixture();
        fixture.engine.restoreSelection(new Position(0, 2), new Position(0, 4));

        fixture.engine.navigateTo(fixture.state.getCursor(), false);

        assertEquals(new Position(0, 2), fixture.state.getCursor());
        assertNull(fixture.state.getSelectionAnchor());
        fixture.engine.restoreSelection(new Position(1, 2), new Position(1, 0));

        fixture.engine.navigateTo(fixture.state.getCursor(), false);

        assertEquals(new Position(1, 2), fixture.state.getCursor());
        assertNull(fixture.state.getSelectionAnchor());
    }

    @Test
    void shiftArrowsAtTheBoundaryRetainTheExistingSelection() {
        Fixture fixture = fixture();
        Position start = new Position(0, 0);
        Position end = new Position(1, 4);
        fixture.engine.restoreSelection(start, end);

        fixture.engine.moveLeft(true);
        fixture.engine.navigateTo(fixture.state.getCursor(), true);

        assertEquals(start, fixture.state.getCursor());
        assertEquals(end, fixture.state.getSelectionAnchor());
        assertTrue(fixture.state.hasSelection());
        fixture.engine.restoreSelection(end, start);

        fixture.engine.moveRight(true);
        fixture.engine.navigateTo(fixture.state.getCursor(), true);

        assertEquals(end, fixture.state.getCursor());
        assertEquals(start, fixture.state.getSelectionAnchor());
        assertTrue(fixture.state.hasSelection());
        assertFalse(fixture.state.isDirty());
        assertFalse(fixture.engine.undo());
    }

    @Test
    void navigationAtABoundaryStillSeparatesTypingHistory() {
        for (Consumer<EditorEngine> navigate : List.<Consumer<EditorEngine>>of(
            EditorEngine::moveRight, EditorEngine::moveWordRight, target -> target.navigateTo(target.state().getCursor(), false)
        )) {
            TextModel model = new TextModel(new SimpleLineBuffer());
            EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
            engine.insertInput("a");

            navigate.accept(engine);
            engine.insertInput("b");

            assertTrue(engine.undo());
            assertEquals("a", model.getLine(0));
            assertTrue(engine.undo());
            assertEquals("", model.getLine(0));
        }
    }

    private static Fixture fixture() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("alpha", "beta"));
        EditorState state = new EditorState();
        return new Fixture(model, state, new EditorEngine(model, state, new LogicalEditorTextLayout(model)));
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
    }
}
