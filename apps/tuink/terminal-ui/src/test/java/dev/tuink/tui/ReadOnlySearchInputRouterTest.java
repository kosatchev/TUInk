package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.EditorClipboard;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.SearchBarView;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadOnlySearchInputRouterTest {
    @Test
    void readonlyQueryRejectsOversizedTypedAndClipboardInputBeforeReplacingItsSelection() {
        var query = new TextField();
        query.set("needle"); query.selectAll();
        var placement = SearchBarView.buildReadOnly(query, 80, false, SearchOptions.MatchMode.SUBSTRING)
            .place(new Object(), 22, new Rect(0, 0, 80, 24));
        var focus = new FocusManager<SearchFocus>();
        var clipboard = new EditorClipboard();
        clipboard.setText("x".repeat(MarkdownPreviewState.SEARCH_QUERY_MAX_UTF16 + 1));
        assertEquals(SearchBarView.Action.INPUT_REJECTED,
            SearchInputRouter.route(focus, placement, new TextInput(clipboard.text())).action());
        assertEquals(SearchBarView.Action.INPUT_REJECTED,
            SearchInputRouter.routeCommand(focus, placement, TuinkCommand.EDIT_PASTE, clipboard).action());
        assertEquals("needle", query.text());
        assertEquals("needle", query.selectedText());
        assertEquals(MarkdownPreviewState.SEARCH_QUERY_MAX_UTF16 + 1, clipboard.text().length());
        assertEquals(SearchBarView.Action.QUERY_CHANGED,
            SearchInputRouter.route(focus, placement, new TextInput("x".repeat(MarkdownPreviewState.SEARCH_QUERY_MAX_UTF16))).action());
        assertEquals(MarkdownPreviewState.SEARCH_QUERY_MAX_UTF16, query.length());
    }

    @Test
    void replacementCommandsAreConsumedWithoutEmittingAMutationIntent() {
        var placement = SearchBarView.buildReadOnly(new TextField(), 80, false, SearchOptions.MatchMode.SUBSTRING)
            .place(new Object(), 22, new Rect(0, 0, 80, 24));
        var focus = new FocusManager<SearchFocus>();
        for (var command : List.of(TuinkCommand.SEARCH_REPLACE, TuinkCommand.SEARCH_REPLACE_ALL)) {
            var result = SearchInputRouter.routeCommand(focus, placement, command, new EditorClipboard());
            assertTrue(result.consumed());
            assertNull(result.action());
        }
    }

    @Test
    void tabAndArrowsVisitOnlyPaintedReadonlyTargets() {
        var placement = SearchBarView.buildReadOnly(new TextField(), 80, false, SearchOptions.MatchMode.SUBSTRING)
            .place(new Object(), 22, new Rect(0, 0, 80, 24));
        var focus = new FocusManager<SearchFocus>();
        placement.activate(focus);
        for (var expected : List.of(SearchFocus.CASE, SearchFocus.WORD, SearchFocus.MODE,
                SearchFocus.NEXT_BUTTON, SearchFocus.PREV_BUTTON, SearchFocus.FIND)) {
            assertTrue(SearchInputRouter.route(focus, placement, KeyStroke.of(KeyStroke.Key.TAB)).consumed());
            assertEquals(expected, focus.focused().orElseThrow());
        }
        focus.focus(placement.focusScope(), SearchFocus.NEXT_BUTTON);
        assertTrue(SearchInputRouter.route(focus, placement, KeyStroke.of(KeyStroke.Key.ARROW_UP)).consumed());
        assertTrue(placement.focusScope().target(focus.focused().orElseThrow()).isPresent());
        assertEquals(SearchBarView.Action.FIND_NEXT,
            SearchInputRouter.routeCommand(focus, placement, TuinkCommand.SEARCH_NEXT, new EditorClipboard()).action());
    }
}
