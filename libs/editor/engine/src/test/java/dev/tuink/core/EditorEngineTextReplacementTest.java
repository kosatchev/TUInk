package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EditorEngineTextReplacementTest {
    @Test
    void validatesEveryRangeBeforeMutatingAnything() {
        Fixture fixture = fixture(List.of("one", "A😀B"));
        long revision = fixture.model.revision();

        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.engine.applyTextReplacements(List.of(
                replacement(0, 0, 0, 3, "ONE"),
                replacement(1, 0, 1, 5, "invalid")
            ))
        );
        assertEquals(List.of("one", "A😀B"), fixture.model.snapshotLines());
        assertEquals(revision, fixture.model.revision());
        assertFalse(fixture.state.isDirty());
        assertFalse(fixture.engine.undo());

        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.engine.applyTextReplacements(List.of(
                replacement(1, 2, 1, 3, "split")
            ))
        );
        assertEquals(List.of("one", "A😀B"), fixture.model.snapshotLines());
        assertEquals(revision, fixture.model.revision());

        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.engine.applyTextReplacements(List.of(
                replacement(0, 0, 0, 1, "bad\uD800")
            ))
        );
        assertEquals(List.of("one", "A😀B"), fixture.model.snapshotLines());
        assertEquals(revision, fixture.model.revision());
        assertFalse(fixture.engine.undo());
    }

    @Test
    void appliesCanonicalBatchAsOneUndoStepAndPreservesMetadata() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        DocumentFileMetadata metadata = new DocumentFileMetadata(
            StandardCharsets.UTF_8,
            BomKind.UTF_8,
            EditorState.LINE_SEPARATOR_CRLF,
            List.of(
                EditorState.LINE_SEPARATOR_CRLF,
                EditorState.LINE_SEPARATOR_LF,
                EditorState.LINE_SEPARATOR_CR
            )
        );
        List<String> original = List.of("one", "two", "three", "");
        model.replaceLines(original, metadata);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));

        assertTrue(engine.applyTextReplacements(List.of(
            replacement(2, 0, 2, 5, "THREE"),
            replacement(0, 0, 0, 3, "ONE")
        )));

        assertEquals(List.of("ONE", "two", "THREE", ""), model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertTrue(state.isDirty());

        assertTrue(engine.undo());
        assertEquals(original, model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertFalse(state.isDirty());
        assertFalse(engine.undo());

        assertTrue(engine.redo());
        assertEquals(List.of("ONE", "two", "THREE", ""), model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertTrue(state.isDirty());
    }

    @Test
    void normalizedLogicalNoOpDoesNotRewriteMixedLineSeparators() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        DocumentFileMetadata metadata = new DocumentFileMetadata(
            StandardCharsets.UTF_8,
            BomKind.NONE,
            EditorState.LINE_SEPARATOR_LF,
            List.of(EditorState.LINE_SEPARATOR_CRLF)
        );
        model.replaceLines(List.of("one", "two"), metadata);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        long revision = model.revision();

        assertFalse(engine.applyTextReplacements(List.of(
            replacement(0, 0, 1, 3, "one\r\ntwo")
        )));

        assertEquals(List.of("one", "two"), model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertEquals(revision, model.revision());
        assertFalse(state.isDirty());
        assertFalse(engine.undo());
    }

    @Test
    void mixedMultilineBatchPreservesUntouchedSeparatorsAndEarliestCursorAcrossUndo() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        List<String> original = List.of("lead😀", "a😀bc", "untouched", "de\u0301f", "tail", "");
        DocumentFileMetadata metadata = new DocumentFileMetadata(
            StandardCharsets.UTF_8, BomKind.UTF_8, EditorState.LINE_SEPARATOR_CRLF,
            List.of("\r\n", "\n", "\r", "\n", "\r\n")
        );
        model.replaceLines(original, metadata);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.selectAll();

        assertTrue(engine.applyTextReplacements(List.of(
            replacement(3, 0, 4, 2, "界\n"),
            replacement(0, 0, 0, 6, "lead😀"), // A preceding no-op must not determine the cursor.
            replacement(1, 1, 1, 3, "X\r\nY")
        )));

        List<String> changed = List.of("lead😀", "aX", "Ybc", "untouched", "界", "il", "");
        assertEquals(changed, model.snapshotLines());
        assertSame(original.get(0), model.getLine(0));
        assertSame(original.get(2), model.getLine(3));
        assertEquals(List.of("\r\n", "\r\n", "\n", "\r", "\r\n", "\r\n"),
            model.fileMetadata().lineSeparators());
        assertEquals(new Position(2, 1), state.getCursor());
        assertFalse(state.hasSelection());
        DocumentFileMetadata changedMetadata = model.fileMetadata();

        assertTrue(engine.undo());
        assertEquals(original, model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertTrue(state.hasSelection());
        assertFalse(state.isDirty());
        assertFalse(engine.undo());
        assertTrue(engine.redo());
        assertEquals(changed, model.snapshotLines());
        assertEquals(changedMetadata, model.fileMetadata());
        assertEquals(new Position(2, 1), state.getCursor());
    }

    @Test
    void adjacentReplacementsAndAnEndInsertionUseOriginalUtf16Coordinates() {
        Fixture fixture = fixture(List.of("😀ab"));

        fixture.engine.applyTextReplacements(List.of(
            replacement(0, 4, 0, 4, "!"),
            replacement(0, 3, 0, 4, "Z"),
            replacement(0, 2, 0, 3, "X\nY")
        ));

        assertEquals(List.of("😀X", "YZ!"), fixture.model.snapshotLines());
        assertEquals(new Position(1, 1), fixture.state.getCursor());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("😀ab"), fixture.model.snapshotLines());
    }

    @Test
    void denseReplaceAllWritesOnlyLinearTextVolume() {
        AtomicLong writtenCharacters = new AtomicLong();
        TextModel model = new TextModel(new CountingBuffer(writtenCharacters));
        int length = 50_000;
        model.insertText(new Position(0, 0), "a".repeat(length));
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        writtenCharacters.set(0L);

        assertEquals(length, engine.replaceAll("a", "b"));

        assertEquals("b".repeat(length), model.getLine(0));
        assertTrue(writtenCharacters.get() <= 3L * length,
            "Dense replacement must not rewrite the entire line once for every match");
        assertTrue(engine.undo());
        assertEquals("a".repeat(length), model.getLine(0));
        assertFalse(engine.undo());
        assertTrue(engine.redo());
        assertEquals("b".repeat(length), model.getLine(0));
    }

    private static final class CountingBuffer extends SimpleLineBuffer {
        private final AtomicLong writtenCharacters;

        private CountingBuffer(AtomicLong writtenCharacters) {
            this.writtenCharacters = writtenCharacters;
        }

        @Override
        public TextBuffer createEmptySibling() {
            return new CountingBuffer(writtenCharacters);
        }

        @Override
        public void setLine(int line, String text) {
            writtenCharacters.addAndGet(text.length());
            super.setLine(line, text);
        }

        @Override
        public void insertLine(int index, String text) {
            writtenCharacters.addAndGet(text.length());
            super.insertLine(index, text);
        }
    }

    private static Fixture fixture(List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        EditorState state = new EditorState();
        return new Fixture(
            model,
            state,
            new EditorEngine(model, state, new LogicalEditorTextLayout(model))
        );
    }

    private static TextReplacement replacement(
        int startLine,
        int startOffset,
        int endLine,
        int endOffset,
        String replacement
    ) {
        return new TextReplacement(
            new Position(startLine, startOffset),
            new Position(endLine, endOffset),
            replacement
        );
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
    }
}
