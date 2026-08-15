package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
