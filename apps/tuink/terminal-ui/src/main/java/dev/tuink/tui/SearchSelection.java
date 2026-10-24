package dev.tuink.tui;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SearchMatcher;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.TextModel;

/** Distinct selection policies for explicit search, automatic highlighting and navigation. */
final class SearchSelection {
    private static final int AUTO_HIGHLIGHT_MIN_CHARS = 2;
    private static final int AUTO_HIGHLIGHT_MAX_CHARS = 128;
    private static final int PREFILL_MAX_CHARS = 256;

    private SearchSelection() {}

    static String prefill(TextModel model, EditorState state) {
        Range range = range(state);
        return range == null || range.length() > PREFILL_MAX_CHARS
            ? null : model.getTextRange(range.start(), range.end());
    }

    static String highlightQuery(boolean searchActive, String query, TextModel model, EditorState state,
        boolean autoHighlight, boolean mouseSelecting) {
        if (searchActive) {
            return query;
        }
        if (!autoHighlight || mouseSelecting) {
            return null;
        }
        Range range = range(state);
        if (range == null || range.length() < AUTO_HIGHLIGHT_MIN_CHARS || range.length() > AUTO_HIGHLIGHT_MAX_CHARS) {
            return null;
        }
        String selected = model.getTextRange(range.start(), range.end());
        return selected.isBlank() || selected.indexOf('\n') >= 0 ? null : selected;
    }

    static boolean matchesQuery(TextModel model, EditorState state, String query, SearchOptions options) {
        if (query == null || query.isEmpty()) {
            return false;
        }
        Range range = range(state);
        return range != null && SearchMatcher.compile(query, options)
            .matchesRange(model.lineText(range.start().line()), range.start().col(), range.end().col());
    }

    private static Range range(EditorState state) {
        if (!state.hasSelection()) {
            return null;
        }
        Position anchor = state.getSelectionAnchor();
        Position cursor = state.getCursor();
        if (anchor == null || cursor == null || anchor.line() != cursor.line()) {
            return null;
        }
        return anchor.col() <= cursor.col() ? new Range(anchor, cursor) : new Range(cursor, anchor);
    }

    private record Range(Position start, Position end) {
        private int length() { return end.col() - start.col(); }
    }
}
