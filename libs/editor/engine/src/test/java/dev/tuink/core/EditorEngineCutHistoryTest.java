package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EditorEngineCutHistoryTest {
    @Test
    void cuttingASelectionRestoresMixedSeparatorsSelectionAndViewportAsOneUndoStep() {
        for (boolean reversed : List.of(false, true)) {
            Fixture fixture = mixedFixture();
            DocumentFileMetadata originalMetadata = fixture.model.fileMetadata();
            Position start = new Position(0, 1);
            Position end = new Position(2, 2);
            Position cursor = reversed ? start : end;
            Position anchor = reversed ? end : start;
            ViewportState viewport = new ViewportState(0, 1, 2, 3);
            fixture.engine.restoreSelection(cursor, anchor);
            fixture.state.setViewport(viewport);

            assertTrue(fixture.engine.cutSelection());
            assertEquals(List.of("oree", "last"), fixture.model.snapshotLines());
            assertEquals("ne\ntwo\nth", fixture.clipboard.text());
            assertEquals(List.of("\n"), fixture.model.fileMetadata().lineSeparators());
            assertFalse(fixture.state.hasSelection());
            assertTrue(fixture.state.isDirty());

            assertTrue(fixture.engine.undo());
            assertEquals(List.of("one", "two", "three", "last"), fixture.model.snapshotLines());
            assertEquals(originalMetadata, fixture.model.fileMetadata());
            assertEquals(cursor, fixture.state.getCursor());
            assertEquals(anchor, fixture.state.getSelectionAnchor());
            assertEquals(viewport, fixture.state.viewport());
            assertEquals("ne\ntwo\nth", fixture.clipboard.text());
            assertFalse(fixture.state.isDirty());
            assertFalse(fixture.engine.undo());

            assertTrue(fixture.engine.redo());
            assertEquals(List.of("oree", "last"), fixture.model.snapshotLines());
            assertEquals(List.of("\n"), fixture.model.fileMetadata().lineSeparators());
            assertEquals(new Position(0, 1), fixture.state.getCursor());
            assertFalse(fixture.state.hasSelection());
            assertTrue(fixture.state.isDirty());
        }
    }

    @Test
    void cuttingFirstMiddleAndLastLinesRestoresTheirOriginalSeparators() {
        for (int line : List.of(0, 1, 3)) {
            Fixture fixture = mixedFixture();
            List<String> original = fixture.model.snapshotLines();
            DocumentFileMetadata originalMetadata = fixture.model.fileMetadata();
            Position cursor = new Position(line, 1);
            fixture.engine.moveCursorTo(cursor);

            assertTrue(fixture.engine.cutCurrentLine());
            List<String> after = fixture.model.snapshotLines();
            DocumentFileMetadata afterMetadata = fixture.model.fileMetadata();
            assertEquals(original.get(line) + (line < 3 ? "\n" : ""), fixture.clipboard.text());
            assertEquals(line < 3 ? 3 : 4, fixture.model.lineCount());
            if (line == 3) {
                assertEquals("", fixture.model.getLine(3));
            }

            assertTrue(fixture.engine.undo());
            assertEquals(original, fixture.model.snapshotLines());
            assertEquals(originalMetadata, fixture.model.fileMetadata());
            assertEquals(cursor, fixture.state.getCursor());
            assertFalse(fixture.state.isDirty());
            assertFalse(fixture.engine.undo());
            assertTrue(fixture.engine.redo());
            assertEquals(after, fixture.model.snapshotLines());
            assertEquals(afterMetadata, fixture.model.fileMetadata());
            assertTrue(fixture.state.isDirty());
        }
    }

    @Test
    void repeatedCutsUndoAndRedoNeverSnapshotTheDocumentOrReadUntouchedLines() {
        for (boolean selection : List.of(false, true)) {
            AtomicInteger reads = new AtomicInteger();
            CountingBuffer source = new CountingBuffer(reads);
            source.setLine(0, "0123456789");
            for (int line = 1; line < 10_000; line++) {
                source.insertLine(line, "0123456789");
            }
            SnapshotCountingModel model = new SnapshotCountingModel(source);
            EditorState state = new EditorState();
            EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            reads.set(0);
            model.snapshots = 0;

            for (int step = 0; step < 100; step++) {
                if (selection) {
                    engine.restoreSelection(new Position(step, 5), new Position(step, 4));
                    assertTrue(engine.cutSelection());
                } else {
                    assertTrue(engine.cutCurrentLine());
                }
            }
            for (int step = 0; step < 100; step++) {
                assertTrue(engine.undo());
            }
            assertFalse(engine.undo());
            assertFalse(state.isDirty());
            for (int step = 0; step < 100; step++) {
                assertTrue(engine.redo());
            }

            assertEquals(0, model.snapshots, "Cut history must retain only the affected text and separators");
            assertTrue(reads.get() < 10_000, "Cut history must read only edited lines: " + reads);
            assertEquals(selection ? 10_000 : 9_900, model.lineCount());
            assertEquals(selection ? "012356789" : "0123456789", model.getLine(0));
            assertEquals("0123456789", model.getLine(model.lineCount() - 1));
        }
    }

    @Test
    void cuttingAnEmptyDocumentDoesNotCreateHistoryOrDirtyState() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        long revision = model.revision();

        assertFalse(engine.cutCurrentLine());

        assertEquals(revision, model.revision());
        assertFalse(state.isDirty());
        assertFalse(engine.undo());
    }

    private static Fixture mixedFixture() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("one", "two", "three", "last"), new DocumentFileMetadata(
            StandardCharsets.UTF_8, BomKind.UTF_8, "\n", List.of("\r\n", "\r", "\n")
        ));
        EditorState state = new EditorState();
        EditorClipboard clipboard = new EditorClipboard();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model), clipboard);
        return new Fixture(model, state, engine, clipboard);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine, EditorClipboard clipboard) {
    }

    private static final class SnapshotCountingModel extends TextModel {
        private int snapshots;

        private SnapshotCountingModel(TextBuffer buffer) {
            super(buffer);
        }

        @Override
        public List<String> snapshotLines() {
            snapshots++;
            return super.snapshotLines();
        }
    }

    private static final class CountingBuffer extends SimpleLineBuffer {
        private final AtomicInteger reads;

        private CountingBuffer(AtomicInteger reads) {
            this.reads = reads;
        }

        @Override
        public TextBuffer createEmptySibling() {
            return new CountingBuffer(reads);
        }

        @Override protected boolean supportsPreparedPublication() { return true; }

        @Override
        public String getLine(int line) {
            reads.incrementAndGet();
            return super.getLine(line);
        }
    }
}
