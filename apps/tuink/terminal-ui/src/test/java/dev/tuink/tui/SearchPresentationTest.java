package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchPresentationTest {
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
}
