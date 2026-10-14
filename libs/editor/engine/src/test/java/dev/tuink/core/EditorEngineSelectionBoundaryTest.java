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
    void horizontalArrowsCollapseEitherSelectionDirectionToTheCorrespondingBoundary() {
        record Example(List<String> lines, Position start, Position end) {}
        for (Example example : List.of(
            new Example(List.of("abcdef"), new Position(0, 1), new Position(0, 4)),
            new Example(List.of("A界👩‍💻e\u0301Z"), new Position(0, 1), new Position(0, 9)),
            new Example(List.of("A👩‍💻tail", "x", "e\u0301界Z"), new Position(0, 1), new Position(2, 3))
        )) {
            for (boolean reversed : List.of(false, true)) {
                for (boolean left : List.of(false, true)) {
                    Fixture fixture = fixture(example.lines().toArray(String[]::new));
                    fixture.engine.restoreSelection(reversed ? example.start() : example.end(),
                        reversed ? example.end() : example.start());
                    long revision = fixture.model.revision();
                    long navigationRevision = fixture.engine.navigationRevision();

                    if (left) {
                        fixture.engine.moveLeft();
                    } else {
                        fixture.engine.moveRight();
                    }

                    assertEquals(left ? example.start() : example.end(), fixture.state.getCursor());
                    assertNull(fixture.state.getSelectionAnchor());
                    assertEquals(example.lines(), fixture.model.snapshotLines());
                    assertEquals(revision, fixture.model.revision());
                    assertTrue(fixture.engine.navigationRevision() > navigationRevision);
                    assertFalse(fixture.state.isDirty());
                    assertFalse(fixture.engine.undo());
                }
            }
        }
    }

    @Test
    void collapsingAPartialGraphemeMatchStillLandsOnAGraphemeBoundary() {
        record Example(String text, int start, int end, int left, int right) {}
        for (Example example : List.of(
            new Example("e\u0301x", 0, 1, 0, 2),
            new Example("e\u0301x", 1, 2, 0, 2),
            new Example("A👩‍💻Z", 4, 6, 1, 6)
        )) {
            for (boolean reversed : List.of(false, true)) {
                Fixture fixture = fixture(example.text());
                Position start = new Position(0, example.start());
                Position end = new Position(0, example.end());
                fixture.engine.restoreSelection(reversed ? start : end, reversed ? end : start);

                fixture.engine.moveLeft();

                assertEquals(new Position(0, example.left()), fixture.state.getCursor());
                assertNull(fixture.state.getSelectionAnchor());
                fixture.engine.restoreSelection(reversed ? start : end, reversed ? end : start);

                fixture.engine.moveRight();

                assertEquals(new Position(0, example.right()), fixture.state.getCursor());
                assertNull(fixture.state.getSelectionAnchor());
                assertEquals(example.text(), fixture.model.getLine(0));
                assertFalse(fixture.engine.undo());
            }
        }
    }

    @Test
    void shiftArrowsKeepMovingTheActiveEndOfAnExistingSelection() {
        Fixture fixture = fixture("abcdef");
        fixture.engine.restoreSelection(new Position(0, 4), new Position(0, 1));

        fixture.engine.moveLeft(true);

        assertEquals(new Position(0, 3), fixture.state.getCursor());
        assertEquals(new Position(0, 1), fixture.state.getSelectionAnchor());
        fixture.engine.restoreSelection(new Position(0, 1), new Position(0, 4));

        fixture.engine.moveRight(true);

        assertEquals(new Position(0, 2), fixture.state.getCursor());
        assertEquals(new Position(0, 4), fixture.state.getSelectionAnchor());
    }

    @Test
    void collapsingSelectionPreservesRedoAndAddsNoUndoEntry() {
        for (Consumer<EditorEngine> collapse : List.<Consumer<EditorEngine>>of(
            EditorEngine::moveLeft, EditorEngine::moveRight
        )) {
            Fixture fixture = fixture("abcdef");
            fixture.engine.moveEnd();
            fixture.engine.insertInput("X");
            assertTrue(fixture.engine.undo());
            fixture.engine.restoreSelection(new Position(0, 4), new Position(0, 1));

            collapse.accept(fixture.engine);

            assertTrue(fixture.engine.redo());
            assertEquals("abcdefX", fixture.model.getLine(0));
            assertTrue(fixture.engine.undo());
            assertEquals("abcdef", fixture.model.getLine(0));
            assertFalse(fixture.state.isDirty());
            assertFalse(fixture.engine.undo());
        }
    }

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
        return fixture("alpha", "beta");
    }

    private static Fixture fixture(String... lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(lines));
        EditorState state = new EditorState();
        return new Fixture(model, state, new EditorEngine(model, state, new LogicalEditorTextLayout(model)));
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
    }
}
