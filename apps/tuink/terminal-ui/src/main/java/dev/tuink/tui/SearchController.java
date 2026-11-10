package dev.tuink.tui;

import dev.tuink.editor.search.SearchOptions;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.command.TuinkCommand;

final class SearchController {
    private SearchController() {
    }

    record SearchUiOptions(boolean caseSensitive, SearchOptions.MatchMode matchMode, boolean allTabs, boolean wholeWord) {
        SearchUiOptions(boolean caseSensitive, SearchOptions.MatchMode matchMode, boolean allTabs) {
            this(caseSensitive, matchMode, allTabs, false);
        }
        static final SearchUiOptions DEFAULT = new SearchUiOptions(false, SearchOptions.MatchMode.SUBSTRING, false);
    }

    static SearchUiOptions toggleFocusedSearchOption(SearchUiOptions options, SearchFocus focus) {
        return switch (focus) {
            case CASE -> new SearchUiOptions(!options.caseSensitive(), options.matchMode(), options.allTabs(), options.wholeWord());
            case WORD -> new SearchUiOptions(
                options.caseSensitive(),
                options.matchMode(), options.allTabs(), !options.wholeWord()
            );
            case ALL -> new SearchUiOptions(
                options.caseSensitive(),
                options.matchMode(),
                !options.allTabs(), options.wholeWord()
            );
            default -> options;
        };
    }

    static SearchOptions toSearchOptions(SearchUiOptions ui) {
        SearchUiOptions safe = ui == null ? SearchUiOptions.DEFAULT : ui;
        return new SearchOptions(!safe.caseSensitive(), safe.matchMode(), safe.wholeWord());
    }
    static SearchUiOptions withMode(SearchUiOptions options, SearchOptions.MatchMode mode) {
        return new SearchUiOptions(options.caseSensitive(), mode, options.allTabs(), options.wholeWord());
    }

    static boolean isToggleFocus(SearchFocus focus) {
        return focus == SearchFocus.CASE || focus == SearchFocus.WORD
            || focus == SearchFocus.ALL;
    }

    static boolean isSearchActionFocus(SearchFocus focus) {
        return focus == SearchFocus.NEXT_BUTTON
            || focus == SearchFocus.PREV_BUTTON
            || focus == SearchFocus.REPLACE_BUTTON
            || focus == SearchFocus.ALL_BUTTON;
    }

    static boolean isSearchFieldFocus(SearchFocus focus) {
        return focus == SearchFocus.FIND || focus == SearchFocus.REPLACE;
    }

    static TuinkCommand actionForSearchFocus(SearchFocus focus) {
        return switch (focus) {
            case NEXT_BUTTON -> TuinkCommand.SEARCH_NEXT;
            case PREV_BUTTON -> TuinkCommand.SEARCH_PREVIOUS;
            case REPLACE_BUTTON -> TuinkCommand.SEARCH_REPLACE;
            case ALL_BUTTON -> TuinkCommand.SEARCH_REPLACE_ALL;
            default -> TuinkCommand.SEARCH_NEXT;
        };
    }

    static SearchFocus focusForAction(TuinkCommand command) {
        return switch (command) {
            case SEARCH_NEXT -> SearchFocus.NEXT_BUTTON;
            case SEARCH_PREVIOUS -> SearchFocus.PREV_BUTTON;
            case SEARCH_REPLACE -> SearchFocus.REPLACE_BUTTON;
            case SEARCH_REPLACE_ALL -> SearchFocus.ALL_BUTTON;
            default -> throw new IllegalArgumentException("Not a document search command: " + command);
        };
    }
}
