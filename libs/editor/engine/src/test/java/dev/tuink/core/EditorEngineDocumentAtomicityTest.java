package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EditorEngineDocumentAtomicityTest {
    @Test
    void replacingAllLinesBuildsIndependentStorageBeforeChangingTheModel() {
        StorageFailure failure = new StorageFailure();
        TextModel model = new TextModel(new ControlledBuffer(failure));
        model.replaceLines(List.of("first", "middle", "last"), metadata());
        List<String> before = model.snapshotLines();
        DocumentFileMetadata metadata = model.fileMetadata();
        long revision = model.revision();
        long epoch = model.contentEpoch();
        failure.writesRemaining = 2;

        assertThrows(OutOfMemoryError.class,
            () -> model.replaceLines(List.of("replacement", "more", "tail"), metadata));

        failure.writesRemaining = -1;
        assertEquals(before, model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertEquals(revision, model.revision());
        assertEquals(epoch, model.contentEpoch());
        model.replaceLines(List.of("replacement", "more", "tail"), metadata);
        assertEquals(List.of("replacement", "more", "tail"), model.snapshotLines());
        assertEquals(revision + 1, model.revision());
        assertEquals(epoch + 1, model.contentEpoch());
    }

    @Test
    void failingTheAfterSnapshotKeepsBothHistoryBranchesAndEveryView() {
        final class FailingSnapshotModel extends TextModel {
            int snapshotsRemaining = -1;
            FailingSnapshotModel() { super(new SimpleLineBuffer()); }
            @Override public List<String> snapshotLines() {
                if (snapshotsRemaining > 0 && --snapshotsRemaining == 0) {
                    throw new OutOfMemoryError("after snapshot allocation");
                }
                return super.snapshotLines();
            }
        }
        FailingSnapshotModel model = new FailingSnapshotModel();
        Fixture fixture = fixture(model);
        fixture.engine().insertChar('Z');
        fixture.engine().insertNewline();
        assertTrue(fixture.engine().undo());
        preserveSelections(fixture);
        Observed before = observe(fixture);
        model.snapshotsRemaining = 2;

        assertThrows(DocumentEditLimitException.class, () -> replaceWholeDocument(fixture.engine()));

        model.snapshotsRemaining = -1;
        assertEquals(before, observe(fixture));
        assertTrue(fixture.engine().redo());
        assertTrue(fixture.engine().undo());
        assertEquals(before.lines(), model.snapshotLines());
        assertTrue(fixture.engine().undo());
        assertFalse(fixture.engine().state().isDirty());
    }

    @Test
    void failedStoragePreparationDuringUndoAndRedoKeepsTheEntryAndTheOppositeBranch() {
        for (boolean snapshotEntry : List.of(false, true)) {
            for (boolean redoing : List.of(false, true)) {
                StorageFailure failure = new StorageFailure();
                Fixture fixture = fixture(new TextModel(new ControlledBuffer(failure)));
                EditorEngine engine = fixture.engine();
                engine.insertChar('Z');
                if (snapshotEntry) replaceWholeDocument(engine);
                else engine.insertInput("P\nQ");
                engine.insertNewline();
                assertTrue(engine.undo());
                if (redoing) assertTrue(engine.undo());
                preserveSelections(fixture);
                Observed before = observe(fixture);
                failure.writesRemaining = 2;

                assertThrows(DocumentEditLimitException.class, () -> {
                    if (redoing) engine.redo();
                    else engine.undo();
                }, "snapshot=" + snapshotEntry + ", redo=" + redoing);

                failure.writesRemaining = -1;
                assertEquals(before, observe(fixture));
                assertTrue(redoing ? engine.redo() : engine.undo());
                assertTrue(redoing ? engine.undo() : engine.redo());
                assertEquals(before.lines(), engine.model().snapshotLines());
                assertEquals(before.metadata(), engine.model().fileMetadata());
            }
        }
    }

    @Test
    void failureAfterPartOfAMultiOperationUndoHasBeenReplayedPublishesNothing() {
        final class FailingReplayModel extends TextModel {
            int replaysRemaining = -1;
            FailingReplayModel() { super(new SimpleLineBuffer()); }
            @Override void replayReplacement(ReplacementDelta delta) {
                if (replaysRemaining > 0 && --replaysRemaining == 0) {
                    throw new OutOfMemoryError("replay text allocation");
                }
                super.replayReplacement(delta);
            }
        }
        FailingReplayModel model = new FailingReplayModel();
        Fixture fixture = fixture(model);
        fixture.engine().setAutoPairsEnabled(true);
        fixture.engine().restoreSelection(new Position(1, 4), new Position(1, 1));
        fixture.engine().insertChar('(');
        preserveSelections(fixture);
        Observed before = observe(fixture);
        model.replaysRemaining = 2;

        assertThrows(DocumentEditLimitException.class, fixture.engine()::undo);

        assertEquals(0, model.replaysRemaining, "The failure must follow an already staged inverse");
        assertEquals(before, observe(fixture));
        model.replaysRemaining = -1;
        assertTrue(fixture.engine().undo());
        assertEquals(List.of("a😀b", "middle", "tail"), model.snapshotLines());
        assertFalse(fixture.engine().state().isDirty());
        assertFalse(fixture.engine().undo());
        assertTrue(fixture.engine().redo());
        assertEquals(before.lines(), model.snapshotLines());
    }

    @Test
    void failureAfterPartOfAMultiOperationRedoHasBeenReplayedPublishesNothing() {
        final class FailingReplayModel extends TextModel {
            int replaysRemaining = -1;
            long revisionBeforeAttempt;
            FailingReplayModel() { super(new SimpleLineBuffer()); }
            @Override void replayReplacement(ReplacementDelta delta) {
                if (replaysRemaining > 0 && --replaysRemaining == 0) {
                    assertEquals(List.of("Za😀b", "midd)le", "tail"), snapshotLines(),
                        "The closing parenthesis must already be staged before the opening one fails");
                    assertEquals(revisionBeforeAttempt + 1, revision());
                    assertEquals(metadata(), fileMetadata());
                    assertEquals(new Position(1, 1), delta.operation().start());
                    assertEquals("(", delta.operation().insertedText());
                    throw new OutOfMemoryError("second redo operation allocation");
                }
                super.replayReplacement(delta);
            }
        }
        FailingReplayModel model = new FailingReplayModel();
        Fixture fixture = fixture(model);
        EditorEngine engine = fixture.engine();
        engine.insertChar('Z');
        engine.setAutoPairsEnabled(true);
        engine.restoreSelection(new Position(1, 4), new Position(1, 1));
        engine.insertChar('(');
        List<String> wrappedLines = List.of("Za😀b", "m(idd)le", "tail");
        assertEquals(wrappedLines, model.snapshotLines());
        assertTrue(engine.undo());
        preserveSelections(fixture);
        Observed before = observe(fixture);
        EditorDocumentHistory history = engine.document().history;
        var undo = history.undoStack;
        var redo = history.redoStack;
        EditorEngine.HistoryEntry entry = redo.peek();
        assertEquals(1, undo.size());
        assertEquals(1, redo.size());
        model.revisionBeforeAttempt = before.revision();
        model.replaysRemaining = 2;

        assertThrows(DocumentEditLimitException.class, engine::redo);

        assertEquals(0, model.replaysRemaining, "Failure must occur at the second redo operation");
        assertEquals(before, observe(fixture));
        assertSame(undo, history.undoStack);
        assertSame(redo, history.redoStack);
        assertSame(entry, history.redoStack.peek());
        model.replaysRemaining = -1;
        assertTrue(engine.redo());
        assertEquals(wrappedLines, model.snapshotLines());
        assertEquals(before.metadata(), model.fileMetadata());
        assertFalse(engine.redo());
        assertTrue(engine.undo());
        assertEquals(before.lines(), model.snapshotLines());
        assertEquals(before.metadata(), model.fileMetadata());
        assertEquals(before.stateId(), history.currentDocumentStateId);
        assertEquals(before.nextStateId(), history.nextDocumentStateId);
        assertEquals(before.savedStateId(), history.savedDocumentStateId);
        assertEquals(before.undo(), List.copyOf(history.undoStack));
        assertEquals(before.redo(), List.copyOf(history.redoStack));
        assertTrue(engine.undo());
        assertEquals(List.of("a😀b", "middle", "tail"), model.snapshotLines());
        assertFalse(engine.state().isDirty());
    }

    @Test
    void foreignViewPreparationFailureCannotPublishBulkPasteCommentsOrHistory() {
        record Attempt(String name, Consumer<EditorEngine> command) {}
        for (Attempt attempt : List.of(
            new Attempt("bulk", EditorEngineDocumentAtomicityTest::replaceWholeDocument),
            new Attempt("paste", engine -> engine.pasteText("replacement\nnext")),
            new Attempt("comments", EditorEngine::toggleLineComment),
            new Attempt("undo", EditorEngine::undo),
            new Attempt("redo", EditorEngine::redo)
        )) {
            Fixture fixture = fixture(new TextModel(new SimpleLineBuffer()));
            EditorEngine engine = fixture.engine();
            engine.state().setCurrentResource(new MemoryDocumentStorage().resource(Path.of("example.java").toString()));
            engine.insertChar('Z');
            engine.insertNewline();
            assertTrue(engine.undo());
            preserveSelections(fixture);
            Observed before = observe(fixture);
            fixture.foreign().fail = true;

            assertThrows(DocumentEditLimitException.class, () -> attempt.command().accept(engine), attempt.name());

            fixture.foreign().fail = false;
            assertEquals(before, observe(fixture), attempt.name());
            assertTrue(engine.redo(), attempt.name());
            assertTrue(engine.undo(), attempt.name());
            assertEquals(before.lines(), engine.model().snapshotLines(), attempt.name());
        }
    }

    private static Fixture fixture(TextModel model) {
        model.replaceLines(List.of("a😀b", "middle", "tail"), metadata());
        EditorDocument document = new EditorDocument(model);
        EditorClipboard clipboard = new EditorClipboard();
        EditorEngine engine = document.attach(LogicalEditorTextLayout::new, clipboard).engine();
        engine.setAutoPairsEnabled(false);
        engine.setAutoIndentEnabled(false);
        engine.setAutoListEnabled(false);
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        FailingViewState foreign = new FailingViewState(document.state);
        new EditorEngine(document, foreign, new LogicalEditorTextLayout(model), clipboard);
        return new Fixture(engine, foreign);
    }

    private static void preserveSelections(Fixture fixture) {
        EditorEngine engine = fixture.engine();
        int last = engine.model().lineCount() - 1;
        engine.restoreSelection(new Position(last, 2), new Position(0, 1));
        engine.state().setViewport(new ViewportState(1, 0, 2, 2));
        fixture.foreign().setCursor(new Position(last, 4));
        fixture.foreign().setSelectionAnchor(new Position(0, 1));
    }

    private static void replaceWholeDocument(EditorEngine engine) {
        int last = engine.model().lineCount() - 1;
        engine.applyTextReplacements(List.of(new TextReplacement(new Position(0, 0),
            new Position(last, engine.model().getLine(last).length()), "replacement\nmore\ntail")));
    }

    private static Observed observe(Fixture fixture) {
        EditorEngine engine = fixture.engine();
        EditorDocumentHistory history = engine.document().history;
        return new Observed(engine.model().snapshotLines(), engine.model().fileMetadata(),
            engine.state().viewMemento(), fixture.foreign().viewMemento(), engine.model().revision(),
            engine.model().contentEpoch(), engine.navigationRevision(), fixture.foreign().navigationRevision(),
            engine.recoveryRevision(), engine.state().isDirty(), List.copyOf(history.undoStack),
            List.copyOf(history.redoStack), history.currentDocumentStateId, history.nextDocumentStateId,
            history.savedDocumentStateId);
    }

    private record Fixture(EditorEngine engine, FailingViewState foreign) {}
    private record Observed(List<String> lines, DocumentFileMetadata metadata, EditorViewMemento view,
        EditorViewMemento foreignView, long revision, long epoch, long navigation, long foreignNavigation,
        long recovery, boolean dirty, List<EditorEngine.HistoryEntry> undo, List<EditorEngine.HistoryEntry> redo,
        long stateId, long nextStateId, long savedStateId) {}

    private static DocumentFileMetadata metadata() {
        return new DocumentFileMetadata(StandardCharsets.UTF_16LE, BomKind.UTF_16_LE, "\r\n", List.of("\r", "\n"));
    }

    private static final class FailingViewState extends EditorState {
        boolean fail;
        FailingViewState(EditorDocumentState document) { super(document, new EditorViewState()); }
        @Override public Position getCursor() {
            if (fail) throw new OutOfMemoryError("foreign view clamp preparation");
            return super.getCursor();
        }
    }

    private static final class StorageFailure {
        int writesRemaining = -1;
        void beforeWrite() {
            if (writesRemaining > 0 && --writesRemaining == 0) {
                throw new OutOfMemoryError("storage strategy allocation");
            }
        }
    }

    private static final class ControlledBuffer extends SimpleLineBuffer {
        private final StorageFailure failure;
        ControlledBuffer(StorageFailure failure) { this.failure = failure; }
        @Override public TextBuffer createEmptySibling() { return new ControlledBuffer(failure); }
        @Override public void setLine(int line, String text) {
            failure.beforeWrite();
            super.setLine(line, text);
        }
        @Override public void insertLine(int line, String text) {
            failure.beforeWrite();
            super.insertLine(line, text);
        }
    }
}
