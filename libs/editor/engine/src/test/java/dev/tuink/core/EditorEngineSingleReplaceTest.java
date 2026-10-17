package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EditorEngineSingleReplaceTest {
    @Test
    void multilineUnicodeReplacementKeepsMixedEolsAndOneCompactUndoStep() {
        List<String> original = List.of("before😀", "needle tail", "far", "");
        DocumentFileMetadata metadata = metadata(List.of("\r", "\n", "\r\n"));
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(original, metadata);
        EditorEngine engine = engine(model);
        engine.restoreSelection(new Position(1, 6), new Position(1, 0));
        engine.state().setViewport(new ViewportState(1, 0, 2, 2));
        EditorViewMemento before = engine.state().viewMemento();
        long epoch = model.contentEpoch();

        assertTrue(engine.replaceCurrentOrNext("needle", "β\r\n漢\r🙂"));

        List<String> changed = List.of("before😀", "β", "漢", "🙂 tail", "far", "");
        assertEquals(changed, model.snapshotLines());
        assertSame(original.getFirst(), model.getLine(0));
        assertSame(original.get(2), model.getLine(4));
        assertEquals(List.of("\r", "\r\n", "\r\n", "\n", "\r\n"),
            model.fileMetadata().lineSeparators());
        assertEquals(epoch, model.contentEpoch(), "Local replacement must retain incremental document identity");
        assertEquals(new Position(3, 2), engine.state().getCursor());
        assertEquals(new Position(1, 0), engine.state().getSelectionAnchor());
        DocumentFileMetadata edited = model.fileMetadata();
        assertEquals(changed, engine.captureRecoverySnapshot().lines());
        assertTrue(engine.state().isDirty());
        assertTrue(engine.undo());
        assertEquals(original, model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertEquals(before, engine.state().viewMemento());
        assertFalse(engine.state().isDirty());
        assertFalse(engine.undo());
        assertTrue(engine.redo());
        assertEquals(changed, model.snapshotLines());
        assertEquals(edited, model.fileMetadata());
    }

    @Test
    void deletingTheMatchDisambiguatesAdjacentCrAndLfAndUndoRestoresThem() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("a", "needle", "c"), metadata(List.of("\r", "\n")));
        EditorEngine engine = engine(model);
        assertTrue(engine.findNext("needle"));

        assertTrue(engine.replaceCurrentOrNext("needle", ""));

        assertEquals(List.of("a", "", "c"), model.snapshotLines());
        assertEquals(List.of("\r", "\r\n"), model.fileMetadata().lineSeparators());
        assertTrue(engine.undo());
        assertEquals(List.of("a", "needle", "c"), model.snapshotLines());
        assertEquals(List.of("\r", "\n"), model.fileMetadata().lineSeparators());
        assertTrue(engine.redo());
        assertEquals(List.of("\r", "\r\n"), model.fileMetadata().lineSeparators());
    }

    @Test
    void aSingleLineReplacementSharesUntouchedTextAndMetadata() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(java.util.Collections.nCopies(10_000, "needle"));
        EditorEngine engine = engine(model);
        engine.restoreSelection(new Position(5_000, 6), new Position(5_000, 0));
        DocumentFileMetadata metadata = model.fileMetadata();
        String untouched = model.getLine(4_999);

        assertTrue(engine.replaceCurrentOrNext("needle", "β"));

        assertSame(metadata, model.fileMetadata());
        assertSame(untouched, model.getLine(4_999));
        assertEquals("β", model.getLine(5_000));
        assertEquals("needle", model.getLine(4_999));
        assertTrue(engine.undo());
        assertEquals("needle", model.getLine(5_000));
        assertSame(metadata, model.fileMetadata());
    }

    @Test
    void aStagingAllocationFailurePreservesTextMetadataViewAndHistory() {
        assertStagingFailurePreservesTheDocument(false);
        assertStagingFailurePreservesTheDocument(true);
    }

    private void assertStagingFailurePreservesTheDocument(boolean multiline) {
        AtomicBoolean fail = new AtomicBoolean();
        TextModel model = new TextModel(multiline ? new FailingBuffer(fail) : new SetLineFailingBuffer(fail));
        model.replaceLines(List.of("needle", "second", "third"), metadata(List.of("\r", "\n")));
        EditorEngine engine = engine(model);
        engine.insertInput("X");
        engine.insertNewline();
        assertTrue(engine.undo());
        engine.restoreSelection(new Position(2, 4), new Position(2, 1));
        engine.state().setViewport(new ViewportState(1, 0, 2, 2));
        List<String> original = model.snapshotLines();
        DocumentFileMetadata metadata = model.fileMetadata();
        EditorViewMemento view = engine.state().viewMemento();
        long revision = model.revision();
        long epoch = model.contentEpoch();
        long navigation = engine.navigationRevision();
        fail.set(true);

        assertThrows(DocumentEditLimitException.class,
            () -> engine.replaceCurrentOrNext("needle", multiline ? "X\nY" : "β"));

        fail.set(false);
        assertEquals(original, model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertEquals(view, engine.state().viewMemento());
        assertEquals(revision, model.revision());
        assertEquals(epoch, model.contentEpoch());
        assertEquals(navigation, engine.navigationRevision());
        assertTrue(engine.state().isDirty());
        assertEquals(original, engine.captureRecoverySnapshot().lines());
        assertTrue(engine.redo(), "Refusal must preserve the existing redo branch");
        assertTrue(engine.undo());
        assertEquals(original, model.snapshotLines());
        assertTrue(engine.undo());
        assertFalse(engine.state().isDirty());
    }

    private static DocumentFileMetadata metadata(List<String> separators) {
        return new DocumentFileMetadata(StandardCharsets.UTF_16LE, BomKind.UTF_16_LE, "\r\n", separators);
    }

    private static EditorEngine engine(TextModel model) {
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.setAutoPairsEnabled(false);
        engine.setAutoIndentEnabled(false);
        engine.setAutoListEnabled(false);
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        return engine;
    }

    private static final class SetLineFailingBuffer extends SimpleLineBuffer {
        private final AtomicBoolean fail;

        private SetLineFailingBuffer(AtomicBoolean fail) {
            this.fail = fail;
        }

        @Override public TextBuffer createEmptySibling() {
            return new SetLineFailingBuffer(fail);
        }

        @Override public void setLine(int index, String text) {
            if (fail.get() && text.contains("β")) {
                throw new OutOfMemoryError("storage strategy allocation");
            }
            super.setLine(index, text);
        }
    }

    private static final class FailingBuffer extends SimpleLineBuffer {
        private final AtomicBoolean fail;

        private FailingBuffer(AtomicBoolean fail) {
            this.fail = fail;
        }

        @Override public TextBuffer createEmptySibling() {
            return new FailingBuffer(fail);
        }

        @Override public void insertLine(int index, String text) {
            if (fail.get() && lineCount() == 2) throw new OutOfMemoryError("staging allocation");
            super.insertLine(index, text);
        }
    }
}
