package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EditorEngineKeyboardAtomicityTest {
    @Test
    void failedStorageReservationKeepsTextMetadataViewsClipboardAndBothHistoryBranches() {
        record Attempt(String name, Consumer<EditorEngine> command) {}
        for (Attempt attempt : List.of(
            new Attempt("typing", engine -> engine.insertChar('X')),
            new Attempt("Unicode input", engine -> engine.insertInput("😀")),
            new Attempt("Tab", EditorEngine::insertTab),
            new Attempt("Enter", EditorEngine::insertNewline),
            new Attempt("Backspace", EditorEngine::backspace),
            new Attempt("Delete", EditorEngine::deleteForward),
            new Attempt("cut selection", EditorEngine::cutSelection),
            new Attempt("cut line", EditorEngine::cutCurrentLine),
            new Attempt("wrap selection", engine -> engine.insertChar('('))
        )) {
            AtomicBoolean fail = new AtomicBoolean();
            TextModel model = new TextModel(new ReservationBuffer(fail, new AtomicInteger()));
            model.replaceLines(List.of("a😀b", "middle", "tail"), new DocumentFileMetadata(
                StandardCharsets.UTF_8, BomKind.UTF_8, "\n", List.of("\r\n", "\r")));
            EditorClipboard clipboard = new EditorClipboard();
            TabSession tab = new EditorDocument(model).attach(LogicalEditorTextLayout::new, clipboard);
            EditorEngine engine = tab.engine();
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            engine.insertChar('Z');
            engine.insertNewline();
            List<String> redoLines = model.snapshotLines();
            assertTrue(engine.undo());
            TabSession other = engine.document().attach(LogicalEditorTextLayout::new, clipboard);
            other.engine().moveDocumentEnd(false);
            EditorViewMemento otherBefore = other.state().viewMemento();
            engine.restoreSelection(new Position(2, 2), new Position(0, 1));
            tab.state().setViewport(new ViewportState(1, 0, 2, 2));
            clipboard.setText("keep clipboard");
            List<String> before = model.snapshotLines();
            DocumentFileMetadata metadata = model.fileMetadata();
            EditorViewMemento view = tab.state().viewMemento();
            long revision = model.revision();
            long epoch = model.contentEpoch();
            long navigation = engine.navigationRevision();
            long recovery = engine.recoveryRevision();
            boolean dirty = tab.state().isDirty();

            fail.set(true);
            assertThrows(DocumentEditLimitException.class, () -> attempt.command().accept(engine), attempt.name());
            fail.set(false);

            assertEquals(before, model.snapshotLines(), attempt.name());
            assertEquals(metadata, model.fileMetadata(), attempt.name());
            assertEquals(view, tab.state().viewMemento(), attempt.name());
            assertEquals(otherBefore, other.state().viewMemento(), attempt.name());
            assertEquals("keep clipboard", clipboard.text(), attempt.name());
            assertEquals(revision, model.revision(), attempt.name());
            assertEquals(epoch, model.contentEpoch(), attempt.name());
            assertEquals(navigation, engine.navigationRevision(), attempt.name());
            assertEquals(recovery, engine.recoveryRevision(), attempt.name());
            assertEquals(dirty, tab.state().isDirty(), attempt.name());
            assertTrue(engine.redo(), attempt.name());
            assertEquals(redoLines, model.snapshotLines(), attempt.name());
            assertTrue(engine.undo(), attempt.name());
            assertEquals(before, model.snapshotLines(), attempt.name());
            assertTrue(engine.undo(), attempt.name());
            assertEquals(List.of("a😀b", "middle", "tail"), model.snapshotLines(), attempt.name());
            assertFalse(engine.undo(), attempt.name());
        }
    }

    @Test
    void failingUndoCaptureForKeywordOutdentCannotCommitTheTypedCharacter() {
        final class FailingCaptureModel extends TextModel {
            int captures;
            boolean fail;
            FailingCaptureModel() { super(new SimpleLineBuffer()); }
            @Override public String getTextRange(Position start, Position end) {
                if (fail && ++captures == 2) throw new OutOfMemoryError("undo text allocation");
                return super.getTextRange(start, end);
            }
        }
        FailingCaptureModel model = new FailingCaptureModel();
        List<String> original = List.of("if ready:", "    run()", "    else");
        model.replaceLines(original);
        EditorState state = new EditorState();
        state.setCurrentFile(Path.of("main.py"));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.moveDocumentEnd(false);
        long revision = model.revision();
        long navigation = engine.navigationRevision();
        model.fail = true;

        assertThrows(DocumentEditLimitException.class, () -> engine.insertChar(':'));

        assertEquals(2, model.captures, "Failure must occur after insertion, while preparing the outdent");
        assertEquals(original, model.snapshotLines());
        assertEquals(revision, model.revision());
        assertEquals(navigation, engine.navigationRevision());
        assertFalse(state.isDirty());
        assertFalse(engine.undo());
        model.fail = false;
        engine.insertChar(':');
        assertEquals("else:", model.getLine(2));
        assertTrue(engine.undo());
        assertEquals(original, model.snapshotLines());
        assertFalse(engine.undo());
    }

    @Test
    void enterAndDeleteAtTheEndDoNotReadOrCopyUntouchedLines() {
        AtomicInteger reads = new AtomicInteger();
        ReservationBuffer source = new ReservationBuffer(new AtomicBoolean(), reads);
        for (int line = 1; line < 100_000; line++) source.insertLine(line, "line");
        TextModel model = new TextModel(source);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        engine.setAutoIndentEnabled(false);
        engine.setAutoListEnabled(false);
        engine.moveDocumentEnd(false);
        reads.set(0);

        engine.insertNewline();
        engine.backspace();

        assertEquals(100_000, model.lineCount());
        assertTrue(reads.get() < 200, "Local structural edits must retain untouched line references: " + reads);
        assertTrue(engine.undo());
        assertEquals(100_001, model.lineCount());
        assertTrue(engine.undo());
        assertEquals(100_000, model.lineCount());
    }

    @Test
    void subclassMutationHooksRemainPartOfItsStorageStrategy() {
        final class CachedBuffer extends SimpleLineBuffer {
            private final Map<Integer, String> cache = new HashMap<>();
            @Override public TextBuffer createEmptySibling() { return new CachedBuffer(); }
            @Override public String getLine(int line) {
                return cache.computeIfAbsent(line, super::getLine);
            }
            @Override public void setLine(int line, String text) {
                super.setLine(line, text);
                cache.put(line, text);
            }
        }
        TextModel model = new TextModel(new CachedBuffer());
        model.replaceLines(List.of("original"));
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));

        engine.insertChar('X');

        assertEquals("Xoriginal", model.getLine(0));
        assertTrue(engine.undo());
        assertEquals("original", model.getLine(0));
        assertTrue(engine.redo());
        assertEquals("Xoriginal", model.getLine(0));
    }

    private static final class ReservationBuffer extends SimpleLineBuffer {
        private final AtomicBoolean fail;
        private final AtomicInteger reads;
        ReservationBuffer(AtomicBoolean fail, AtomicInteger reads) {
            this.fail = fail;
            this.reads = reads;
        }
        @Override public TextBuffer createEmptySibling() { return new ReservationBuffer(fail, reads); }
        @Override public String getLine(int line) {
            reads.incrementAndGet();
            return super.getLine(line);
        }
        @Override protected boolean supportsPreparedPublication() { return true; }
        @Override protected void reserveLines(int required) {
            if (fail.get()) throw new OutOfMemoryError("backing storage reservation");
            super.reserveLines(required);
        }
    }
}
