package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.LineText;
import dev.tuink.core.Position;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextModel;
import dev.tuink.render.TerminalEditorTextLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchPresentationTest {
    @Test
    void expensiveSearchIsExplicitlyIncompleteAndItsFailureIsCachedUntilInputsChange() {
        TabSession tab = tab("a".repeat(132));
        SearchPresentation presentation = new SearchPresentation(0);
        var options = new SearchController.SearchUiOptions(true, SearchOptions.MatchMode.WILDCARD, false);
        String query = "a".repeat(64) + "?a";

        var rejected = presentation.resolve(true, query, options, List.of(tab), 0);

        assertEquals(-1, rejected.matchCount());
        assertEquals("", rejected.query());
        assertTrue(rejected.error().startsWith("Search is too complex"));
        assertEquals(rejected.error(), rejected.summary());
        assertSame(rejected, presentation.resolve(true, query, options, List.of(tab), 0));
        var simpler = presentation.resolve(true, "a?a", options, List.of(tab), 0);
        assertEquals(44, simpler.matchCount());
        assertEquals("", simpler.error());
        assertFalse(simpler.query().isEmpty());

        presentation.resolve(true, query, options, List.of(tab), 0);
        tab.model().replaceLines(List.of("short"));
        var edited = presentation.resolve(true, query, options, List.of(tab), 0);
        assertEquals(0, edited.matchCount());
        assertEquals("", edited.error());
    }

    @Test
    void countsAndOptionalTabMarkersHaveIndependentBudgets() {
        TabSession tab = tab("a".repeat(66));
        SearchPresentation presentation = new SearchPresentation(100);
        var options = new SearchController.SearchUiOptions(true, SearchOptions.MatchMode.WILDCARD, false);

        String query = "a".repeat(64) + "?a";
        var complete = presentation.resolve(true, query, options, List.of(tab), 0);

        assertEquals(1, complete.matchCount());
        assertEquals(query, complete.query());
        assertEquals("", complete.error());
        assertArrayEquals(new boolean[] {true}, complete.tabMatchFlags());
    }

    @Test
    void inactiveMarkerFailureKeepsTheActiveSearchAndIsCachedAcrossCursorOnlyFrames() {
        CountingModel costlyModel = new CountingModel("a".repeat(1000) + "z");
        TabSession costly = tab(costlyModel);
        TabSession active = tab("a".repeat(160) + "z");
        List<TabSession> tabs = List.of(costly, active);
        SearchPresentation presentation = new SearchPresentation(400);
        var local = new SearchController.SearchUiOptions(true, SearchOptions.MatchMode.WILDCARD, false);
        String query = "a?".repeat(80) + "z";
        costlyModel.lineReads = 0;

        var complete = presentation.resolve(true, query, local, tabs, 1);

        assertEquals(1, complete.matchCount());
        assertEquals("Find 1", complete.summary());
        assertEquals(query, complete.query());
        assertEquals("", complete.error());
        assertArrayEquals(new boolean[] {false, true}, complete.tabMatchFlags());
        int markerReads = costlyModel.lineReads;
        assertTrue(markerReads > 0, "The inactive marker must actually reach its work limit");
        for (int frame = 0; frame < 10; frame++) {
            active.engine().moveCursorTo(new Position(0, frame));
            var cursorOnly = presentation.resolve(true, query, local, tabs, 1);
            assertSame(complete.tabMatchFlags(), cursorOnly.tabMatchFlags());
            assertEquals(1, cursorOnly.matchCount());
            assertEquals("", cursorOnly.error());
        }
        assertEquals(markerReads, costlyModel.lineReads, "A failed marker scan must be cached");

        var global = new SearchController.SearchUiOptions(true, SearchOptions.MatchMode.WILDCARD, true);
        var rejected = presentation.resolve(true, query, global, tabs, 1);
        assertEquals(-1, rejected.matchCount());
        assertEquals("", rejected.query());
        assertTrue(rejected.error().startsWith("Search is too complex"));
        int globalReads = costlyModel.lineReads;
        assertSame(rejected, presentation.resolve(true, query, global, tabs, 1));
        assertEquals(globalReads, costlyModel.lineReads, "The global count failure must also be cached");

        var localAgain = presentation.resolve(true, query, local, tabs, 1);
        assertEquals(query, localAgain.query());
        assertEquals(1, localAgain.matchCount());
        assertEquals("", localAgain.error());
        assertEquals(globalReads, costlyModel.lineReads, "Changing scope must not retry unchanged failed markers");

        costlyModel.replaceLines(List.of("a"));
        int beforeEditRefresh = costlyModel.lineReads;
        var edited = presentation.resolve(true, query, local, tabs, 1);
        assertArrayEquals(new boolean[] {false, true}, edited.tabMatchFlags());
        assertTrue(costlyModel.lineReads > beforeEditRefresh, "Editing a tab must invalidate its failed marker scan");

        var simpler = presentation.resolve(true, "a", local, tabs, 1);
        assertEquals(160, simpler.matchCount());
        assertArrayEquals(new boolean[] {true, true}, simpler.tabMatchFlags());
    }

    @Test
    void failedActiveOnlyCountDoesNotRescanWhenInactiveTabsChange() {
        CountingModel costlyModel = new CountingModel("a".repeat(1000) + "z");
        TabSession active = tab(costlyModel);
        TabSession inactive = tab("other");
        SearchPresentation presentation = new SearchPresentation(0);
        var options = new SearchController.SearchUiOptions(true, SearchOptions.MatchMode.WILDCARD, false);
        String query = "a?".repeat(80) + "z";
        var rejected = presentation.resolve(true, query, options, List.of(active, inactive), 0);
        int countReads = costlyModel.lineReads;

        inactive.model().replaceLines(List.of("edited elsewhere"));

        assertSame(rejected, presentation.resolve(true, query, options, List.of(active, inactive), 0));
        assertEquals(countReads, costlyModel.lineReads);
        var addedTab = presentation.resolve(true, query, options, List.of(active, inactive, tab("new")), 0);
        assertEquals(-1, addedTab.matchCount());
        assertArrayEquals(new boolean[] {false, false, false}, addedTab.tabMatchFlags());
        assertEquals(countReads, costlyModel.lineReads);
    }

    @Test
    void countsFollowTheSelectedScopeWhileMarkersDescribeAllMatchingTabs() {
        TabSession first = tab("\t \t");
        TabSession second = tab("\t");
        TabSession other = tab("other");
        List<TabSession> tabs = List.of(first, second, other);
        SearchPresentation presentation = new SearchPresentation();

        SearchPresentation.Snapshot single = presentation.resolve(true, "\t", SearchController.SearchUiOptions.DEFAULT, tabs, 0);

        assertEquals(2, single.matchCount());
        assertEquals("Find 2", single.summary());
        assertArrayEquals(new boolean[] {true, true, false}, single.tabMatchFlags());
        var allOptions = new SearchController.SearchUiOptions(false, SearchOptions.MatchMode.SUBSTRING, true);
        SearchPresentation.Snapshot all = presentation.resolve(true, "\t", allOptions, tabs, 0);
        assertEquals(3, all.matchCount());
        assertEquals("Find 3 in 3 tabs", all.summary());
        SearchPresentation.Snapshot switched = presentation.resolve(true, "\t", SearchController.SearchUiOptions.DEFAULT, tabs, 1);
        assertEquals(1, switched.matchCount());
        assertEquals("Find 1", switched.summary());
    }

    @Test
    void cursorOnlyFramesReuseMarkersAndEditsInvalidateTheDerivedValues() {
        TabSession tab = tab("needle");
        SearchPresentation presentation = new SearchPresentation();
        var before = presentation.resolve(true, "needle", SearchController.SearchUiOptions.DEFAULT, List.of(tab), 0);
        tab.engine().moveCursorTo(new Position(0, 3));
        var cursorOnly = presentation.resolve(true, "needle", SearchController.SearchUiOptions.DEFAULT, List.of(tab), 0);
        assertSame(before.tabMatchFlags(), cursorOnly.tabMatchFlags());
        assertEquals(1, cursorOnly.matchCount());

        tab.engine().selectAll();
        tab.engine().insertInput("changed");
        var changed = presentation.resolve(true, "needle", SearchController.SearchUiOptions.DEFAULT, List.of(tab), 0);
        assertEquals(0, changed.matchCount());
        assertArrayEquals(new boolean[] {false}, changed.tabMatchFlags());
        var hidden = presentation.resolve(false, "needle", SearchController.SearchUiOptions.DEFAULT, List.of(tab), 0);
        assertEquals("", hidden.query());
        assertEquals("", hidden.summary());
        assertArrayEquals(new boolean[] {false}, hidden.tabMatchFlags());
    }

    @Test
    void replacingATabWithAnotherModelAtTheSameRevisionRecomputesItsMarker() {
        TabSession matching = tab("needle");
        TabSession replacement = tab("other");
        assertEquals(matching.model().revision(), replacement.model().revision());
        SearchPresentation presentation = new SearchPresentation();
        var before = presentation.resolve(true, "needle", SearchController.SearchUiOptions.DEFAULT, List.of(matching), 0);
        assertArrayEquals(new boolean[] {true}, before.tabMatchFlags());

        var after = presentation.resolve(true, "needle", SearchController.SearchUiOptions.DEFAULT, List.of(replacement), 0);

        assertEquals(0, after.matchCount());
        assertArrayEquals(new boolean[] {false}, after.tabMatchFlags());
    }

    private static TabSession tab(String text) {
        TabSession session = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        session.engine().insertInput(text);
        return session;
    }

    private static TabSession tab(TextModel model) {
        EditorState state = new EditorState();
        var layout = new TerminalEditorTextLayout(model);
        return new TabSession(model, state, layout, new EditorEngine(model, state, layout));
    }

    private static final class CountingModel extends TextModel {
        private int lineReads;

        private CountingModel(String text) {
            super(new SimpleLineBuffer());
            replaceLines(List.of(text));
        }

        @Override
        public LineText lineText(int line) {
            lineReads++;
            return super.lineText(line);
        }
    }
}
