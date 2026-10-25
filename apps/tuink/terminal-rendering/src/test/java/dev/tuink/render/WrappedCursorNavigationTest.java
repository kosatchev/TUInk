package dev.tuink.render;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WrappedCursorNavigationTest {
    @Test
    void arrowsTraverseDisplayedRowsWithinOneLogicalLineInBothDirections() {
        EditorEngine engine = engine("abcdefghij");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        engine.moveCursorTo(new Position(0, 1));

        for (int col : new int[] {5, 9, 9}) {
            navigation.moveDown(false, 4, true, false);
            assertEquals(new Position(0, col), engine.state().getCursor());
        }
        for (int col : new int[] {5, 1, 1}) {
            navigation.moveUp(false, 4, true, false);
            assertEquals(new Position(0, col), engine.state().getCursor());
        }
    }

    @Test
    void fullyFilledFinalSegmentHasANavigableInsertionRow() {
        EditorEngine engine = engine("abcdefgh");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        assertEquals(List.of(0, 4, 8), engine.textLayout().wrapStarts(0, 4, false, 4));

        navigation.moveDown(true, 4, true, false);
        navigation.moveDown(true, 4, true, false);

        assertEquals(new Position(0, 8), engine.state().getCursor());
        assertEquals(new Position(0, 0), engine.state().getSelectionAnchor());
        navigation.ensureCursorVisible(1, 4, true, false);
        assertEquals(2, engine.state().getTopWrapSegment());
        navigation.moveDown(false, 4, true, false);
        assertFalse(engine.hasSelection());
        engine.insertInput("X");
        assertEquals("abcdefghX", engine.model().getLine(0));
    }

    @Test
    void preferredColumnSurvivesShortWrappedAndLogicalRows() {
        EditorEngine engine = engine("abcdefghij\nx\nabcdefghij");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        engine.moveCursorTo(new Position(0, 3));
        List<Position> expected = List.of(
            new Position(0, 7), new Position(0, 10), new Position(1, 1), new Position(2, 3)
        );
        for (Position position : expected) {
            navigation.moveDown(false, 4, true, false);
            assertEquals(position, engine.state().getCursor());
        }
        for (Position position : List.of(new Position(1, 1), new Position(0, 10), new Position(0, 7))) {
            navigation.moveUp(false, 4, true, false);
            assertEquals(position, engine.state().getCursor());
        }
    }

    @Test
    void wordWrappingUsesTheSameSegmentsAsRendering() {
        EditorEngine engine = engine("one two three four");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        assertEquals(List.of(0, 8, 14), engine.textLayout().wrapStarts(0, 8, true, 4));
        engine.moveCursorTo(new Position(0, 2));
        navigation.moveDown(false, 8, true, true);
        assertEquals(new Position(0, 10), engine.state().getCursor());
        navigation.moveDown(false, 8, true, true);
        assertEquals(new Position(0, 16), engine.state().getCursor());
        navigation.moveUp(false, 8, true, true);
        assertEquals(new Position(0, 10), engine.state().getCursor());
    }

    @Test
    void verticalMovementKeepsCombiningSequencesAndWideGraphemesIntact() {
        EditorEngine engine = engine("Ae\u0301😀中B");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        assertEquals(List.of(0, 5), engine.textLayout().wrapStarts(0, 4, false, 4));
        engine.moveCursorTo(new Position(0, 1));
        navigation.moveDown(false, 4, true, false);
        assertEquals(new Position(0, 5), engine.state().getCursor());
        navigation.moveUp(false, 4, true, false);
        assertEquals(new Position(0, 1), engine.state().getCursor());
    }

    @Test
    void anOversizedTabDoesNotTrapVerticalNavigationInOneSegment() {
        EditorEngine engine = engine("\tX");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        assertEquals(List.of(0, 1, 2), engine.textLayout().wrapStarts(0, 1, false, 4));
        navigation.moveDown(false, 1, true, false);
        assertEquals(new Position(0, 1), engine.state().getCursor());
        navigation.moveDown(false, 1, true, false);
        assertEquals(new Position(0, 2), engine.state().getCursor());
        navigation.moveUp(false, 1, true, false);
        assertEquals(new Position(0, 1), engine.state().getCursor());
    }

    @Test
    void changingWidthRecomputesThePreferredColumnFromTheNewSegment() {
        EditorEngine engine = engine("abcdefghijklmnop");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        engine.moveCursorTo(new Position(0, 3));
        navigation.moveDown(false, 4, true, false);
        assertEquals(new Position(0, 7), engine.state().getCursor());
        navigation.moveDown(false, 6, true, false);
        assertEquals(new Position(0, 13), engine.state().getCursor());
    }

    @Test
    void pageKeysMoveAndScrollByDisplayedRows() {
        EditorEngine engine = engine("abcdefghijklmnopqrstuvwxyz1234");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        engine.moveCursorTo(new Position(0, 1));
        navigation.pageDown(3, 4, true, false);
        assertEquals(new Position(0, 13), engine.state().getCursor());
        assertEquals(3, engine.state().getTopWrapSegment());
        navigation.pageUp(3, 4, true, false);
        assertEquals(new Position(0, 1), engine.state().getCursor());
        assertEquals(0, engine.state().getTopWrapSegment());
    }

    @Test
    void pageKeysPreserveExistingSelectionWhileScrollingWrappedRows() {
        EditorEngine engine = engine("abcdefghijklmnopqrstuvwxyz1234");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        engine.moveCursorTo(new Position(0, 1));
        navigation.moveDown(true, 4, true, false);
        navigation.pageDown(3, 4, true, false);

        assertEquals(new Position(0, 5), engine.state().getCursor());
        assertEquals(new Position(0, 1), engine.state().getSelectionAnchor());
        assertEquals(3, engine.state().getTopWrapSegment());
    }

    @Test
    void disablingWrappingRestoresLogicalRowNavigation() {
        EditorEngine engine = engine("abcdefghij\nabcdefghij");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        engine.moveCursorTo(new Position(0, 5));
        navigation.moveDown(false, 4, true, false);
        assertEquals(new Position(0, 9), engine.state().getCursor());
        navigation.moveDown(false, 4, false, false);
        assertEquals(new Position(1, 9), engine.state().getCursor());
    }

    @Test
    void changingTabWidthRecomputesThePreferredVisualColumn() {
        EditorEngine engine = engine("abcde\n\tZ\nabcdefgh");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        engine.moveCursorTo(new Position(0, 3));
        navigation.moveDown(false, 8, true, false);
        assertEquals(new Position(1, 1), engine.state().getCursor());
        engine.setTabWidth(2);
        navigation.moveDown(false, 8, true, false);
        assertEquals(new Position(2, 2), engine.state().getCursor());
    }

    @Test
    void openingAnotherFileResetsThePreferredColumn(@TempDir Path directory) throws IOException {
        EditorEngine engine = engine("abcdefghij");
        EditorViewNavigation navigation = new EditorViewNavigation(engine);
        engine.moveCursorTo(new Position(0, 3));
        navigation.moveDown(false, 4, true, false);
        Path file = directory.resolve("next.txt");
        Files.writeString(file, "abcde");
        engine.open(JvmDocumentStorage.resource(file));
        navigation.moveDown(false, 4, true, false);
        assertEquals(new Position(0, 4), engine.state().getCursor());
    }

    private static EditorEngine engine(String text) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), text);
        return new EditorEngine(model, new EditorState(), new TerminalEditorTextLayout(model));
    }
}
