package dev.tuink.core;

import java.util.List;

/**
 * Search utilities that work across tab sessions.
 */
public final class TabSearch {
    private TabSearch() {
    }

    public static int countMatches(List<TabSession> tabs, String query, SearchOptions options) {
        if (tabs == null || query == null || query.isEmpty()) {
            return 0;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        SearchMatcher matcher = SearchMatcher.compile(query, safe);
        int total = 0;
        for (TabSession tab : tabs) {
            TextModel model = tab.model();
            for (int i = 0; i < model.lineCount(); i++) {
                total += matcher.countMatches(model.getLine(i));
            }
        }
        return total;
    }

    public static int countMatchesInModel(TextModel model, String query, SearchOptions options) {
        if (model == null || query == null || query.isEmpty()) {
            return 0;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        SearchMatcher matcher = SearchMatcher.compile(query, safe);
        int total = 0;
        for (int i = 0; i < model.lineCount(); i++) {
            total += matcher.countMatches(model.getLine(i));
        }
        return total;
    }

    public static int[] findMatchTabs(List<TabSession> tabs, String query, SearchOptions options) {
        if (tabs == null) {
            return new int[0];
        }
        int[] flags = new int[tabs.size()];
        if (query == null || query.isEmpty()) {
            return flags;
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        SearchMatcher matcher = SearchMatcher.compile(query, safe);
        for (int tabIndex = 0; tabIndex < tabs.size(); tabIndex++) {
            TextModel model = tabs.get(tabIndex).model();
            for (int line = 0; line < model.lineCount(); line++) {
                if (matcher.findFirstAtOrAfter(model.getLine(line), 0) != null) {
                    flags[tabIndex] = 1;
                    break;
                }
            }
        }
        return flags;
    }

    public static int findNextAcrossTabs(List<TabSession> tabs, int activeTabIndex, String query, SearchOptions options) {
        if (tabs == null || tabs.isEmpty() || query == null || query.isEmpty()) {
            return -1;
        }
        int safeActive = Math.max(0, Math.min(activeTabIndex, tabs.size() - 1));
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        SearchMatcher matcher = SearchMatcher.compile(query, safe);
        TabSession active = tabs.get(safeActive);
        Position from = forwardOrigin(active, matcher);
        MatchPosition activeTail = firstAtOrAfter(active.model(), matcher, from);
        if (activeTail != null) {
            select(active.engine(), activeTail);
            return safeActive;
        }
        for (int step = 1; step < tabs.size(); step++) {
            int idx = Math.floorMod(safeActive + step, tabs.size());
            TabSession tab = tabs.get(idx);
            MatchPosition match = firstMatch(tab.model(), matcher);
            if (match != null) {
                select(tab.engine(), match);
                return idx;
            }
        }
        MatchPosition wrapped = firstBefore(active.model(), matcher, from);
        if (wrapped != null) {
            select(active.engine(), wrapped);
            return safeActive;
        }
        return -1;
    }

    public static int findPreviousAcrossTabs(List<TabSession> tabs, int activeTabIndex, String query, SearchOptions options) {
        if (tabs == null || tabs.isEmpty() || query == null || query.isEmpty()) {
            return -1;
        }
        int safeActive = Math.max(0, Math.min(activeTabIndex, tabs.size() - 1));
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        SearchMatcher matcher = SearchMatcher.compile(query, safe);
        TabSession active = tabs.get(safeActive);
        Position from = backwardOrigin(active, matcher);
        MatchPosition activeHead = lastBefore(active.model(), matcher, from);
        if (activeHead != null) {
            select(active.engine(), activeHead);
            return safeActive;
        }
        for (int step = 1; step < tabs.size(); step++) {
            int idx = Math.floorMod(safeActive - step, tabs.size());
            TabSession tab = tabs.get(idx);
            MatchPosition match = lastMatch(tab.model(), matcher);
            if (match != null) {
                select(tab.engine(), match);
                return idx;
            }
        }
        MatchPosition wrapped = lastAtOrAfter(active.model(), matcher, from);
        if (wrapped != null) {
            select(active.engine(), wrapped);
            return safeActive;
        }
        return -1;
    }

    private static Position forwardOrigin(TabSession tab, SearchMatcher matcher) {
        Selection selection = selection(tab.state());
        if (selection != null && matches(tab.model(), matcher, selection)) {
            return selection.end();
        }
        return tab.model().clamp(tab.state().getCursor());
    }

    private static Position backwardOrigin(TabSession tab, SearchMatcher matcher) {
        Selection selection = selection(tab.state());
        if (selection != null && matches(tab.model(), matcher, selection)) {
            return selection.start();
        }
        return tab.model().clamp(tab.state().getCursor());
    }

    private static Selection selection(EditorState state) {
        Position anchor = state.getSelectionAnchor();
        if (anchor == null || anchor.equals(state.getCursor())) {
            return null;
        }
        Position cursor = state.getCursor();
        return compare(anchor, cursor) <= 0
            ? new Selection(anchor, cursor)
            : new Selection(cursor, anchor);
    }

    private static boolean matches(
        TextModel model,
        SearchMatcher matcher,
        Selection selection
    ) {
        return selection.start().line() == selection.end().line()
            && matcher.matchesRange(
                model.getLine(selection.start().line()),
                selection.start().col(),
                selection.end().col()
            );
    }

    private static MatchPosition firstMatch(TextModel model, SearchMatcher matcher) {
        for (int line = 0; line < model.lineCount(); line++) {
            SearchMatcher.Match match = matcher.findFirstAtOrAfter(model.getLine(line), 0);
            if (match != null) {
                return new MatchPosition(line, match.startUtf16(), match.endUtf16());
            }
        }
        return null;
    }

    private static MatchPosition lastMatch(TextModel model, SearchMatcher matcher) {
        for (int line = model.lineCount() - 1; line >= 0; line--) {
            String text = model.getLine(line);
            SearchMatcher.Match match = matcher.findLastBefore(text, text.length());
            if (match != null) {
                return new MatchPosition(line, match.startUtf16(), match.endUtf16());
            }
        }
        return null;
    }

    private static MatchPosition firstAtOrAfter(
        TextModel model,
        SearchMatcher matcher,
        Position from
    ) {
        for (int line = from.line(); line < model.lineCount(); line++) {
            SearchMatcher.Match match = matcher.findFirstAtOrAfter(
                model.getLine(line), line == from.line() ? from.col() : 0
            );
            if (match != null) {
                return new MatchPosition(line, match.startUtf16(), match.endUtf16());
            }
        }
        return null;
    }

    private static MatchPosition firstBefore(
        TextModel model,
        SearchMatcher matcher,
        Position limit
    ) {
        for (int line = 0; line <= limit.line(); line++) {
            SearchMatcher.Match match = matcher.findFirstAtOrAfter(model.getLine(line), 0);
            if (match != null && (line < limit.line() || match.startUtf16() < limit.col())) {
                return new MatchPosition(line, match.startUtf16(), match.endUtf16());
            }
        }
        return null;
    }

    private static MatchPosition lastBefore(
        TextModel model,
        SearchMatcher matcher,
        Position limit
    ) {
        for (int line = limit.line(); line >= 0; line--) {
            String text = model.getLine(line);
            SearchMatcher.Match match = matcher.findLastBefore(
                text, line == limit.line() ? limit.col() : text.length()
            );
            if (match != null) {
                return new MatchPosition(line, match.startUtf16(), match.endUtf16());
            }
        }
        return null;
    }

    private static MatchPosition lastAtOrAfter(
        TextModel model,
        SearchMatcher matcher,
        Position limit
    ) {
        for (int line = model.lineCount() - 1; line >= limit.line(); line--) {
            String text = model.getLine(line);
            SearchMatcher.Match match = matcher.findLastBefore(text, text.length());
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

    private static void select(EditorEngine engine, MatchPosition match) {
        engine.restoreSelection(
            new Position(match.line(), match.endUtf16()),
            new Position(match.line(), match.startUtf16())
        );
    }

    private record MatchPosition(int line, int startUtf16, int endUtf16) {
    }

    private record Selection(Position start, Position end) {
    }
}
