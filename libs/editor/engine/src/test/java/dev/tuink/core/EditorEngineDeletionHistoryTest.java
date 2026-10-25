package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEngineDeletionHistoryTest {
    @TempDir
    Path tempDir;

    @Test
    void deletionUndoAndRedoDoNotSnapshotUntouchedDocumentLines() {
        for (boolean backward : List.of(true, false)) {
            AtomicInteger reads = new AtomicInteger();
            CountingBuffer source = new CountingBuffer(reads);
            source.setLine(0, "0123456789");
            for (int line = 1; line < 10_000; line++) {
                source.insertLine(line, "0123456789");
            }
            TextModel model = new TextModel(source);
            Fixture fixture = fixture(model);
            reads.set(0);

            for (int line = 0; line < 100; line++) {
                fixture.engine.moveCursorTo(new Position(line, 5));
                delete(fixture.engine, backward);
            }
            for (int step = 0; step < 100; step++) {
                assertTrue(fixture.engine.undo());
            }
            for (int step = 0; step < 100; step++) {
                assertTrue(fixture.engine.redo());
            }

            assertTrue(reads.get() < 10_000, "Deletion history must read only edited lines: " + reads);
            assertEquals(backward ? "012356789" : "012346789", model.getLine(0));
            assertEquals("0123456789", model.getLine(9_999));
        }
    }

    @Test
    void backspaceCoalescesWholeGraphemesAndRestoresTheCursor() {
        String text = "ae\u0301👨‍👩‍👧‍👦z";
        Fixture fixture = fixture(List.of(text));
        Position before = new Position(0, text.length() - 1);
        fixture.engine.moveCursorTo(before);

        fixture.engine.backspace();
        fixture.engine.backspace();
        assertEquals("az", fixture.model.getLine(0));
        assertEquals(new Position(0, 1), fixture.state.getCursor());

        assertTrue(fixture.engine.undo());
        assertEquals(text, fixture.model.getLine(0));
        assertEquals(before, fixture.state.getCursor());
        assertFalse(fixture.engine.undo());
        assertTrue(fixture.engine.redo());
        assertEquals("az", fixture.model.getLine(0));
        assertEquals(new Position(0, 1), fixture.state.getCursor());
    }

    @Test
    void forwardDeleteCoalescesWholeGraphemes() {
        Fixture fixture = fixture(List.of("ae\u0301😀z"));
        fixture.engine.moveCursorTo(new Position(0, 1));

        fixture.engine.deleteForward();
        fixture.engine.deleteForward();
        assertEquals("az", fixture.model.getLine(0));
        assertTrue(fixture.engine.undo());
        assertEquals("ae\u0301😀z", fixture.model.getLine(0));
        assertFalse(fixture.engine.undo());
        assertTrue(fixture.engine.redo());
        assertEquals("az", fixture.model.getLine(0));
    }

    @Test
    void backspaceDeletesAnAutoPairTogetherWithItsCombiningMark() {
        Fixture fixture = fixture(List.of("a()\u0301z"));
        fixture.engine.moveCursorTo(new Position(0, 2));

        fixture.engine.backspace();
        assertEquals("az", fixture.model.getLine(0));
        assertTrue(fixture.engine.undo());
        assertEquals("a()\u0301z", fixture.model.getLine(0));
        assertEquals(new Position(0, 2), fixture.state.getCursor());
        assertTrue(fixture.engine.redo());
        assertEquals("az", fixture.model.getLine(0));
    }

    @Test
    void backspaceDeletesOneIndentUnitAndRestoresTheViewport() {
        Fixture fixture = fixture(List.of("        x"));
        fixture.engine.setTabInsertMode(TabInsertMode.ALWAYS_SPACES_4);
        fixture.engine.moveCursorTo(new Position(0, 8));
        ViewportState viewport = new ViewportState(0, 2, 3, 5);
        fixture.state.setViewport(viewport);

        fixture.engine.backspace();
        assertEquals("    x", fixture.model.getLine(0));
        assertEquals(new Position(0, 4), fixture.state.getCursor());
        assertTrue(fixture.engine.undo());
        assertEquals("        x", fixture.model.getLine(0));
        assertEquals(new Position(0, 8), fixture.state.getCursor());
        assertEquals(viewport, fixture.state.viewport());
        assertTrue(fixture.engine.redo());
        assertEquals("    x", fixture.model.getLine(0));
    }

    @Test
    void selectionDeletionRestoresExactSelectionAndMixedSeparatorsInBothDirections() {
        for (boolean backward : List.of(true, false)) {
            Fixture fixture = mixedFixture(List.of("one", "two", "three"), List.of("\r\n", "\r"));
            DocumentFileMetadata beforeMetadata = fixture.model.fileMetadata();
            Position cursor = new Position(0, 1);
            Position anchor = new Position(2, 1);
            fixture.engine.restoreSelection(cursor, anchor);

            delete(fixture.engine, backward);
            assertEquals(List.of("ohree"), fixture.model.snapshotLines());
            assertFalse(fixture.state.hasSelection());
            assertTrue(fixture.engine.undo());
            assertEquals(List.of("one", "two", "three"), fixture.model.snapshotLines());
            assertEquals(beforeMetadata, fixture.model.fileMetadata());
            assertEquals(cursor, fixture.state.getCursor());
            assertEquals(anchor, fixture.state.getSelectionAnchor());
            assertTrue(fixture.engine.redo());
            assertEquals(List.of("ohree"), fixture.model.snapshotLines());
        }
    }

    @Test
    void coalescedNewlineJoinsRestoreOnlyTheirOriginalMixedSeparators() {
        for (boolean backward : List.of(true, false)) {
            Fixture fixture = mixedFixture(List.of("a", "", "", "d"), List.of("\r\n", "\r", "\n"));
            DocumentFileMetadata beforeMetadata = fixture.model.fileMetadata();
            fixture.engine.moveCursorTo(backward ? new Position(3, 0) : new Position(0, 1));

            for (int step = 0; step < 3; step++) {
                delete(fixture.engine, backward);
            }
            assertEquals(List.of("ad"), fixture.model.snapshotLines());
            assertEquals(List.of(), fixture.model.fileMetadata().lineSeparators());
            assertTrue(fixture.engine.undo());
            assertEquals(List.of("a", "", "", "d"), fixture.model.snapshotLines());
            assertEquals(beforeMetadata, fixture.model.fileMetadata());
            assertFalse(fixture.engine.undo());
            assertTrue(fixture.engine.redo());
            assertEquals(List.of("ad"), fixture.model.snapshotLines());
        }
    }

    @Test
    void replacementOperationRestoresMixedSeparatorsWithItsDeletedText() {
        Fixture fixture = mixedFixture(List.of("a", "b", "c", "d"), List.of("\r\n", "\r", "\n"));
        DocumentFileMetadata beforeMetadata = fixture.model.fileMetadata();
        fixture.engine.restoreSelection(new Position(2, 0), new Position(0, 1));

        fixture.engine.insertInput("\r\nx\ry");
        assertEquals(List.of("a", "x", "yc", "d"), fixture.model.snapshotLines());
        assertEquals(List.of("\n", "\n", "\n"), fixture.model.fileMetadata().lineSeparators());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("a", "b", "c", "d"), fixture.model.snapshotLines());
        assertEquals(beforeMetadata, fixture.model.fileMetadata());
        assertTrue(fixture.engine.redo());
        assertEquals(List.of("a", "x", "yc", "d"), fixture.model.snapshotLines());
        assertEquals(List.of("\n", "\n", "\n"), fixture.model.fileMetadata().lineSeparators());
    }

    @Test
    void deletionSavepointAndFreshEditKeepIndependentUndoSteps() throws IOException {
        Fixture fixture = fixture(List.of("abc"));
        fixture.engine.moveCursorTo(new Position(0, 3));
        fixture.engine.backspace();
        fixture.engine.saveAs(JvmDocumentStorage.resource(tempDir.resolve("saved.txt")));
        assertFalse(fixture.state.isDirty());

        fixture.engine.backspace();
        assertEquals("a", fixture.model.getLine(0));
        assertTrue(fixture.state.isDirty());
        assertTrue(fixture.engine.undo());
        assertEquals("ab", fixture.model.getLine(0));
        assertFalse(fixture.state.isDirty());
        assertTrue(fixture.engine.undo());
        assertEquals("abc", fixture.model.getLine(0));
        assertTrue(fixture.state.isDirty());
        assertTrue(fixture.engine.redo());
        assertEquals("ab", fixture.model.getLine(0));
        assertFalse(fixture.state.isDirty());
    }

    @Test
    void boundaryDeletionsDoNotCreateHistoryOrDirtyState() {
        Fixture fixture = fixture(List.of("abc"));
        long revision = fixture.model.revision();
        fixture.engine.backspace();
        fixture.engine.moveCursorTo(new Position(0, 3));
        fixture.engine.deleteForward();

        assertEquals(revision, fixture.model.revision());
        assertFalse(fixture.state.isDirty());
        assertFalse(fixture.engine.undo());
    }

    private static void delete(EditorEngine engine, boolean backward) {
        if (backward) {
            engine.backspace();
        } else {
            engine.deleteForward();
        }
    }

    private static Fixture fixture(List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        return fixture(model);
    }

    private static Fixture mixedFixture(List<String> lines, List<String> separators) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines, new DocumentFileMetadata(
            StandardCharsets.UTF_8, BomKind.UTF_8, "\n", separators
        ));
        return fixture(model);
    }

    private static Fixture fixture(TextModel model) {
        EditorState state = new EditorState();
        return new Fixture(model, state, new EditorEngine(model, state, new LogicalEditorTextLayout(model)));
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
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
