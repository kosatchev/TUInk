package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import org.junit.jupiter.api.Test;

class SearchControllerTest {
    @Test
    void focusedSearchButtonsResolveToTheirActionsWithoutKeyboardAliases() {
        assertEquals(TuinkCommand.SEARCH_NEXT, SearchController.actionForSearchFocus(SearchFocus.NEXT_BUTTON));
        assertEquals(TuinkCommand.SEARCH_PREVIOUS, SearchController.actionForSearchFocus(SearchFocus.PREV_BUTTON));
        assertEquals(TuinkCommand.SEARCH_REPLACE, SearchController.actionForSearchFocus(SearchFocus.REPLACE_BUTTON));
        assertEquals(TuinkCommand.SEARCH_REPLACE_ALL, SearchController.actionForSearchFocus(SearchFocus.ALL_BUTTON));
        assertEquals(TuinkCommand.SEARCH_NEXT, SearchController.actionForSearchFocus(SearchFocus.FIND));
    }
}
