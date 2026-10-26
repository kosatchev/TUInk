package dev.tuink.core;

import dev.tuink.editor.search.SearchOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineSearchWrapTest {
    @Test
    void previousWrapIncludesTheSoleMatchAtTheCursor() {
        for (String text : List.of("abc", "prefix abc", "first\nabc")) {
            TabSession tab = tab(text);
            int line = tab.model().lineCount() - 1;
            int column = tab.model().getLine(line).indexOf("abc");
            tab.engine().moveCursorTo(new Position(line, column));

            assertTrue(tab.engine().findPrevious("abc"), text);
            assertEquals(new Position(line, column), tab.state().getSelectionAnchor());
            assertEquals(new Position(line, column + 3), tab.state().getCursor());
        }
    }

    @Test
    void bothDirectionsKeepFindingAnAlreadySelectedSoleMatch() {
        TabSession tab = tab("abc");
        assertTrue(tab.engine().findNext("abc"));

        for (int repeat = 0; repeat < 3; repeat++) {
            assertTrue(tab.engine().findPrevious("abc"));
            assertTrue(tab.engine().findNext("abc"));
            assertEquals(new Position(0, 0), tab.state().getSelectionAnchor());
            assertEquals(new Position(0, 3), tab.state().getCursor());
        }
    }

    @Test
    void previousVisitsOtherMatchesBeforeWrapping() {
        TabSession tab = tab("abc abc\nabc");

        for (Position expected : List.of(
            new Position(1, 0), new Position(0, 4), new Position(0, 0), new Position(1, 0)
        )) {
            assertTrue(tab.engine().findPrevious("abc"));
            assertEquals(expected, tab.state().getSelectionAnchor());
            assertEquals(new Position(expected.line(), expected.col() + 3), tab.state().getCursor());
        }
    }

    @Test
    void singleTabAndAllTabsAgreeOnWrappedUnicodeAndWildcardMatches() {
        for (SearchOptions.MatchMode mode : SearchOptions.MatchMode.values()) {
            TabSession local = tab("🙂 NEEDLE");
            TabSession workspace = tab("🙂 NEEDLE");
            local.engine().moveCursorTo(new Position(0, 3));
            workspace.engine().moveCursorTo(new Position(0, 3));
            SearchOptions options = new SearchOptions(true, mode);
            String query = mode == SearchOptions.MatchMode.WILDCARD ? "?eedle" : "needle";

            assertTrue(local.engine().findPrevious(query, options), mode.toString());
            assertEquals(0, TabSearch.findPreviousAcrossTabs(List.of(workspace), 0, query, options));
            assertEquals(workspace.state().getSelectionAnchor(), local.state().getSelectionAnchor());
            assertEquals(workspace.state().getCursor(), local.state().getCursor());
        }
    }

    private static TabSession tab(String text) {
        TabSession tab = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
        tab.model().replaceLines(List.of(text.split("\n", -1)));
        return tab;
    }
}
