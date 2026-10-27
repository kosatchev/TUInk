package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EditorViewNavigationTest {
    @Test
    void ordinaryVerticalNavigationKeepsOnePreferredVisualColumnAcrossShortRows() {
        Fixture fixture = fixture("A界abcdef", "x", "A界abcdef");
        fixture.engine.moveCursorTo(new Position(0, 6));

        fixture.navigation.moveDown(false, 30, false, false);
        assertEquals(new Position(1, 1), fixture.state.getCursor());
        fixture.navigation.moveDown(false, 10, false, true);
        assertEquals(new Position(2, 6), fixture.state.getCursor(), "unwrapped width/word-wrap changes do not change geometry");
        fixture.navigation.moveUp(false, 10, false, false);
        fixture.navigation.moveUp(false, 10, false, false);
        assertEquals(new Position(0, 6), fixture.state.getCursor());
    }

    @Test
    void pointerAndLogicalCursorActionsResetPreferredColumnEvenAtTheSameCursor() {
        for (Consumer<EditorEngine> action : List.<Consumer<EditorEngine>>of(
            engine -> engine.beginSelectionAt(new Position(1, 0)),
            engine -> engine.extendSelectionTo(new Position(1, 0)),
            engine -> engine.restoreSelection(new Position(1, 0), null),
            EditorEngine::moveHome, EditorEngine::moveHomeSmart,
            engine -> engine.moveCursorTo(new Position(1, 0))
        )) {
            Fixture fixture = fixture("0123456789", "", "abcdefghij");
            fixture.engine.moveCursorTo(new Position(0, 8));
            fixture.navigation.moveDown(false, 20, false, false);
            assertEquals(new Position(1, 0), fixture.state.getCursor());

            action.accept(fixture.engine);
            fixture.navigation.moveDown(false, 20, false, false);

            assertEquals(new Position(2, 0), fixture.state.getCursor());
        }
    }

    @Test
    void copyingAndEnsuringVisibilityPreserveThePreferredColumn() {
        Fixture fixture = fixture("0123456789", "x", "abcdefghij");
        fixture.engine.moveCursorTo(new Position(0, 8));
        fixture.navigation.moveDown(false, 20, false, false);
        fixture.engine.copyCurrentLine();
        fixture.navigation.ensureCursorVisible(2, 20, false, false);

        fixture.navigation.moveDown(false, 20, false, false);

        assertEquals(new Position(2, 8), fixture.state.getCursor());
    }

    @Test
    void editsAndUndoWhileTheViewIsInactiveInvalidateItsRetainedColumn() {
        Fixture first = fixture("0123456789", "x", "abcdefghij");
        Fixture other = fixture("another", "tab");
        first.engine.moveCursorTo(new Position(0, 8));
        first.navigation.moveDown(false, 20, false, false);

        other.navigation.moveDown(false, 10, false, false);
        first.engine.insertInput("Z");
        assertTrue(first.engine.undo());
        assertEquals(new Position(1, 1), first.state.getCursor());
        first.navigation.moveDown(false, 20, false, false);

        assertEquals(new Position(2, 1), first.state.getCursor());
        assertTrue(first.engine.redo(), "navigation must preserve the redo stack");
        assertEquals(List.of("0123456789", "xZ", "abcdefghij"), first.model.snapshotLines());
    }

    @Test
    void boundaryNavigationClearsOrRetainsSelectionAndSeparatesTypingHistory() {
        for (boolean wrapped : List.of(false, true)) {
            Fixture fixture = fixture("abc");
            fixture.engine.restoreSelection(new Position(0, 3), new Position(0, 0));

            fixture.navigation.moveDown(false, 20, wrapped, false);

            assertEquals(new Position(0, 3), fixture.state.getCursor());
            assertNull(fixture.state.getSelectionAnchor());
            assertFalse(fixture.engine.undo());
            fixture.engine.insertInput("X");
            fixture.navigation.moveDown(false, 20, wrapped, false);
            fixture.engine.insertInput("Y");
            assertTrue(fixture.engine.undo());
            assertEquals("abcX", fixture.model.getLine(0));
            assertTrue(fixture.engine.undo());
            assertEquals("abc", fixture.model.getLine(0));

            fixture.engine.restoreSelection(new Position(0, 0), new Position(0, 3));
            fixture.navigation.moveUp(true, 20, wrapped, false);
            assertEquals(new Position(0, 0), fixture.state.getCursor());
            assertEquals(new Position(0, 3), fixture.state.getSelectionAnchor());
        }
    }

    @Test
    void verticalAndPointerHitsResolveTheSameWrappedRowEnds() {
        Fixture fixture = fixture("abcdef", "a\t界👩‍💻e\u0301 words");
        for (boolean words : List.of(false, true)) {
            for (int width : new int[] {1, 2, 4, 8}) {
                fixture.engine.moveCursorTo(new Position(0, 3));
                EditorViewCoordinates coordinates = new EditorViewCoordinates(fixture.engine.textLayout(), width, true, words, 4);
                var origin = coordinates.toVisual(fixture.state.getCursor());

                fixture.navigation.moveDown(false, width, true, words);

                int nextRow = Math.min(origin.row() + 1, coordinates.totalRows() - 1);
                assertEquals(coordinates.positionAt(nextRow, origin.column()), fixture.state.getCursor());
                assertEquals(nextRow, coordinates.toVisual(fixture.state.getCursor()).row());
            }
        }
    }

    @Test
    void unwrappedPageKeysKeepTheExistingSelectionAndLandAtVisualGraphemeBoundaries() {
        Fixture fixture = fixture("0123456789", "A👩‍💻B", "abcdefghij", "last");
        fixture.engine.moveCursorTo(new Position(0, 3));
        fixture.navigation.pageDown(1, 20, false, false);
        assertEquals(new Position(1, 6), fixture.state.getCursor(), "visual column 3 follows the complete emoji");
        fixture.engine.restoreSelection(new Position(1, 6), new Position(0, 0));

        fixture.navigation.pageDown(2, 20, false, false);

        assertEquals(new Position(1, 6), fixture.state.getCursor());
        assertEquals(new Position(0, 0), fixture.state.getSelectionAnchor());
        assertEquals(3, fixture.state.getTopLine());
    }

    @Test
    void unwrappedPageKeysKeepTheVisualColumnOfTabsWideCharactersAndCombiningText() {
        record Example(String text, int textColumn, int visualColumn) {}
        for (Example example : List.of(
            new Example("\tX", 1, 4),
            new Example("界X", 1, 2),
            new Example("😀X", 2, 2),
            new Example("e\u0301X", 2, 1),
            new Example("\t界👩‍💻e\u0301X", 9, 9)
        )) {
            Fixture down = fixture(example.text(), "middle", "0123456789abcdef");
            down.engine.moveCursorTo(new Position(0, example.textColumn()));

            down.navigation.pageDown(2, 30, false, false);

            assertEquals(new Position(2, example.visualColumn()), down.state.getCursor(), example.text());
            Fixture up = fixture("0123456789abcdef", "middle", example.text());
            up.engine.moveCursorTo(new Position(2, example.textColumn()));
            up.state.setTopLine(2);

            up.navigation.pageUp(2, 30, false, false);

            assertEquals(new Position(0, example.visualColumn()), up.state.getCursor(), example.text());
            assertFalse(down.state.isDirty());
            assertFalse(up.state.isDirty());
        }
    }

    @Test
    void repeatedUnwrappedPageKeysRestorePreferredColumnAfterShortLinesInBothDirections() {
        Fixture fixture = fixture("0123456789", "middle", "x", "middle", "abcdefghij");
        fixture.engine.moveCursorTo(new Position(0, 8));
        fixture.state.setLeftVisualColumn(3);

        fixture.navigation.pageDown(2, 20, false, false);
        assertEquals(new Position(2, 1), fixture.state.getCursor());
        assertEquals(2, fixture.state.getTopLine());
        fixture.navigation.pageDown(2, 10, false, true);
        assertEquals(new Position(4, 8), fixture.state.getCursor());
        assertEquals(4, fixture.state.getTopLine());
        fixture.navigation.pageUp(2, 20, false, false);
        assertEquals(new Position(2, 1), fixture.state.getCursor());
        fixture.navigation.pageUp(2, 20, false, false);
        assertEquals(new Position(0, 8), fixture.state.getCursor());
        assertEquals(0, fixture.state.getTopLine());
        assertEquals(3, fixture.state.getLeftVisualColumn(), "page scrolling retains the manual horizontal offset");
        assertFalse(fixture.engine.undo());
    }

    @Test
    void arrowsAndPageKeysShareThePreferredColumn() {
        Fixture fixture = fixture("0123456789", "x", "middle", "y", "abcdefghij");
        fixture.engine.moveCursorTo(new Position(0, 8));

        fixture.navigation.moveDown(false, 20, false, false);
        fixture.navigation.pageDown(2, 20, false, false);
        assertEquals(new Position(3, 1), fixture.state.getCursor());
        fixture.navigation.moveDown(false, 20, false, false);
        assertEquals(new Position(4, 8), fixture.state.getCursor());
        fixture.navigation.moveUp(false, 20, false, false);
        fixture.navigation.pageUp(2, 20, false, false);
        assertEquals(new Position(1, 1), fixture.state.getCursor());
        fixture.navigation.moveUp(false, 20, false, false);
        assertEquals(new Position(0, 8), fixture.state.getCursor());
    }

    @Test
    void anExplicitCursorActionBetweenPagesResetsThePreferredColumn() {
        Fixture fixture = fixture("0123456789", "middle", "x", "middle", "abcdefghij");
        fixture.engine.moveCursorTo(new Position(0, 8));
        fixture.navigation.pageDown(2, 20, false, false);
        fixture.engine.moveCursorTo(fixture.state.getCursor());

        fixture.navigation.pageDown(2, 20, false, false);

        assertEquals(new Position(4, 1), fixture.state.getCursor());
    }

    @Test
    void pageKeysWithASelectionOnlyScrollTheViewport() {
        for (boolean wrapped : List.of(false, true)) {
            Fixture fixture = fixture("0123456789", "abcdefghij", "middle", "last");
            fixture.engine.restoreSelection(new Position(1, 3), new Position(0, 2));
            fixture.state.setTopLine(1);
            fixture.state.setLeftVisualColumn(5);
            long revision = fixture.model.revision();
            long navigationRevision = fixture.engine.navigationRevision();

            fixture.navigation.pageDown(2, 20, wrapped, false);
            assertEquals(wrapped ? 2 : 3, fixture.state.getTopLine());
            fixture.navigation.pageUp(2, 20, wrapped, false);

            assertEquals(wrapped ? 0 : 1, fixture.state.getTopLine());
            assertEquals(new Position(1, 3), fixture.state.getCursor());
            assertEquals(new Position(0, 2), fixture.state.getSelectionAnchor());
            assertEquals(5, fixture.state.getLeftVisualColumn());
            assertEquals(revision, fixture.model.revision());
            assertEquals(navigationRevision, fixture.engine.navigationRevision());
            assertFalse(fixture.engine.undo());
        }
    }

    @Test
    void pageNavigationSeparatesTypingHistoryWithoutEditingText() {
        Fixture fixture = fixture("abc", "def", "ghi");
        fixture.engine.moveEnd();
        fixture.engine.insertInput("X");

        fixture.navigation.pageDown(1, 20, false, false);
        assertEquals(List.of("abcX", "def", "ghi"), fixture.model.snapshotLines());
        fixture.engine.insertInput("Y");

        assertTrue(fixture.engine.undo());
        assertEquals(List.of("abcX", "def", "ghi"), fixture.model.snapshotLines());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("abc", "def", "ghi"), fixture.model.snapshotLines());
        assertFalse(fixture.engine.undo());
        assertTrue(fixture.engine.redo());
        assertTrue(fixture.engine.redo());
        assertEquals(List.of("abcX", "defY", "ghi"), fixture.model.snapshotLines());
    }

    private static Fixture fixture(String... lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(lines));
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TerminalEditorTextLayout(model));
        return new Fixture(model, state, engine, new EditorViewNavigation(engine, engine::navigateTo));
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine, EditorViewNavigation navigation) {
    }
}
