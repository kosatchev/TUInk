package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.editor.search.SearchOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineRegexTest {
    private static final SearchOptions REGEX = new SearchOptions(false, SearchOptions.MatchMode.REGEX);
    private final TextModel model = new TextModel(new SimpleLineBuffer());
    private final EditorState state = new EditorState();
    private final EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
    private void text(String value) { engine.setAutoPairsEnabled(false); engine.insertInput(value); state.setCursor(new Position(0, 0)); state.clearSelection(); }

    @Test void nextAndPreviousProgressThroughZeroWidthMatchesAndEmptyLines() {
        text("a\n\nb");
        assertTrue(engine.findNext("$", REGEX)); assertEquals(new Position(0, 1), state.getCursor());
        assertTrue(engine.findNext("$", REGEX)); assertEquals(new Position(1, 0), state.getCursor());
        assertTrue(engine.findNext("$", REGEX)); assertEquals(new Position(2, 1), state.getCursor());
        assertTrue(engine.findNext("$", REGEX)); assertEquals(new Position(0, 1), state.getCursor());
        assertTrue(engine.findPrevious("$", REGEX)); assertEquals(new Position(2, 1), state.getCursor());
    }

    @Test void replacingAnchorsIsAtomicAndUndoRestoresTheExactDocument() {
        text("first\n\nlast");
        var before = model.snapshotLines();
        assertEquals(3, engine.replaceAll("^", "> ", REGEX));
        assertEquals(List.of("> first", "> ", "> last"), model.snapshotLines());
        assertTrue(engine.undo()); assertEquals(before, model.snapshotLines());
    }

    @Test void aSingleEmptyMatchCanBeReplacedAndInvalidPatternsPreserveState() {
        text("abc");
        assertTrue(engine.findNext("$", REGEX));
        assertTrue(engine.replaceCurrentOrNext("$", "!", REGEX));
        assertEquals("abc!", model.getLine(0));
        var cursor = state.getCursor(); long revision = model.revision();
        assertThrows(SearchPatternException.class, () -> engine.replaceAll("[", "lost", REGEX));
        assertEquals(revision, model.revision()); assertEquals(cursor, state.getCursor());
    }

    @Test void evictingZeroWidthResultsRetainsBackwardNavigation() {
        text("a".repeat(700));
        for (int i = 0; i < 600; i++) assertTrue(engine.findNext("(?=a)", REGEX));
        assertEquals(new Position(0, 599), state.getCursor());
        for (int i = 0; i < 400; i++) assertTrue(engine.findPrevious("(?=a)", REGEX));
        assertEquals(new Position(0, 199), state.getCursor());
    }

    @Test void zeroWidthNavigationCrossesTabsAndWrapsInBothDirections() {
        text("a");
        var secondModel = new TextModel(new SimpleLineBuffer()); secondModel.replaceLines(List.of("b"));
        var secondState = new EditorState();
        var firstLayout = engine.textLayout();
        var secondLayout = new LogicalEditorTextLayout(secondModel);
        var tabs = List.of(new TabSession(model, state, firstLayout, engine),
            new TabSession(secondModel, secondState, secondLayout, new EditorEngine(secondModel, secondState, secondLayout)));
        assertEquals(0, TabSearch.findNextAcrossTabs(tabs, 0, "$", REGEX));
        assertEquals(1, TabSearch.findNextAcrossTabs(tabs, 0, "$", REGEX));
        assertEquals(0, TabSearch.findNextAcrossTabs(tabs, 1, "$", REGEX));
        assertEquals(1, TabSearch.findPreviousAcrossTabs(tabs, 0, "$", REGEX));
    }
}
