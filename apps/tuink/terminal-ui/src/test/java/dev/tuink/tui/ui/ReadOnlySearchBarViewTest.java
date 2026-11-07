package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.TextField;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.tui.flow.SearchFocus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadOnlySearchBarViewTest {
    private static final List<SearchFocus> FIND_ONLY = List.of(SearchFocus.FIND, SearchFocus.CASE,
        SearchFocus.WORD, SearchFocus.WILDCARD, SearchFocus.NEXT_BUTTON, SearchFocus.PREV_BUTTON);

    @Test
    void findOnlyCompositionHasNoReplacementOrAllTabsTargets() {
        var view = SearchBarView.buildReadOnly(new TextField(), 80, true, SearchOptions.MatchMode.SUBSTRING);
        var placement = view.place(new Object(), new Rect(0, 22, 80, 1), new Rect(0, 0, 80, 24));
        assertTrue(placement.readOnly());
        assertEquals(FIND_ONLY, placement.focusScope().tabOrder());
        assertFalse(placement.replacementContextVisible());
        assertEquals("", placement.row2());
        assertFalse(placement.text().contains("\n"));
        for (var absent : List.of(SearchFocus.REPLACE, SearchFocus.REPLACE_BUTTON, SearchFocus.ALL_BUTTON, SearchFocus.ALL)) {
            assertTrue(placement.focusScope().target(absent).isEmpty());
            assertTrue(placement.field(absent).isEmpty());
        }
    }

    @Test
    void fieldsAndButtonsUseTheirParentPaneCoordinates() {
        var field = new TextField();
        var view = SearchBarView.buildReadOnly(field, 49, false, SearchOptions.MatchMode.WHOLE_WORD);
        var bounds = new Rect(31, 21, 49, 1);
        var placement = view.place(new Object(), bounds, new Rect(0, 0, 80, 24));
        var focus = new FocusManager<SearchFocus>();
        placement.activate(focus);
        assertEquals(37, placement.targetBounds(SearchFocus.FIND).x());
        for (var id : FIND_ONLY) assertTrue(bounds.contains(placement.targetBounds(id)), id.toString());
        var query = placement.focusScope().target(SearchFocus.FIND).orElseThrow();
        var result = query.placement().handle(new TextInput("x"), focus.peekContext(placement.focusScope(), SearchFocus.FIND).orElseThrow());
        assertEquals(SearchBarView.Action.QUERY_CHANGED, result.output().orElseThrow());
        assertEquals("x", field.text());
        assertTrue(bounds.contains(placement.cursorPosition(focus).orElseThrow()));
    }

    @Test
    void narrowFindOnlyLayoutRetainsEverySupportedControlAndNoUnsupportedFocus() {
        var view = SearchBarView.buildReadOnly(new TextField(), 24, false, SearchOptions.MatchMode.WILDCARD);
        int rows = view.text().split("\n", -1).length;
        assertTrue(rows > 1);
        var bounds = new Rect(17, 8, 24, rows);
        var placement = view.place(new Object(), bounds, bounds);
        var focus = new FocusManager<SearchFocus>();
        placement.activate(focus);
        for (var id : FIND_ONLY) {
            assertEquals(id, focus.focused().orElseThrow());
            assertTrue(bounds.contains(placement.targetBounds(id)), id.toString());
            focus.next(placement.focusScope());
        }
        assertEquals(SearchFocus.FIND, focus.focused().orElseThrow());
        var below = placement.verticalTarget(SearchFocus.FIND, false);
        assertNotEquals(SearchFocus.FIND, below);
        assertTrue(FIND_ONLY.contains(below));
        assertTrue(placement.targetBounds(below).y() > placement.targetBounds(SearchFocus.FIND).y());
    }

    @Test
    void editableSearchReceivesTheSameOffsetWithoutChangingItsControls() {
        var view = SearchBarView.build(new TextField(), new TextField(), 80, false,
            SearchOptions.MatchMode.SUBSTRING, true);
        var origin = view.place(new Object(), 4, new Rect(0, 0, 120, 20));
        var shifted = view.place(new Object(), new Rect(29, 4, 80, 2), new Rect(0, 0, 120, 20));
        assertFalse(shifted.readOnly());
        assertEquals(origin.focusScope().tabOrder(), shifted.focusScope().tabOrder());
        for (var id : origin.focusScope().tabOrder()) {
            Rect before = origin.targetBounds(id), after = shifted.targetBounds(id);
            assertEquals(new Rect(before.x() + 29, before.y(), before.width(), before.height()), after);
        }
    }
}
