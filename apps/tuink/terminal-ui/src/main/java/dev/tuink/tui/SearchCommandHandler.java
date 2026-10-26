package dev.tuink.tui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.DocumentEditLimitException;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SearchLimitException;
import dev.tuink.core.SearchWorkBudget;
import dev.tuink.core.TabSession;
import dev.tuink.core.TabSearch;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.ViewportController.CursorPolicy;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Search operations on documents; field editing stays in the control router. */
final class SearchCommandHandler {
    private SearchCommandHandler() {}

    record Context(List<TabSession> tabs, int activeTabIndex, boolean readingMode,
        SearchController.SearchUiOptions options, String query, String replacement) {}

    record Result(int activeTabIndex, int matchedCount, boolean documentChanged, CursorPolicy cursorPolicy, String status,
        InputActionDispatcher.StatusLevel statusLevel) {}

    static Result execute(TuinkCommand command, Context context, BooleanSupplier revealCurrentSelection) {
        try {
            return SearchWorkBudget.run(SearchPresentation.INTERACTIVE_WILDCARD_WORK_LIMIT,
                () -> executeSearch(command, context, revealCurrentSelection));
        } catch (DocumentEditLimitException | SearchLimitException limit) {
            return new Result(context.activeTabIndex(), 0, false, CursorPolicy.PRESERVE,
                limit.getMessage(), InputActionDispatcher.StatusLevel.ERROR);
        }
    }

    private static Result executeSearch(TuinkCommand command, Context context, BooleanSupplier revealCurrentSelection) {
        var current = context.tabs().get(context.activeTabIndex());
        EditorEngine engine = current.engine();
        SearchOptions options = SearchController.toSearchOptions(context.options());
        boolean allTabs = context.options().allTabs();
        int activeTabIndex = context.activeTabIndex();
        int matchedCount = 0;
        boolean documentChanged = false;
        CursorPolicy policy = CursorPolicy.PRESERVE;
        String status;
        var level = InputActionDispatcher.StatusLevel.INFO;
        switch (command) {
            case SEARCH_NEXT, SEARCH_PREVIOUS -> {
                boolean next = command == TuinkCommand.SEARCH_NEXT;
                boolean found = next && SearchSelection.matchesQuery(current.model(), current.state(), context.query(), options)
                    && revealCurrentSelection.getAsBoolean();
                if (!found && allTabs) {
                    int foundTab = next
                        ? TabSearch.findNextAcrossTabs(context.tabs(), activeTabIndex, context.query(), options)
                        : TabSearch.findPreviousAcrossTabs(context.tabs(), activeTabIndex, context.query(), options);
                    found = foundTab >= 0;
                    if (found) activeTabIndex = foundTab;
                } else if (!found) {
                    found = next ? engine.findNext(context.query(), options) : engine.findPrevious(context.query(), options);
                }
                if (found) policy = CursorPolicy.FOLLOW_CURSOR;
                matchedCount = found ? 1 : 0;
                status = found ? next ? "Found next match" : "Found previous match" : "Not found";
            }
            case SEARCH_REPLACE, SEARCH_REPLACE_ALL -> {
                if (context.readingMode()) {
                    status = "Reading mode: replace is disabled";
                } else if (command == TuinkCommand.SEARCH_REPLACE) {
                    long before = current.model().revision();
                    var cursor = current.state().getCursor();
                    var anchor = current.state().getSelectionAnchor();
                    boolean found = engine.replaceCurrentOrNext(context.query(), context.replacement(), options);
                    boolean changed = before != current.model().revision();
                    matchedCount = found ? 1 : 0;
                    documentChanged = changed;
                    if (changed || !cursor.equals(current.state().getCursor())
                        || !java.util.Objects.equals(anchor, current.state().getSelectionAnchor())) policy = CursorPolicy.FOLLOW_CURSOR;
                    status = changed ? "Replaced match" : found ? "Match found; text unchanged" : "Nothing to replace";
                } else {
                    long before = current.model().revision();
                    int matched = engine.replaceAll(context.query(), context.replacement(), options);
                    matchedCount = matched;
                    documentChanged = before != current.model().revision();
                    if (documentChanged) {
                        policy = CursorPolicy.FOLLOW_CURSOR;
                        status = "Matched " + matched + " occurrence(s); replacement applied. Undo: Esc, then Ctrl+Z";
                        level = InputActionDispatcher.StatusLevel.WARNING;
                    } else {
                        status = matched > 0 ? "Found " + matched + " occurrence(s); text unchanged" : "Nothing to replace";
                    }
                }
            }
            default -> throw new IllegalArgumentException("Not a document search command: " + command);
        }
        return new Result(activeTabIndex, matchedCount, documentChanged, policy, status, level);
    }
}
