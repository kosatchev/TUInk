package dev.tuink.tui;

import dev.tuink.core.SearchOptions;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.input.KeyEvent;

final class SearchController {
    private SearchController() {
    }

    record SearchUiOptions(boolean caseSensitive, SearchOptions.MatchMode matchMode, boolean allTabs) {
        static final SearchUiOptions DEFAULT = new SearchUiOptions(false, SearchOptions.MatchMode.SUBSTRING, false);
    }

    static SearchUiOptions toggleFocusedSearchOption(SearchUiOptions options, SearchFocus focus) {
        return switch (focus) {
            case CASE -> new SearchUiOptions(!options.caseSensitive(), options.matchMode(), options.allTabs());
            case WORD -> new SearchUiOptions(
                options.caseSensitive(),
                options.matchMode() == SearchOptions.MatchMode.WHOLE_WORD
                    ? SearchOptions.MatchMode.SUBSTRING
                    : SearchOptions.MatchMode.WHOLE_WORD,
                options.allTabs()
            );
            case WILDCARD -> new SearchUiOptions(
                options.caseSensitive(),
                options.matchMode() == SearchOptions.MatchMode.WILDCARD
                    ? SearchOptions.MatchMode.SUBSTRING
                    : SearchOptions.MatchMode.WILDCARD,
                options.allTabs()
            );
            case ALL -> new SearchUiOptions(
                options.caseSensitive(),
                options.matchMode(),
                !options.allTabs()
            );
            default -> options;
        };
    }

    static SearchOptions toSearchOptions(SearchUiOptions ui) {
        SearchUiOptions safe = ui == null ? SearchUiOptions.DEFAULT : ui;
        return new SearchOptions(!safe.caseSensitive(), safe.matchMode());
    }

    static boolean isToggleFocus(SearchFocus focus) {
        return focus == SearchFocus.CASE || focus == SearchFocus.WORD
            || focus == SearchFocus.WILDCARD || focus == SearchFocus.ALL;
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

    static KeyEvent.Action actionForSearchFocus(SearchFocus focus) {
        return switch (focus) {
            case NEXT_BUTTON -> KeyEvent.Action.CTRL_F;
            case PREV_BUTTON -> KeyEvent.Action.CTRL_G;
            case REPLACE_BUTTON -> KeyEvent.Action.CTRL_R;
            case ALL_BUTTON -> KeyEvent.Action.CTRL_A;
            default -> null;
        };
    }

    static SearchFocus nextSearchFocus(SearchFocus focus) {
        return switch (focus) {
            case FIND -> SearchFocus.CASE;
            case CASE -> SearchFocus.WORD;
            case WORD -> SearchFocus.WILDCARD;
            case WILDCARD -> SearchFocus.ALL;
            case ALL -> SearchFocus.REPLACE;
            case REPLACE -> SearchFocus.NEXT_BUTTON;
            case NEXT_BUTTON -> SearchFocus.PREV_BUTTON;
            case PREV_BUTTON -> SearchFocus.REPLACE_BUTTON;
            case REPLACE_BUTTON -> SearchFocus.ALL_BUTTON;
            case ALL_BUTTON -> SearchFocus.FIND;
        };
    }

    static SearchFocus nextSearchFocusHorizontal(SearchFocus focus) {
        return switch (focus) {
            case FIND -> SearchFocus.CASE;
            case CASE -> SearchFocus.WORD;
            case WORD -> SearchFocus.WILDCARD;
            case WILDCARD -> SearchFocus.ALL;
            case ALL -> SearchFocus.REPLACE;
            case REPLACE -> SearchFocus.NEXT_BUTTON;
            case NEXT_BUTTON -> SearchFocus.PREV_BUTTON;
            case PREV_BUTTON -> SearchFocus.REPLACE_BUTTON;
            case REPLACE_BUTTON -> SearchFocus.ALL_BUTTON;
            case ALL_BUTTON -> SearchFocus.FIND;
        };
    }

    static SearchFocus previousSearchFocusHorizontal(SearchFocus focus) {
        return switch (focus) {
            case FIND -> SearchFocus.ALL_BUTTON;
            case CASE -> SearchFocus.FIND;
            case WORD -> SearchFocus.CASE;
            case WILDCARD -> SearchFocus.WORD;
            case ALL -> SearchFocus.WILDCARD;
            case REPLACE -> SearchFocus.ALL;
            case NEXT_BUTTON -> SearchFocus.REPLACE;
            case PREV_BUTTON -> SearchFocus.NEXT_BUTTON;
            case REPLACE_BUTTON -> SearchFocus.PREV_BUTTON;
            case ALL_BUTTON -> SearchFocus.REPLACE_BUTTON;
        };
    }

    static SearchFocus verticalSearchFocus(SearchFocus focus, boolean up) {
        return switch (focus) {
            case FIND -> up ? SearchFocus.REPLACE : SearchFocus.REPLACE;
            case CASE -> up ? SearchFocus.NEXT_BUTTON : SearchFocus.NEXT_BUTTON;
            case WORD -> up ? SearchFocus.PREV_BUTTON : SearchFocus.PREV_BUTTON;
            case WILDCARD -> up ? SearchFocus.REPLACE_BUTTON : SearchFocus.REPLACE_BUTTON;
            case ALL -> up ? SearchFocus.ALL_BUTTON : SearchFocus.ALL_BUTTON;
            case REPLACE -> up ? SearchFocus.FIND : SearchFocus.FIND;
            case NEXT_BUTTON -> up ? SearchFocus.CASE : SearchFocus.CASE;
            case PREV_BUTTON -> up ? SearchFocus.WORD : SearchFocus.WORD;
            case REPLACE_BUTTON -> up ? SearchFocus.WILDCARD : SearchFocus.WILDCARD;
            case ALL_BUTTON -> up ? SearchFocus.ALL : SearchFocus.ALL;
        };
    }
}
