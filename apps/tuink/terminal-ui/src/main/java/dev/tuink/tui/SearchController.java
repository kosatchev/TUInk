package dev.tuink.tui;

import dev.tuink.core.SearchOptions;

final class SearchController {
    private SearchController() {
    }

    record SearchUiOptions(boolean caseSensitive, SearchOptions.MatchMode matchMode, boolean allTabs) {
        static final SearchUiOptions DEFAULT = new SearchUiOptions(false, SearchOptions.MatchMode.SUBSTRING, false);
    }

    static SearchUiOptions toggleFocusedSearchOption(SearchUiOptions options, TerminalEditorApp.SearchFocus focus) {
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

    static boolean isToggleFocus(TerminalEditorApp.SearchFocus focus) {
        return focus == TerminalEditorApp.SearchFocus.CASE || focus == TerminalEditorApp.SearchFocus.WORD
            || focus == TerminalEditorApp.SearchFocus.WILDCARD || focus == TerminalEditorApp.SearchFocus.ALL;
    }

    static boolean isSearchActionFocus(TerminalEditorApp.SearchFocus focus) {
        return focus == TerminalEditorApp.SearchFocus.NEXT_BUTTON
            || focus == TerminalEditorApp.SearchFocus.PREV_BUTTON
            || focus == TerminalEditorApp.SearchFocus.REPLACE_BUTTON
            || focus == TerminalEditorApp.SearchFocus.ALL_BUTTON;
    }

    static boolean isSearchFieldFocus(TerminalEditorApp.SearchFocus focus) {
        return focus == TerminalEditorApp.SearchFocus.FIND || focus == TerminalEditorApp.SearchFocus.REPLACE;
    }

    static KeyEvent.Action actionForSearchFocus(TerminalEditorApp.SearchFocus focus) {
        return switch (focus) {
            case NEXT_BUTTON -> KeyEvent.Action.CTRL_F;
            case PREV_BUTTON -> KeyEvent.Action.CTRL_G;
            case REPLACE_BUTTON -> KeyEvent.Action.CTRL_R;
            case ALL_BUTTON -> KeyEvent.Action.CTRL_A;
            default -> null;
        };
    }

    static TerminalEditorApp.SearchFocus nextSearchFocus(TerminalEditorApp.SearchFocus focus) {
        return switch (focus) {
            case FIND -> TerminalEditorApp.SearchFocus.CASE;
            case CASE -> TerminalEditorApp.SearchFocus.WORD;
            case WORD -> TerminalEditorApp.SearchFocus.WILDCARD;
            case WILDCARD -> TerminalEditorApp.SearchFocus.ALL;
            case ALL -> TerminalEditorApp.SearchFocus.REPLACE;
            case REPLACE -> TerminalEditorApp.SearchFocus.NEXT_BUTTON;
            case NEXT_BUTTON -> TerminalEditorApp.SearchFocus.PREV_BUTTON;
            case PREV_BUTTON -> TerminalEditorApp.SearchFocus.REPLACE_BUTTON;
            case REPLACE_BUTTON -> TerminalEditorApp.SearchFocus.ALL_BUTTON;
            case ALL_BUTTON -> TerminalEditorApp.SearchFocus.FIND;
        };
    }

    static TerminalEditorApp.SearchFocus nextSearchFocusHorizontal(TerminalEditorApp.SearchFocus focus) {
        return switch (focus) {
            case FIND -> TerminalEditorApp.SearchFocus.CASE;
            case CASE -> TerminalEditorApp.SearchFocus.WORD;
            case WORD -> TerminalEditorApp.SearchFocus.WILDCARD;
            case WILDCARD -> TerminalEditorApp.SearchFocus.ALL;
            case ALL -> TerminalEditorApp.SearchFocus.REPLACE;
            case REPLACE -> TerminalEditorApp.SearchFocus.NEXT_BUTTON;
            case NEXT_BUTTON -> TerminalEditorApp.SearchFocus.PREV_BUTTON;
            case PREV_BUTTON -> TerminalEditorApp.SearchFocus.REPLACE_BUTTON;
            case REPLACE_BUTTON -> TerminalEditorApp.SearchFocus.ALL_BUTTON;
            case ALL_BUTTON -> TerminalEditorApp.SearchFocus.FIND;
        };
    }

    static TerminalEditorApp.SearchFocus previousSearchFocusHorizontal(TerminalEditorApp.SearchFocus focus) {
        return switch (focus) {
            case FIND -> TerminalEditorApp.SearchFocus.ALL_BUTTON;
            case CASE -> TerminalEditorApp.SearchFocus.FIND;
            case WORD -> TerminalEditorApp.SearchFocus.CASE;
            case WILDCARD -> TerminalEditorApp.SearchFocus.WORD;
            case ALL -> TerminalEditorApp.SearchFocus.WILDCARD;
            case REPLACE -> TerminalEditorApp.SearchFocus.ALL;
            case NEXT_BUTTON -> TerminalEditorApp.SearchFocus.REPLACE;
            case PREV_BUTTON -> TerminalEditorApp.SearchFocus.NEXT_BUTTON;
            case REPLACE_BUTTON -> TerminalEditorApp.SearchFocus.PREV_BUTTON;
            case ALL_BUTTON -> TerminalEditorApp.SearchFocus.REPLACE_BUTTON;
        };
    }

    static TerminalEditorApp.SearchFocus verticalSearchFocus(TerminalEditorApp.SearchFocus focus, boolean up) {
        return switch (focus) {
            case FIND -> up ? TerminalEditorApp.SearchFocus.REPLACE : TerminalEditorApp.SearchFocus.REPLACE;
            case CASE -> up ? TerminalEditorApp.SearchFocus.NEXT_BUTTON : TerminalEditorApp.SearchFocus.NEXT_BUTTON;
            case WORD -> up ? TerminalEditorApp.SearchFocus.PREV_BUTTON : TerminalEditorApp.SearchFocus.PREV_BUTTON;
            case WILDCARD -> up ? TerminalEditorApp.SearchFocus.REPLACE_BUTTON : TerminalEditorApp.SearchFocus.REPLACE_BUTTON;
            case ALL -> up ? TerminalEditorApp.SearchFocus.ALL_BUTTON : TerminalEditorApp.SearchFocus.ALL_BUTTON;
            case REPLACE -> up ? TerminalEditorApp.SearchFocus.FIND : TerminalEditorApp.SearchFocus.FIND;
            case NEXT_BUTTON -> up ? TerminalEditorApp.SearchFocus.CASE : TerminalEditorApp.SearchFocus.CASE;
            case PREV_BUTTON -> up ? TerminalEditorApp.SearchFocus.WORD : TerminalEditorApp.SearchFocus.WORD;
            case REPLACE_BUTTON -> up ? TerminalEditorApp.SearchFocus.WILDCARD : TerminalEditorApp.SearchFocus.WILDCARD;
            case ALL_BUTTON -> up ? TerminalEditorApp.SearchFocus.ALL : TerminalEditorApp.SearchFocus.ALL;
        };
    }
}
