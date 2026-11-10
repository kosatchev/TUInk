package dev.tuink.core;

import dev.tuink.editor.search.SearchOptions;

import java.util.List;
import java.util.Objects;

/**
 * Search utilities that work across tab sessions.
 */
public final class TabSearch {
    private TabSearch() {
    }

    /** Publishes a resolved match through the caller's registered document/view boundary. */
    @FunctionalInterface
    public interface SelectionSink {
        void select(TabSession tab, Position cursor, Position anchor);
    }

    private static final SelectionSink CORE_SELECTION = (tab, cursor, anchor) ->
        tab.engine().restoreSelection(cursor, anchor);

    public static int countMatches(List<TabSession> tabs, String query, SearchOptions options) {
        return countMatches(tabs, query, options, SearchWorkBudget.current());
    }

    public static int countMatches(List<TabSession> tabs, String query, SearchOptions options,
                                  SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        if (tabs == null || query == null || query.isEmpty()) {
            return 0;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        SearchMatcher matcher = SearchMatcher.compile(query, safe, context);
        int total = 0;
        for (TabSession tab : tabs) {
            TextModel model = tab.model();
            for (int i = 0; i < model.lineCount(); i++) {
                total += matcher.countMatches(model.lineText(i), context);
            }
        }
        return total;
    }

    public static int countMatchesInModel(TextModel model, String query, SearchOptions options) {
        return countMatchesInModel(model, query, options, SearchWorkBudget.current());
    }

    public static int countMatchesInModel(TextModel model, String query, SearchOptions options,
                                          SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        if (model == null || query == null || query.isEmpty()) {
            return 0;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        SearchMatcher matcher = SearchMatcher.compile(query, safe, context);
        int total = 0;
        for (int i = 0; i < model.lineCount(); i++) {
            total += matcher.countMatches(model.lineText(i), context);
        }
        return total;
    }

    public static int[] findMatchTabs(List<TabSession> tabs, String query, SearchOptions options) {
        return findMatchTabs(tabs, query, options, SearchWorkBudget.current());
    }

    public static int[] findMatchTabs(List<TabSession> tabs, String query, SearchOptions options,
                                      SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        if (tabs == null) {
            return new int[0];
        }
        int[] flags = new int[tabs.size()];
        if (query == null || query.isEmpty()) {
            return flags;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        SearchMatcher matcher = SearchMatcher.compile(query, safe, context);
        for (int tabIndex = 0; tabIndex < tabs.size(); tabIndex++) {
            TextModel model = tabs.get(tabIndex).model();
            for (int line = 0; line < model.lineCount(); line++) {
                if (matcher.findFirstAtOrAfter(model.lineText(line), 0, context) != null) {
                    flags[tabIndex] = 1;
                    break;
                }
            }
        }
        return flags;
    }

    public static int findNextAcrossTabs(List<TabSession> tabs, int activeTabIndex, String query, SearchOptions options) {
        return findNextAcrossTabs(tabs, activeTabIndex, query, options, SearchWorkBudget.current());
    }

    public static int findNextAcrossTabs(List<TabSession> tabs, int activeTabIndex, String query, SearchOptions options,
                                         SelectionSink selection) {
        return findNextAcrossTabs(tabs, activeTabIndex, query, options, SearchWorkBudget.current(), selection);
    }

    public static int findNextAcrossTabs(List<TabSession> tabs, int activeTabIndex, String query, SearchOptions options,
                                         SearchOperationContext context) {
        return findNextAcrossTabs(tabs, activeTabIndex, query, options, context, CORE_SELECTION);
    }

    public static int findNextAcrossTabs(List<TabSession> tabs, int activeTabIndex, String query, SearchOptions options,
                                         SearchOperationContext context, SelectionSink selection) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(context, "context");
        if (tabs == null || tabs.isEmpty() || query == null || query.isEmpty()) {
            return -1;
        }
        int safeActive = Math.max(0, Math.min(activeTabIndex, tabs.size() - 1));
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        TabSession active = tabs.get(safeActive);
        SearchNavigation navigation = active.engine().searchNavigation(query, safe, context);
        Position from = forwardOrigin(active, navigation, context);
        MatchPosition activeTail = firstAtOrAfter(active, navigation, from, context);
        if (activeTail != null) {
            select(selection, active, activeTail);
            return safeActive;
        }
        for (int step = 1; step < tabs.size(); step++) {
            int idx = Math.floorMod(safeActive + step, tabs.size());
            TabSession tab = tabs.get(idx);
            MatchPosition match = firstMatch(tab, navigation, context);
            if (match != null) {
                select(selection, tab, match);
                return idx;
            }
        }
        MatchPosition wrapped = firstBefore(active, navigation, from, context);
        if (wrapped != null) {
            select(selection, active, wrapped);
            return safeActive;
        }
        return -1;
    }

    public static int findPreviousAcrossTabs(List<TabSession> tabs, int activeTabIndex, String query, SearchOptions options) {
        return findPreviousAcrossTabs(tabs, activeTabIndex, query, options, SearchWorkBudget.current());
    }

    public static int findPreviousAcrossTabs(List<TabSession> tabs, int activeTabIndex, String query, SearchOptions options,
                                             SelectionSink selection) {
        return findPreviousAcrossTabs(tabs, activeTabIndex, query, options, SearchWorkBudget.current(), selection);
    }

    public static int findPreviousAcrossTabs(List<TabSession> tabs, int activeTabIndex, String query, SearchOptions options,
                                             SearchOperationContext context) {
        return findPreviousAcrossTabs(tabs, activeTabIndex, query, options, context, CORE_SELECTION);
    }

    public static int findPreviousAcrossTabs(List<TabSession> tabs, int activeTabIndex, String query, SearchOptions options,
                                         SearchOperationContext context, SelectionSink selection) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(context, "context");
        if (tabs == null || tabs.isEmpty() || query == null || query.isEmpty()) {
            return -1;
        }
        int safeActive = Math.max(0, Math.min(activeTabIndex, tabs.size() - 1));
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        TabSession active = tabs.get(safeActive);
        SearchNavigation navigation = active.engine().searchNavigation(query, safe, context);
        Position from = backwardOrigin(active, navigation, context);
        MatchPosition activeHead = lastBefore(active, navigation, from, context);
        if (activeHead != null) {
            select(selection, active, activeHead);
            return safeActive;
        }
        for (int step = 1; step < tabs.size(); step++) {
            int idx = Math.floorMod(safeActive - step, tabs.size());
            TabSession tab = tabs.get(idx);
            MatchPosition match = lastMatch(tab, navigation, context);
            if (match != null) {
                select(selection, tab, match);
                return idx;
            }
        }
        MatchPosition wrapped = lastAtOrAfter(active, navigation, from, context);
        if (wrapped != null) {
            select(selection, active, wrapped);
            return safeActive;
        }
        return -1;
    }

    private static Position forwardOrigin(TabSession tab, SearchNavigation navigation, SearchOperationContext context) {
        Selection selection = selection(tab.state());
        if (selection != null && matches(navigation, selection, context)) {
            return selection.start().equals(selection.end())
                ? new Position(selection.end().line(), selection.end().col() + 1) : selection.end();
        }
        return tab.model().clamp(tab.state().getCursor());
    }

    private static Position backwardOrigin(TabSession tab, SearchNavigation navigation, SearchOperationContext context) {
        Selection selection = selection(tab.state());
        if (selection != null && matches(navigation, selection, context)) {
            return selection.start();
        }
        return tab.model().clamp(tab.state().getCursor());
    }

    private static Selection selection(EditorState state) {
        Position anchor = state.getSelectionAnchor();
        if (anchor == null) {
            return null;
        }
        Position cursor = state.getCursor();
        return compare(anchor, cursor) <= 0
            ? new Selection(anchor, cursor)
            : new Selection(cursor, anchor);
    }

    private static boolean matches(
        SearchNavigation navigation,
        Selection selection,
        SearchOperationContext context
    ) {
        return selection.start().line() == selection.end().line()
            && navigation.matchesRange(
                selection.start().line(),
                selection.start().col(),
                selection.end().col(), context
            );
    }

    private static MatchPosition firstMatch(TabSession tab, SearchNavigation query, SearchOperationContext context) {
        TextModel model = tab.model();
        SearchNavigation navigation = tab.engine().searchNavigation(query);
        for (int line = 0; line < model.lineCount(); line++) {
            SearchMatcher.Match match = navigation.firstAtOrAfter(line, 0, context);
            if (match != null) {
                return new MatchPosition(line, match.startUtf16(), match.endUtf16());
            }
        }
        return null;
    }

    private static MatchPosition lastMatch(TabSession tab, SearchNavigation query, SearchOperationContext context) {
        TextModel model = tab.model();
        SearchNavigation navigation = tab.engine().searchNavigation(query);
        for (int line = model.lineCount() - 1; line >= 0; line--) {
            LineText text = model.lineText(line);
            SearchMatcher.Match match = navigation.lastBefore(line, text.length() + 1, context);
            if (match != null) {
                return new MatchPosition(line, match.startUtf16(), match.endUtf16());
            }
        }
        return null;
    }

    private static MatchPosition firstAtOrAfter(
        TabSession tab,
        SearchNavigation query,
        Position from,
        SearchOperationContext context
    ) {
        TextModel model = tab.model();
        SearchNavigation navigation = tab.engine().searchNavigation(query);
        for (int line = from.line(); line < model.lineCount(); line++) {
            SearchMatcher.Match match = navigation.firstAtOrAfter(
                line, line == from.line() ? from.col() : 0, context
            );
            if (match != null) {
                return new MatchPosition(line, match.startUtf16(), match.endUtf16());
            }
        }
        return null;
    }

    private static MatchPosition firstBefore(
        TabSession tab,
        SearchNavigation query,
        Position limit,
        SearchOperationContext context
    ) {
        SearchNavigation navigation = tab.engine().searchNavigation(query);
        for (int line = 0; line <= limit.line(); line++) {
            SearchMatcher.Match match = navigation.firstAtOrAfter(line, 0, context);
            if (match != null && (line < limit.line() || match.startUtf16() < limit.col())) {
                return new MatchPosition(line, match.startUtf16(), match.endUtf16());
            }
        }
        return null;
    }

    private static MatchPosition lastBefore(
        TabSession tab,
        SearchNavigation query,
        Position limit,
        SearchOperationContext context
    ) {
        TextModel model = tab.model();
        SearchNavigation navigation = tab.engine().searchNavigation(query);
        for (int line = limit.line(); line >= 0; line--) {
            LineText text = model.lineText(line);
            SearchMatcher.Match match = navigation.lastBefore(
                line, line == limit.line() ? limit.col() : text.length() + 1, context
            );
            if (match != null) {
                return new MatchPosition(line, match.startUtf16(), match.endUtf16());
            }
        }
        return null;
    }

    private static MatchPosition lastAtOrAfter(
        TabSession tab,
        SearchNavigation query,
        Position limit,
        SearchOperationContext context
    ) {
        TextModel model = tab.model();
        SearchNavigation navigation = tab.engine().searchNavigation(query);
        for (int line = model.lineCount() - 1; line >= limit.line(); line--) {
            LineText text = model.lineText(line);
            SearchMatcher.Match match = navigation.lastBefore(line, text.length() + 1, context);
            if (match != null && (line > limit.line() || match.startUtf16() >= limit.col())) {
                return new MatchPosition(line, match.startUtf16(), match.endUtf16());
            }
        }
        return null;
    }

    private static int compare(Position left, Position right) {
        return left.line() == right.line()
            ? Integer.compare(left.col(), right.col())
            : Integer.compare(left.line(), right.line());
    }

    private static void select(SelectionSink selection, TabSession tab, MatchPosition match) {
        selection.select(tab,
            new Position(match.line(), match.endUtf16()),
            new Position(match.line(), match.startUtf16())
        );
    }

    private record MatchPosition(int line, int startUtf16, int endUtf16) {
    }

    private record Selection(Position start, Position end) {
    }
}
