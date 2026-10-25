package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.DocumentFileMetadata;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineIdenticalReplaceTest {
    private static final SearchOptions WILDCARD = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);
    private static final String QUERY = "a".repeat(64) + "?a";
    private static final String REPLACEMENT = "a".repeat(66);
    private static final String SOURCE = "z".repeat(200) + REPLACEMENT;
    private static final String OTHER_LINE = "z".repeat(200);
    private static final long WORK_LIMIT = 700;

    @Test
    void aRefusedAdvancePreservesAbsentReversedAndUnrelatedSelections() {
        for (int selection = 0; selection < 3; selection++) {
            EditorEngine engine = engine();
            if (selection == 1) {
                engine.restoreSelection(new Position(0, 200), new Position(0, 266));
            } else if (selection == 2) {
                engine.restoreSelection(new Position(0, 52), new Position(0, 50));
            }
            engine.state().setViewport(new ViewportState(0, 0, 17, 17));
            EditorViewMemento view = engine.state().viewMemento();
            long navigation = engine.navigationRevision();
            long revision = engine.model().revision();
            DocumentFileMetadata metadata = engine.model().fileMetadata();
            assertEquals(1, SearchWorkBudget.run(WORK_LIMIT,
                () -> TabSearch.countMatchesInModel(engine.model(), QUERY, WILDCARD)));

            assertThrows(SearchLimitException.class, () -> SearchWorkBudget.run(WORK_LIMIT,
                () -> engine.replaceCurrentOrNext(QUERY, REPLACEMENT, WILDCARD)));

            assertEquals(view, engine.state().viewMemento());
            assertEquals(navigation, engine.navigationRevision());
            assertEquals(revision, engine.model().revision());
            assertSame(metadata, engine.model().fileMetadata());
            assertEquals(SOURCE, engine.model().getLine(0));
            assertFalse(engine.state().isDirty());
            assertFalse(engine.undo());
            assertFalse(engine.redo());
        }
    }

    @Test
    void aRefusedAdvancePreservesTheExistingRedoBranch() {
        EditorEngine engine = engine();
        engine.insertChar('X');
        engine.insertNewline();
        assertTrue(engine.undo());
        EditorViewMemento view = engine.state().viewMemento();
        long revision = engine.model().revision();

        assertThrows(SearchLimitException.class, () -> SearchWorkBudget.run(WORK_LIMIT,
            () -> engine.replaceCurrentOrNext(QUERY, REPLACEMENT, WILDCARD)));

        assertEquals(view, engine.state().viewMemento());
        assertEquals(revision, engine.model().revision());
        assertTrue(engine.state().isDirty());
        assertEquals("X" + SOURCE, engine.model().getLine(0));
        assertTrue(engine.redo());
        assertEquals(List.of("X", SOURCE, OTHER_LINE), engine.model().snapshotLines());
        assertTrue(engine.undo());
        assertEquals("X" + SOURCE, engine.model().getLine(0));
        assertTrue(engine.undo());
        assertEquals(SOURCE, engine.model().getLine(0));
        assertFalse(engine.state().isDirty());
    }

    @Test
    void aRefusedAdvanceDoesNotSplitThePendingTypingUndoGroup() {
        EditorEngine engine = engine();
        engine.insertChar('X');
        EditorDocumentHistory history = engine.document().history;
        var top = history.undoStack.peek();
        long editedAt = history.lastEditAtNanos;
        Position cursorAfter = history.lastEditCursorAfter;

        assertThrows(SearchLimitException.class, () -> SearchWorkBudget.run(WORK_LIMIT,
            () -> engine.replaceCurrentOrNext(QUERY, REPLACEMENT, WILDCARD)));

        assertSame(top, history.undoStack.peek());
        assertEquals(EditorEngine.EditKind.INSERT_CHAR, history.lastEditKind);
        assertEquals(editedAt, history.lastEditAtNanos);
        assertEquals(cursorAfter, history.lastEditCursorAfter);
        // Keep the gesture inside the time window without depending on test-runner scheduling.
        history.lastEditAtNanos = System.nanoTime();
        engine.insertChar('Y');
        assertEquals("XY" + SOURCE, engine.model().getLine(0));
        assertTrue(engine.undo());
        assertEquals(SOURCE, engine.model().getLine(0));
        assertFalse(engine.undo());
    }

    @Test
    void successfulIdenticalReplacementsAdvanceAndWrapUsingExactUtf16MatchRanges() {
        for (String word : List.of("e", "👩")) {
            String cluster = word.equals("e") ? "e\u0301" : "👩‍💻";
            String source = cluster + " " + cluster;
            EditorEngine engine = engine(source);
            engine.restoreSelection(new Position(0, 0), new Position(0, word.length()));
            long revision = engine.model().revision();
            int second = cluster.length() + 1;

            assertTrue(engine.replaceCurrentOrNext(word, word));

            assertEquals(new Position(0, second), engine.state().getSelectionAnchor());
            assertEquals(new Position(0, second + word.length()), engine.state().getCursor());
            assertTrue(engine.replaceCurrentOrNext(word, word));
            assertEquals(new Position(0, 0), engine.state().getSelectionAnchor());
            assertEquals(new Position(0, word.length()), engine.state().getCursor());
            assertEquals(source, engine.model().getLine(0));
            assertEquals(revision, engine.model().revision());
            assertFalse(engine.state().isDirty());
            assertFalse(engine.undo());
        }
    }

    @Test
    void aSingleIdenticalMatchWrapsBackToItselfWithoutAnUndoStep() {
        EditorEngine engine = engine("👩‍💻");
        long revision = engine.model().revision();

        assertTrue(engine.replaceCurrentOrNext("👩", "👩"));

        assertEquals(new Position(0, 0), engine.state().getSelectionAnchor());
        assertEquals(new Position(0, 2), engine.state().getCursor());
        assertEquals(revision, engine.model().revision());
        assertFalse(engine.undo());
    }

    private static EditorEngine engine() {
        // A full count fits the work budget. Advancing through the other line
        // and wrapping evicts the first line's scanner and really exceeds it,
        // even when sequential same-line search no longer repeats prefixes.
        return engine(List.of(SOURCE, OTHER_LINE));
    }

    private static EditorEngine engine(String source) {
        return engine(List.of(source));
    }

    private static EditorEngine engine(List<String> source) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, source.getFirst());
        for (int line = 1; line < source.size(); line++) buffer.insertLine(line, source.get(line));
        TextModel model = new TextModel(buffer);
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.setAutoPairsEnabled(false);
        engine.setAutoIndentEnabled(false);
        engine.setAutoListEnabled(false);
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        return engine;
    }
}
