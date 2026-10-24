package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.diff.LineDiff;
import dev.tuink.editor.diff.LineEdit;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EditorEnginePublicationBoundaryTest {
    @Test
    void keyboardInputCutsAndPreparedReplacementPublishWithoutCallingTheDiffProvider() {
        record Attempt(String name, Consumer<EditorEngine> command, String text, String clipboard, Position cursor) {}
        List<String> original = List.of("first", "tail");
        for (Attempt attempt : List.of(
            new Attempt("typing", engine -> engine.insertInput("X"), "X", "keep clipboard", new Position(0, 1)),
            new Attempt("cut selection", engine -> assertTrue(engine.cutSelection()), "", "first\ntail", new Position(0, 0)),
            new Attempt("cut line", engine -> assertTrue(engine.cutCurrentLine()), "tail", "first\n", new Position(0, 0)),
            new Attempt("prepared paste", engine -> assertTrue(engine.pasteText("replacement")),
                "replacement", "keep clipboard", new Position(0, 11))
        )) {
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.replaceLines(original);
            EditorDocument document = new EditorDocument(model);
            EditorClipboard clipboard = new EditorClipboard();
            ThrowingDiff diff = new ThrowingDiff();
            TabSession origin = document.attach(LogicalEditorTextLayout::new, clipboard, diff);
            EditorEngine engine = origin.engine();
            engine.restoreSelection(new Position(0, 0), new Position(1, 4));
            TabSession foreign = document.attach(LogicalEditorTextLayout::new, clipboard);
            foreign.engine().restoreSelection(new Position(1, 4), new Position(1, 2));
            foreign.state().setViewport(new ViewportState(1, 0, 2, 3));
            ViewportState foreignViewport = foreign.state().viewport();
            long foreignNavigation = foreign.engine().navigationRevision();
            long revision = model.revision();
            long recoveryGeneration = document.recoveryGeneration;
            int availabilityCalls = diff.availabilityCalls;
            int computations = diff.computations;
            clipboard.setText("keep clipboard");
            diff.fail = true;

            assertDoesNotThrow(() -> attempt.command().accept(engine), attempt.name());

            assertEquals(availabilityCalls, diff.availabilityCalls,
                attempt.name() + ": text publication must not call an optional provider");
            assertEquals(computations, diff.computations, attempt.name());
            assertEquals(List.of(attempt.text()), model.snapshotLines(), attempt.name());
            assertTrue(model.revision() > revision, attempt.name());
            assertEquals(recoveryGeneration + 1, document.recoveryGeneration, attempt.name());
            assertEquals(attempt.cursor(), origin.state().getCursor(), attempt.name());
            assertNull(origin.state().getSelectionAnchor(), attempt.name());
            assertEquals(new Position(0, Math.min(4, attempt.text().length())),
                foreign.state().getCursor(), attempt.name());
            assertEquals(new Position(0, Math.min(2, attempt.text().length())),
                foreign.state().getSelectionAnchor(), attempt.name());
            assertEquals(foreignNavigation + 1, foreign.engine().navigationRevision(), attempt.name());
            assertEquals(foreignViewport, foreign.state().viewport(), attempt.name());
            assertEquals(attempt.clipboard(), clipboard.text(), attempt.name());
            assertTrue(origin.state().isDirty(), attempt.name());
            assertEquals(1, document.history.undoStack.size(), attempt.name());
            assertTrue(document.history.redoStack.isEmpty(), attempt.name());

            diff.fail = false;
            assertTrue(engine.undo(), attempt.name());
            assertEquals(original, model.snapshotLines(), attempt.name());
            assertFalse(origin.state().isDirty(), attempt.name());
            assertFalse(engine.undo(), attempt.name());
            assertTrue(engine.redo(), attempt.name());
            assertEquals(List.of(attempt.text()), model.snapshotLines(), attempt.name());
            assertFalse(engine.redo(), attempt.name());
        }
    }

    @Test
    void lineCommentPublicationDoesNotCallTheDiffProvider() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("// first", "tail"));
        EditorDocument document = new EditorDocument(model);
        EditorClipboard clipboard = new EditorClipboard();
        ThrowingDiff diff = new ThrowingDiff();
        EditorEngine engine = document.attach(LogicalEditorTextLayout::new, clipboard, diff).engine();
        engine.state().setCurrentFile(Path.of("Document.java"));
        engine.moveEnd();
        TabSession foreign = document.attach(LogicalEditorTextLayout::new, clipboard);
        foreign.engine().restoreSelection(new Position(0, 8), new Position(0, 7));
        long foreignNavigation = foreign.engine().navigationRevision();
        long recoveryGeneration = document.recoveryGeneration;
        int availabilityCalls = diff.availabilityCalls;
        int computations = diff.computations;
        clipboard.setText("keep clipboard");
        diff.fail = true;

        EditorEngine.LineCommentResult result = assertDoesNotThrow(engine::toggleLineComment);

        assertEquals(EditorEngine.LineCommentAction.UNCOMMENTED, result.action());
        assertEquals(1, result.lineCount());
        assertEquals(availabilityCalls, diff.availabilityCalls);
        assertEquals(computations, diff.computations);
        assertEquals(List.of("first", "tail"), model.snapshotLines());
        assertEquals(new Position(0, 5), engine.state().getCursor());
        assertEquals(new Position(0, 5), foreign.state().getCursor());
        assertEquals(new Position(0, 5), foreign.state().getSelectionAnchor());
        assertEquals(foreignNavigation + 1, foreign.engine().navigationRevision());
        assertEquals(recoveryGeneration + 1, document.recoveryGeneration);
        assertEquals("keep clipboard", clipboard.text());
        assertEquals(1, document.history.undoStack.size());
        assertTrue(document.history.redoStack.isEmpty());
        assertTrue(engine.state().isDirty());

        diff.fail = false;
        assertTrue(engine.undo());
        assertEquals(List.of("// first", "tail"), model.snapshotLines());
        assertFalse(engine.state().isDirty());
        assertTrue(engine.redo());
        assertEquals(List.of("first", "tail"), model.snapshotLines());
    }

    private static final class ThrowingDiff implements LineDiff {
        private boolean fail;
        private int availabilityCalls;
        private int computations;

        @Override public boolean available() {
            availabilityCalls++;
            if (fail) throw new IllegalStateException("optional diff availability failure");
            return true;
        }

        @Override public List<LineEdit> compute(List<? extends CharSequence> before,
            List<? extends CharSequence> after, Algorithm algorithm, long maximumWork) {
            computations++;
            return List.of();
        }
    }
}
