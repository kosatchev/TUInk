package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.diff.LineDiff;
import dev.tuink.editor.diff.LineEdit;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.ViewId;
import dev.tuink.verification.MemoryDocumentStorage;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
        engine.state().setCurrentResource(new MemoryDocumentStorage().resource("Document.java"));
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

    @Test
    void failedMergedTypingResultPreservesTheEntryPayloadAndTheNextTypingGroup() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        FaultResultEngine engine = new FaultResultEngine(model, new EditorState());
        executeInput(engine, "A");
        EditorDocumentHistory history = engine.document().history;
        EditorEngine.HistoryEntry entry = history.undoStack.peek();
        HistoryPayload payload = historyPayload(entry);
        assertEquals(1, payload.operations().size());
        assertNull(engine.state().getSelectionAnchor(), "The refused input must have no selection");
        // Keep this setup eligible for coalescing even if the test is descheduled.
        history.lastEditAtNanos = System.nanoTime() + TimeUnit.DAYS.toNanos(1);
        long coalescingAt = history.lastEditAtNanos;
        EditorEngine.EditKind coalescingKind = history.lastEditKind;
        Position coalescingCursor = history.lastEditCursorAfter;
        long coalescingView = history.lastEditViewId;
        long revision = model.revision();
        long epoch = model.contentEpoch();
        long recovery = engine.recoveryRevision();
        long navigation = engine.navigationRevision();
        long currentState = history.currentDocumentStateId;
        long nextState = history.nextDocumentStateId;
        EditorViewMemento view = engine.state().viewMemento();
        engine.expectedMergedEntry = entry;
        engine.failResult = true;

        try {
            assertThrows(DocumentEditLimitException.class, () -> executeInput(engine, "B"));
        } finally {
            engine.failResult = false;
        }

        assertEquals(1, engine.failedResults, "The failure must follow preparation of a real history merge");
        assertEquals("A", model.getLine(0));
        assertEquals(revision, model.revision());
        assertEquals(epoch, model.contentEpoch());
        assertEquals(recovery, engine.recoveryRevision());
        assertEquals(navigation, engine.navigationRevision());
        assertEquals(view, engine.state().viewMemento());
        assertEquals(currentState, history.currentDocumentStateId);
        assertEquals(nextState, history.nextDocumentStateId);
        assertEquals(coalescingAt, history.lastEditAtNanos);
        assertEquals(coalescingKind, history.lastEditKind);
        assertEquals(coalescingCursor, history.lastEditCursorAfter);
        assertEquals(coalescingView, history.lastEditViewId);
        assertEquals(1, history.undoStack.size());
        assertSame(entry, history.undoStack.peek());
        assertEquals(payload, historyPayload(entry), "Failed input must not mutate the existing entry payload");
        assertTrue(history.redoStack.isEmpty());

        executeInput(engine, "C");

        assertEquals("AC", model.getLine(0));
        assertEquals(1, history.undoStack.size(), "The next successful input still belongs to the original group");
        assertSame(entry, history.undoStack.peek());
        assertEquals(2, historyPayload(entry).operations().size());
        assertTrue(engine.undo());
        assertEquals("", model.getLine(0));
        assertFalse(engine.state().isDirty());
        assertFalse(engine.undo());
        assertTrue(engine.redo());
        assertEquals("AC", model.getLine(0), "Redo includes only successful input");
        assertFalse(engine.redo());
    }

    private static DocumentCommandResult.Applied executeInput(EditorEngine engine, String text) {
        return engine.executeCommand(new DocumentCommandTarget(new DocumentId("publication-boundary"),
            new ViewId(engine.state().viewId()), engine.model().revision(), engine.recoveryRevision(),
            engine.navigationRevision()), new DocumentCommand.Input(text));
    }

    private static HistoryPayload historyPayload(EditorEngine.HistoryEntry entry) {
        return new HistoryPayload(List.copyOf((List<?>) field(entry.getClass(), entry, "ops")),
            field(entry.getClass(), entry, "afterView"),
            (Long) field(entry.getClass(), entry, "afterStateId"));
    }

    private static Object field(Class<?> declaringClass, Object target, String name) {
        try {
            Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private record HistoryPayload(List<?> operations, Object afterView, long afterStateId) {}

    private static final class FaultResultEngine extends EditorEngine {
        private boolean failResult;
        private int failedResults;
        private EditorEngine.HistoryEntry expectedMergedEntry;

        private FaultResultEngine(TextModel model, EditorState state) {
            super(model, state, new LogicalEditorTextLayout(model));
        }

        @Override protected DocumentCommandResult.Applied prepareCommandResult(DocumentCommandTarget target,
            long revision, boolean textChanged, boolean viewChanged, boolean performed, int affectedCount,
            DocumentCommandResult.CommentAction commentAction) {
            if (failResult) {
                Object prepared = field(EditorEngine.class, this, "keyboardHistory");
                assertSame(expectedMergedEntry, field(prepared.getClass(), prepared, "merged"),
                    "The failure must exercise preparation of an append to the existing entry");
                assertEquals("AB", model().getLine(0), "The refused input is already staged");
                failedResults++;
                throw new OutOfMemoryError("mandatory result for merged typing");
            }
            return super.prepareCommandResult(target, revision, textChanged, viewChanged, performed,
                affectedCount, commentAction);
        }
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
