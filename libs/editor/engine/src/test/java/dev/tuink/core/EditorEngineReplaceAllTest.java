package dev.tuink.core;

import dev.tuink.editor.search.SearchOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineReplaceAllTest {
    @Test
    void multilineReplacementPreservesOriginalSeparatorsAndUsesOneUndoStep() {
        List<String> original = List.of("first😀", "A A", "middle", "A", "");
        DocumentFileMetadata metadata = new DocumentFileMetadata(
            StandardCharsets.UTF_8, BomKind.UTF_8, "\r\n", List.of("\n", "\r", "\r\n", "\n"));
        EditorEngine engine = engine(original, metadata);
        engine.restoreSelection(new Position(3, 1), new Position(0, 0));

        assertEquals(3, engine.replaceAll("A", "X\rY\nZ"));

        List<String> changed = List.of("first😀", "X", "Y", "Z X", "Y", "Z", "middle", "X", "Y", "Z", "");
        assertEquals(changed, engine.model().snapshotLines());
        assertSame(original.get(0), engine.model().getLine(0));
        assertSame(original.get(2), engine.model().getLine(6));
        assertEquals(List.of("\n", "\r\n", "\r\n", "\r\n", "\r\n", "\r", "\r\n", "\r\n", "\r\n", "\n"),
            engine.model().fileMetadata().lineSeparators());
        assertEquals(new Position(3, 1), engine.state().getCursor());
        assertFalse(engine.state().hasSelection());
        DocumentFileMetadata changedMetadata = engine.model().fileMetadata();

        assertTrue(engine.undo());
        assertEquals(original, engine.model().snapshotLines());
        assertEquals(metadata, engine.model().fileMetadata());
        assertEquals(new Position(3, 1), engine.state().getCursor());
        assertEquals(new Position(0, 0), engine.state().getSelectionAnchor());
        assertFalse(engine.state().isDirty());
        assertFalse(engine.undo());
        assertTrue(engine.redo());
        assertEquals(changed, engine.model().snapshotLines());
        assertEquals(changedMetadata, engine.model().fileMetadata());
        assertEquals(new Position(3, 1), engine.state().getCursor());
        assertTrue(engine.state().isDirty());
    }

    @Test
    void identicalMatchesStillCountButDoNotDetermineTheFirstChangedCursor() {
        List<String> original = List.of("keep😀", "aA tail", "untouched", "A e\u0301 a", "");
        DocumentFileMetadata metadata = new DocumentFileMetadata(
            StandardCharsets.UTF_16LE, BomKind.UTF_16_LE, "\r\n", List.of("\r\n", "\n", "\r", "\r\n"));
        EditorEngine engine = engine(original, metadata);

        assertEquals(5, engine.replaceAll("a", "a", new SearchOptions(true, SearchOptions.MatchMode.SUBSTRING)));

        assertEquals(List.of("keep😀", "aa tail", "untouched", "a e\u0301 a", ""), engine.model().snapshotLines());
        assertEquals(metadata, engine.model().fileMetadata());
        assertEquals(new Position(1, 2), engine.state().getCursor());
        assertTrue(engine.undo());
        assertEquals(original, engine.model().snapshotLines());
        assertFalse(engine.state().isDirty());
        assertFalse(engine.undo());
    }

    @Test
    void wildcardReplacementUsesCompleteSupplementaryRangesAndCanInsertNewlines() {
        EditorEngine engine = engine(List.of("😀x 𝔸x", "ax", ""), DocumentFileMetadata.newDocument(3));

        assertEquals(3, engine.replaceAll("?x", "!\n", new SearchOptions(false, SearchOptions.MatchMode.WILDCARD)));

        assertEquals(List.of("!", " !", "", "!", "", ""), engine.model().snapshotLines());
        assertEquals(new Position(1, 0), engine.state().getCursor());
        assertTrue(engine.undo());
        assertEquals(List.of("😀x 𝔸x", "ax", ""), engine.model().snapshotLines());
        assertFalse(engine.state().isDirty());
    }

    @Test
    void wholeWordReplacementLeavesCombiningSequencesAndLongerWordsIntact() {
        EditorEngine engine = engine(List.of("a aa a\u0301 a"), DocumentFileMetadata.newDocument(1));

        assertEquals(2, engine.replaceAll("a", "!", new SearchOptions(false, SearchOptions.MatchMode.WHOLE_WORD)));

        assertEquals("! aa a\u0301 !", engine.model().getLine(0));
        assertTrue(engine.undo());
        assertEquals("a aa a\u0301 a", engine.model().getLine(0));
    }

    @Test
    void identicalAndUnmatchedReplacementsPreserveTheSelectionRevisionSavepointAndRedo() {
        EditorEngine engine = engine(List.of("a a", "tail"), DocumentFileMetadata.newDocument(2));
        engine.insertInput("x");
        assertTrue(engine.undo());
        Position cursor = new Position(0, 0);
        Position anchor = new Position(0, 3);
        engine.restoreSelection(cursor, anchor);
        long revision = engine.model().revision();
        long navigationRevision = engine.navigationRevision();

        assertEquals(3, engine.replaceAll("a", "a"));
        assertEquals(0, engine.replaceAll("absent", "replacement"));

        assertEquals(List.of("a a", "tail"), engine.model().snapshotLines());
        assertEquals(revision, engine.model().revision());
        assertEquals(navigationRevision, engine.navigationRevision());
        assertEquals(cursor, engine.state().getCursor());
        assertEquals(anchor, engine.state().getSelectionAnchor());
        assertFalse(engine.state().isDirty());
        assertFalse(engine.undo());
        assertTrue(engine.redo());
        assertEquals(List.of("xa a", "tail"), engine.model().snapshotLines());
        assertTrue(engine.state().isDirty());
    }

    private static EditorEngine engine(List<String> lines, DocumentFileMetadata metadata) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines, metadata);
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        return engine;
    }
}
