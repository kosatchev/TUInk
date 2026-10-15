package dev.tuink.tui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.TabSearch;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.SearchBarView;
import dev.tuink.tui.ui.ViewportController.CursorPolicy;
import java.util.List;

/** Search operations on documents; field editing stays in the control router. */
final class SearchCommandHandler {
    private SearchCommandHandler() {}

    record Context(TerminalEditorApp host, List<TerminalEditorApp.EditorTab> tabs, int activeTabIndex,
        UiSessionState ui, SearchController.SearchUiOptions options, String query, String replacement,
        SearchBarView.Placement placement, int rows, int columns) {}

    record Result(int activeTabIndex, CursorPolicy cursorPolicy, String status,
        InputActionDispatcher.StatusLevel statusLevel) {}

    static Result execute(TuinkCommand command, Context context) {
        var current = context.tabs().get(context.activeTabIndex());
        EditorEngine engine = current.tabSession().engine();
        SearchOptions options = SearchController.toSearchOptions(context.options());
        boolean allTabs = context.options().allTabs();
        int activeTabIndex = context.activeTabIndex();
        CursorPolicy policy = CursorPolicy.PRESERVE;
        String status;
        var level = InputActionDispatcher.StatusLevel.INFO;
        if (command == TuinkCommand.CURSOR_PAGE_UP || command == TuinkCommand.CURSOR_PAGE_DOWN) {
            TerminalEditorApp.scrollDocumentViewport(current.tabSession().model(), engine.textLayout(), current.state(),
                context.rows(), context.columns(), context.ui().wrapLinesSetting, context.ui().wrapWordsSetting,
                (command == TuinkCommand.CURSOR_PAGE_UP ? -1 : 1) * Math.max(1, context.rows()),
                context.ui().tabWidthSetting);
            return new Result(activeTabIndex, CursorPolicy.PAGE_SCROLL, null, level);
        }
        SearchFocus focus = switch (command) {
            case SEARCH_NEXT -> SearchFocus.NEXT_BUTTON;
            case SEARCH_PREVIOUS -> SearchFocus.PREV_BUTTON;
            case SEARCH_REPLACE -> SearchFocus.REPLACE_BUTTON;
            case SEARCH_REPLACE_ALL -> SearchFocus.ALL_BUTTON;
            default -> throw new IllegalArgumentException("Not a document search command: " + command);
        };
        if (SearchController.isSearchActionFocus(context.host().searchFocusManager.focused().orElse(null))) {
            context.host().focusSearch(context.placement(), focus);
        }
        switch (command) {
            case SEARCH_NEXT, SEARCH_PREVIOUS -> {
                boolean next = command == TuinkCommand.SEARCH_NEXT;
                boolean found = next && context.host().focusCurrentMatchIfOffscreen(current.tabSession().model(), current.state(),
                    engine, context.rows(), context.columns(), context.ui().wrapLinesSetting,
                    context.ui().wrapWordsSetting, context.query(), options);
                if (!found && allTabs) {
                    int foundTab = next
                        ? TabSearch.findNextAcrossTabs(context.host().toTabSessions(context.tabs()), activeTabIndex, context.query(), options)
                        : TabSearch.findPreviousAcrossTabs(context.host().toTabSessions(context.tabs()), activeTabIndex, context.query(), options);
                    found = foundTab >= 0;
                    if (found) activeTabIndex = foundTab;
                } else if (!found) {
                    found = next ? engine.findNext(context.query(), options) : engine.findPrevious(context.query(), options);
                }
                if (found) policy = CursorPolicy.FOLLOW_CURSOR;
                status = found ? next ? "Found next match" : "Found previous match" : "Not found";
            }
            case SEARCH_REPLACE, SEARCH_REPLACE_ALL -> {
                if (context.ui().readingModeSetting) {
                    status = "Reading mode: replace is disabled";
                } else if (command == TuinkCommand.SEARCH_REPLACE) {
                    long before = current.tabSession().model().revision();
                    var cursor = current.state().getCursor();
                    var anchor = current.state().getSelectionAnchor();
                    boolean found = engine.replaceCurrentOrNext(context.query(), context.replacement(), options);
                    boolean changed = before != current.tabSession().model().revision();
                    if (changed || !cursor.equals(current.state().getCursor())
                        || !java.util.Objects.equals(anchor, current.state().getSelectionAnchor())) policy = CursorPolicy.FOLLOW_CURSOR;
                    status = changed ? "Replaced match" : found ? "Match found; text unchanged" : "Nothing to replace";
                } else {
                    long before = current.tabSession().model().revision();
                    int matched = engine.replaceAll(context.query(), context.replacement(), options);
                    if (before != current.tabSession().model().revision()) {
                        policy = CursorPolicy.FOLLOW_CURSOR;
                        status = "Matched " + matched + " occurrence(s); replacement applied. Undo available: Ctrl+Z";
                        level = InputActionDispatcher.StatusLevel.WARNING;
                    } else {
                        status = matched > 0 ? "Found " + matched + " occurrence(s); text unchanged" : "Nothing to replace";
                    }
                }
            }
            default -> throw new IllegalArgumentException("Not a document search command: " + command);
        }
        return new Result(activeTabIndex, policy, status, level);
    }
}
