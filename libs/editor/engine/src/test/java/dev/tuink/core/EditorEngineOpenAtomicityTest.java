package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.ResourceVersion;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EditorEngineOpenAtomicityTest {
    @Test
    void localEditConstructionFailurePreservesTheOwnerAndAllowsOpeningAgain() throws Exception {
        MemoryDocumentStorage storage = new MemoryDocumentStorage();
        byte[] originalBytes = "\uFEFFalpha\r\nbeta\nthird\rfourth".getBytes(StandardCharsets.UTF_8);
        byte[] replacementBytes = "\uFEFF😀new\r\nshort".getBytes(StandardCharsets.UTF_16LE);
        storage.put("original.txt", originalBytes);
        storage.put("replacement.txt", replacementBytes);
        AtomicBoolean failLineCount = new AtomicBoolean();
        TextModel model = new TextModel(new ConstructionFailureBuffer(failLineCount));
        EditorClipboard clipboard = new EditorClipboard();
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model), clipboard);
        engine.openPrepared(storage.load(storage.resource("original.txt"), DocumentOpenMode.DETECT, false));
        engine.insertChar('X');
        engine.insertNewline();
        List<String> redoLines = model.snapshotLines();
        assertTrue(engine.undo());
        engine.restoreSelection(new Position(1, 3), new Position(1, 1));
        engine.state().setViewport(new ViewportState(1, 1, 2, 3));
        TabSession other = engine.document().attach(LogicalEditorTextLayout::new, clipboard);
        other.engine().restoreSelection(new Position(3, 4), new Position(2, 3));
        other.state().setViewport(new ViewportState(2, 1, 7, 8));
        clipboard.setText("workspace clipboard");
        DocumentLoad replacement = storage.load(storage.resource("replacement.txt"), DocumentOpenMode.DETECT, false);
        Observed before = observe(engine, other);
        EditorDocumentHistory history = engine.document().history;
        var undo = history.undoStack;
        var redo = history.redoStack;
        var openBaseline = engine.document().baselines.openLines;
        var savedBaseline = engine.document().baselines.savedLines;
        long baselineGeneration = engine.document().baselines.generation;
        int calls = storage.calls();
        failLineCount.set(true);

        try {
            assertInstanceOf(DocumentEditLimitException.class,
                assertThrows(Throwable.class, () -> engine.openPrepared(replacement)));
            assertFalse(failLineCount.get(), "Failure must occur while constructing the local edit buffer");
        } finally {
            failLineCount.set(false);
        }

        assertEquals(before, observe(engine, other));
        assertSame(undo, history.undoStack);
        assertSame(redo, history.redoStack);
        assertSame(openBaseline, engine.document().baselines.openLines);
        assertSame(savedBaseline, engine.document().baselines.savedLines);
        assertEquals(baselineGeneration, engine.document().baselines.generation);
        assertEquals("workspace clipboard", clipboard.text());
        assertEquals(calls, storage.calls(), "Prepared publication must not reopen either resource");
        assertArrayEquals(originalBytes, storage.bytes("original.txt"));
        assertArrayEquals(replacementBytes, storage.bytes("replacement.txt"));
        assertTrue(engine.redo());
        assertEquals(redoLines, model.snapshotLines());
        assertTrue(engine.undo());
        assertEquals(before.lines(), model.snapshotLines());
        assertTrue(engine.undo());
        assertFalse(engine.state().isDirty(), "The original savepoint must remain reachable");
        assertTrue(engine.redo());
        assertEquals(before.lines(), model.snapshotLines());

        engine.openPrepared(replacement);

        assertEquals(replacement.lines(), model.snapshotLines());
        assertEquals(replacement.metadata(), model.fileMetadata());
        assertEquals(replacement.baseline(), model.fileBaseline());
        assertSame(replacement.resource(), engine.state().getCurrentResource());
        assertEquals(replacement.disposition(), engine.state().getSaveDisposition());
        assertEquals(new Position(0, 0), engine.state().getCursor());
        assertEquals(new Position(1, 4), other.state().getCursor());
        assertEquals(new Position(1, 3), other.state().getSelectionAnchor());
        assertFalse(engine.state().isDirty());
        assertFalse(engine.undo());
        assertFalse(engine.redo());
        assertEquals(calls, storage.calls());
    }

    private static Observed observe(EditorEngine engine, TabSession other) {
        TextModel model = engine.model();
        EditorState state = engine.state();
        EditorDocumentHistory history = engine.document().history;
        return new Observed(model.snapshotLines(), model.fileMetadata(), model.fileBaseline(),
            state.getCurrentResource(), state.getSaveDisposition(), state.getFileEncoding(), state.getLineSeparator(),
            state.isDirty(), state.viewMemento(), other.state().viewMemento(), model.revision(), model.contentEpoch(),
            engine.recoveryRevision(), engine.navigationRevision(), other.engine().navigationRevision(),
            List.copyOf(history.undoStack), List.copyOf(history.redoStack), history.currentDocumentStateId,
            history.nextDocumentStateId, history.savedDocumentStateId);
    }

    private record Observed(List<String> lines, DocumentFileMetadata metadata, ResourceVersion baseline,
        DocumentResource resource, SaveDisposition disposition, Charset encoding, String separator, boolean dirty,
        EditorViewMemento view, EditorViewMemento foreignView, long revision, long epoch, long recovery,
        long navigation, long foreignNavigation, List<EditorEngine.HistoryEntry> undo,
        List<EditorEngine.HistoryEntry> redo, long stateId, long nextStateId, long savedStateId) {}

    private static final class ConstructionFailureBuffer extends SimpleLineBuffer {
        private final AtomicBoolean failLineCount;

        private ConstructionFailureBuffer(AtomicBoolean failLineCount) {
            this.failLineCount = failLineCount;
        }

        @Override public TextBuffer createEmptySibling() { return new ConstructionFailureBuffer(failLineCount); }

        @Override public int lineCount() {
            if (failLineCount.getAndSet(false)) throw new OutOfMemoryError("local edit buffer construction");
            return super.lineCount();
        }
    }
}
