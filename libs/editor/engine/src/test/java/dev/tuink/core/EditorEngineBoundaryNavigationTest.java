package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineBoundaryNavigationTest {
    @Test
    void smartLineBoundariesRetainTheSelectionAnchorAndKeepUnicodeClustersIntact() {
        EditorEngine engine = engine(List.of("  e\u0301😀  "));
        engine.moveCursorTo(new Position(0, 4));
        long revision = engine.model().revision();

        engine.moveHomeSmart(true);
        assertEquals(new Position(0, 2), engine.state().getCursor());
        assertEquals(new Position(0, 4), engine.state().getSelectionAnchor());
        engine.moveHomeSmart(true);
        assertEquals(new Position(0, 0), engine.state().getCursor());
        engine.moveEndSmart(true);
        assertEquals(new Position(0, 6), engine.state().getCursor());
        engine.moveEndSmart(true);
        assertEquals(new Position(0, 8), engine.state().getCursor());
        assertEquals(new Position(0, 4), engine.state().getSelectionAnchor());

        engine.moveEnd(false);
        assertEquals(new Position(0, 8), engine.state().getCursor());
        assertNull(engine.state().getSelectionAnchor());
        assertEquals(revision, engine.model().revision());
        assertFalse(engine.state().isDirty());
        assertFalse(engine.undo());
    }

    @Test
    void smartEndTrimsOnlyWholeWhitespaceGraphemesAndNeverGetsStuckInsideACluster() {
        for (String content : List.of("A\u0600 ", "\u0600 ", "Ae\u0301", "😀")) {
            for (String padding : List.of("", "  ", "\t")) {
                for (boolean selecting : List.of(false, true)) {
                    EditorEngine engine = engine(List.of(content + padding));

                    engine.moveEndSmart(selecting);
                    assertEquals(new Position(0, content.length()), engine.state().getCursor());
                    engine.moveEndSmart(selecting);
                    assertEquals(new Position(0, content.length() + padding.length()), engine.state().getCursor());
                    engine.moveEndSmart(selecting);
                    assertEquals(new Position(0, content.length()), engine.state().getCursor());
                    assertEquals(content + padding, engine.model().getLine(0));
                    assertFalse(engine.state().isDirty());
                    assertFalse(engine.undo());
                }
            }
        }
    }

    @Test
    void documentBoundariesIncludeTheFinalEmptyLineAndPreserveRedo() {
        List<String> original = List.of("Ae\u0301", "😀", "");
        EditorEngine engine = engine(original);
        engine.moveCursorTo(new Position(1, 2));

        engine.moveDocumentStart(true);
        assertEquals(new Position(0, 0), engine.state().getCursor());
        assertEquals(new Position(1, 2), engine.state().getSelectionAnchor());
        engine.moveDocumentEnd(true);
        assertEquals(new Position(2, 0), engine.state().getCursor());
        assertEquals(new Position(1, 2), engine.state().getSelectionAnchor());
        engine.moveDocumentStart(false);
        assertNull(engine.state().getSelectionAnchor());
        engine.insertInput("X");
        assertTrue(engine.undo());
        engine.moveDocumentEnd(false);
        assertTrue(engine.redo());
        assertEquals(List.of("XAe\u0301", "😀", ""), engine.model().snapshotLines());
        assertTrue(engine.undo());
        assertEquals(original, engine.model().snapshotLines());
        assertFalse(engine.undo());
    }

    @Test
    void lineBoundaryNavigationSeparatesTypingEvenWhenTheCursorIsAlreadyAtTheTarget() {
        EditorEngine engine = engine(List.of(""));
        engine.insertInput("a");
        engine.moveEnd(true);
        engine.insertInput("b");

        assertTrue(engine.undo());
        assertEquals("a", engine.model().getLine(0));
        assertTrue(engine.undo());
        assertEquals("", engine.model().getLine(0));
    }

    private static EditorEngine engine(List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        return new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
    }
}
