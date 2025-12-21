package dev.tuink.render;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.TextModel;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds render data from current model and state.
 */
public class RenderPlanBuilder {
    private final LineHighlighter highlighter;
    private static final int TAB_WIDTH = 4;
    private static final Style SELECTION_STYLE = new Style(Color.BLACK, Color.BRIGHT_WHITE, false, false);
    private static final Style SEARCH_MATCH_STYLE = SELECTION_STYLE;
    private static final Style CURRENT_SEARCH_MATCH_STYLE = new Style(Color.BLACK, Color.BRIGHT_YELLOW, false, false);

    public RenderPlanBuilder(LineHighlighter highlighter) {
        this.highlighter = highlighter;
    }

    public RenderPlan build(
        TextModel model,
        EditorState state,
        int rows,
        int cols,
        String topBarText,
        String bottomBarText
    ) {
        return build(model, state, rows, cols, topBarText, bottomBarText, null, SearchOptions.DEFAULT, null, null, null, null, false);
    }

    public RenderPlan build(
        TextModel model,
        EditorState state,
        int rows,
        int cols,
        String topBarText,
        String bottomBarText,
        String searchQuery,
        SearchOptions searchOptions
    ) {
        return build(
            model,
            state,
            rows,
            cols,
            topBarText,
            bottomBarText,
            searchQuery,
            searchOptions,
            null,
            null,
            null,
            null,
            false
        );
    }

    public RenderPlan build(
        TextModel model,
        EditorState state,
        int rows,
        int cols,
        String topBarText,
        String bottomBarText,
        String interactiveBarText,
        Style interactiveBarStyle
    ) {
        return build(
            model,
            state,
            rows,
            cols,
            topBarText,
            bottomBarText,
            null,
            SearchOptions.DEFAULT,
            interactiveBarText,
            interactiveBarStyle,
            null,
            null,
            false
        );
    }

    public RenderPlan build(
        TextModel model,
        EditorState state,
        int rows,
        int cols,
        String topBarText,
        String bottomBarText,
        String searchQuery,
        SearchOptions searchOptions,
        String interactiveBarText,
        Style interactiveBarStyle,
        String notificationBarText,
        Style notificationBarStyle
    ) {
        return build(
            model,
            state,
            rows,
            cols,
            topBarText,
            bottomBarText,
            searchQuery,
            searchOptions,
            interactiveBarText,
            interactiveBarStyle,
            notificationBarText,
            notificationBarStyle,
            false,
            false
        );
    }

    public RenderPlan build(
        TextModel model,
        EditorState state,
        int rows,
        int cols,
        String topBarText,
        String bottomBarText,
        String searchQuery,
        SearchOptions searchOptions,
        String interactiveBarText,
        Style interactiveBarStyle,
        String notificationBarText,
        Style notificationBarStyle,
        boolean wrapLines
    ) {
        return build(
            model,
            state,
            rows,
            cols,
            topBarText,
            bottomBarText,
            searchQuery,
            searchOptions,
            interactiveBarText,
            interactiveBarStyle,
            notificationBarText,
            notificationBarStyle,
            wrapLines,
            false
        );
    }

    public RenderPlan build(
        TextModel model,
        EditorState state,
        int rows,
        int cols,
        String topBarText,
        String bottomBarText,
        String searchQuery,
        SearchOptions searchOptions,
        String interactiveBarText,
        Style interactiveBarStyle,
        String notificationBarText,
        Style notificationBarStyle,
        boolean wrapLines,
        boolean wrapByWords
    ) {
        int safeRows = Math.max(rows, 1);
        int safeCols = Math.max(cols, 1);
        int extraBottomRows = lineCount(interactiveBarText) + lineCount(notificationBarText);
        int contentRows = Math.max(0, safeRows - 2 - extraBottomRows);
        boolean needVerticalScrollbar = state.getTopLine() > 0 || model.lineCount() > contentRows;
        int scrollbarWidth = safeCols >= 8 && needVerticalScrollbar ? 1 : 0;
        int gutterDigits = Math.max(1, String.valueOf(Math.max(model.lineCount(), 1)).length());
        int gutterWidth = gutterDigits + 1;
        int textWidth = Math.max(0, safeCols - gutterWidth - scrollbarWidth);
        int totalColumns = 0;
        for (int i = 0; i < model.lineCount(); i++) {
            String line = model.getLine(i);
            totalColumns = Math.max(totalColumns, visualColumn(line, 0, line.length()));
        }

        List<LineVisual> lines = new ArrayList<>(contentRows);
        if (!wrapLines) {
            for (int row = 0; row < contentRows; row++) {
                int lineIndex = state.getTopLine() + row;
                if (lineIndex < model.lineCount()) {
                    String line = model.getLine(lineIndex);
                    String visibleText = clip(line, state.getLeftColumn(), textWidth);
                    List<StyleSpan> spans = new ArrayList<>(
                        clipSpans(highlighter.highlight(line), state.getLeftColumn(), visibleText.length())
                    );
                    spans.addAll(
                        clipSpans(
                            searchMatchSpans(line, searchQuery, searchOptions, SEARCH_MATCH_STYLE),
                            state.getLeftColumn(),
                            visibleText.length()
                        )
                    );
                    StyleSpan selection = selectionSpanForLine(state, model, lineIndex, state.getLeftColumn(), visibleText.length());
                    if (selection != null) {
                        spans.add(selection);
                    }
                    StyleSpan currentMatch = currentMatchSpanForLine(
                        state,
                        model,
                        lineIndex,
                        state.getLeftColumn(),
                        visibleText.length(),
                        searchQuery,
                        searchOptions
                    );
                    if (currentMatch != null) {
                        spans.add(currentMatch);
                    }
                    String gutter = lineNumberGutter(lineIndex + 1, gutterWidth);
                    lines.add(new LineVisual(gutter, visibleText, spans));
                } else {
                    lines.add(new LineVisual(tildeGutter(gutterWidth), "", List.of()));
                }
            }
        } else {
            int lineIndex = state.getTopLine();
            while (lines.size() < contentRows) {
                if (lineIndex >= model.lineCount()) {
                    lines.add(new LineVisual(tildeGutter(gutterWidth), "", List.of()));
                    continue;
                }
                String line = model.getLine(lineIndex);
                List<StyleSpan> lineSpans = new ArrayList<>(highlighter.highlight(line));
                lineSpans.addAll(searchMatchSpans(line, searchQuery, searchOptions, SEARCH_MATCH_STYLE));
                StyleSpan selection = selectionSpanForLine(state, model, lineIndex, 0, line.length());
                if (selection != null) {
                    lineSpans.add(selection);
                }
                StyleSpan currentMatch = currentMatchSpanForLine(
                    state,
                    model,
                    lineIndex,
                    0,
                    line.length(),
                    searchQuery,
                    searchOptions
                );
                if (currentMatch != null) {
                    lineSpans.add(currentMatch);
                }

                List<Integer> starts = wrapStarts(line, textWidth, wrapByWords);
                for (int seg = 0; seg < starts.size() && lines.size() < contentRows; seg++) {
                    int start = starts.get(seg);
                    int end = seg + 1 < starts.size() ? starts.get(seg + 1) : line.length();
                    String part = line.substring(start, end);
                    List<StyleSpan> partSpans = clipSpans(lineSpans, start, part.length());
                    String gutter = seg == 0
                        ? lineNumberGutter(lineIndex + 1, gutterWidth)
                        : " ".repeat(gutterWidth);
                    lines.add(new LineVisual(gutter, part, partSpans));
                }
                lineIndex++;
            }
        }

        Position cursor = model.clamp(state.getCursor());
        int cursorRow;
        int leftVisualColumn = visualColumn(model.getLine(cursor.line()), 0, state.getLeftColumn());
        int cursorCol;
        if (!wrapLines) {
            cursorRow = cursor.line() - state.getTopLine();
            cursorCol = gutterWidth + visualColumn(model.getLine(cursor.line()), state.getLeftColumn(), cursor.col());
        } else {
            cursorRow = 0;
            for (int i = state.getTopLine(); i < cursor.line(); i++) {
                cursorRow += wrapStarts(model.getLine(i), textWidth, wrapByWords).size();
            }
            String cursorLine = model.getLine(cursor.line());
            List<Integer> starts = wrapStarts(cursorLine, textWidth, wrapByWords);
            int segIndex = 0;
            int segStart = 0;
            for (int i = 0; i < starts.size(); i++) {
                int s = starts.get(i);
                int e = i + 1 < starts.size() ? starts.get(i + 1) : cursorLine.length();
                if (cursor.col() < e || (cursor.col() == e && i + 1 == starts.size())) {
                    segIndex = i;
                    segStart = s;
                    break;
                }
                if (cursor.col() == e && i + 1 < starts.size()) {
                    segIndex = i + 1;
                    segStart = starts.get(i + 1);
                    break;
                }
            }
            cursorRow += segIndex;
            cursorCol = gutterWidth + visualColumn(cursorLine, segStart, cursor.col());
        }
        int maxCursorCol = Math.max(gutterWidth, safeCols - 1 - scrollbarWidth);
        cursorRow = Math.max(0, Math.min(cursorRow, Math.max(contentRows - 1, 0)));
        cursorCol = Math.max(gutterWidth, Math.min(cursorCol, maxCursorCol));

        return new RenderPlan(
            lines,
            topBarText,
            bottomBarText,
            interactiveBarText,
            interactiveBarStyle,
            notificationBarText,
            notificationBarStyle,
            cursorRow,
            cursorCol,
            wrapLines,
            contentRows,
            state.getTopLine(),
            model.lineCount(),
            state.getLeftColumn(),
            leftVisualColumn,
            totalColumns,
            safeCols,
            gutterWidth,
            scrollbarWidth
        );
    }

    private static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static List<Integer> wrapStarts(String line, int maxVisualWidth, boolean wrapByWords) {
        if (line.isEmpty()) {
            return List.of(0);
        }
        if (maxVisualWidth <= 0) {
            return List.of(0);
        }
        List<Integer> starts = new ArrayList<>();
        int start = 0;
        starts.add(0);
        while (start < line.length()) {
            int end = wrapByWords
                ? advanceByWordWrap(line, start, maxVisualWidth)
                : advanceByVisualWidth(line, start, maxVisualWidth);
            if (end <= start) {
                end = Math.min(line.length(), start + 1);
            }
            if (end < line.length()) {
                starts.add(end);
            }
            start = end;
        }
        return starts;
    }

    private static int advanceByVisualWidth(String line, int start, int maxVisualWidth) {
        int visual = 0;
        int i = Math.max(0, Math.min(start, line.length()));
        while (i < line.length()) {
            char ch = line.charAt(i);
            int adv = ch == '\t' ? TAB_WIDTH - (visual % TAB_WIDTH) : 1;
            if (visual + adv > maxVisualWidth) {
                break;
            }
            visual += adv;
            i++;
        }
        return i;
    }

    private static int advanceByWordWrap(String line, int start, int maxVisualWidth) {
        int visual = 0;
        int i = Math.max(0, Math.min(start, line.length()));
        int lastBreakAfterWhitespace = -1;
        while (i < line.length()) {
            char ch = line.charAt(i);
            int adv = ch == '\t' ? TAB_WIDTH - (visual % TAB_WIDTH) : 1;
            if (visual + adv > maxVisualWidth) {
                break;
            }
            visual += adv;
            i++;
            if (Character.isWhitespace(ch)) {
                lastBreakAfterWhitespace = i;
            }
        }
        if (i >= line.length()) {
            return i;
        }
        if (lastBreakAfterWhitespace > start) {
            return lastBreakAfterWhitespace;
        }
        return i;
    }

    private static String clip(String line, int leftColumn, int maxWidth) {
        if (maxWidth <= 0 || leftColumn >= line.length()) {
            return "";
        }
        int start = Math.max(0, leftColumn);
        int end = Math.min(line.length(), start + maxWidth);
        return line.substring(start, end);
    }

    private static List<StyleSpan> clipSpans(List<StyleSpan> spans, int leftColumn, int visibleLength) {
        if (visibleLength <= 0 || spans.isEmpty()) {
            return List.of();
        }

        List<StyleSpan> clipped = new ArrayList<>();
        int windowStart = Math.max(0, leftColumn);
        int windowEnd = windowStart + visibleLength;

        for (StyleSpan span : spans) {
            int start = Math.max(span.start(), windowStart);
            int end = Math.min(span.end(), windowEnd);
            if (end > start) {
                clipped.add(new StyleSpan(start - windowStart, end - windowStart, span.style()));
            }
        }
        return clipped;
    }

    private static int visualColumn(String line, int leftColumn, int col) {
        int start = Math.max(0, Math.min(leftColumn, line.length()));
        int end = Math.max(start, Math.min(col, line.length()));
        int visual = 0;
        for (int i = start; i < end; i++) {
            char ch = line.charAt(i);
            if (ch == '\t') {
                int tabAdvance = TAB_WIDTH - (visual % TAB_WIDTH);
                visual += tabAdvance;
            } else {
                visual++;
            }
        }
        return visual;
    }

    private static String lineNumberGutter(int lineNumber, int gutterWidth) {
        return String.format("%" + (gutterWidth - 1) + "d ", lineNumber);
    }

    private static String tildeGutter(int gutterWidth) {
        return String.format("%" + (gutterWidth - 1) + "s ", "~");
    }

    private List<StyleSpan> searchMatchSpans(String line, String query, SearchOptions options, Style style) {
        if (query == null || query.isEmpty() || line.isEmpty()) {
            return List.of();
        }
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        return findMatches(line, query, safe).stream()
            .map(m -> new StyleSpan(m.start(), m.end(), style))
            .toList();
    }

    private StyleSpan selectionSpanForLine(EditorState state, TextModel model, int lineIndex, int leftColumn, int visibleLength) {
        if (!state.hasSelection() || visibleLength <= 0) {
            return null;
        }
        Position a = state.getSelectionAnchor();
        Position b = state.getCursor();
        if (a.line() > b.line() || (a.line() == b.line() && a.col() > b.col())) {
            Position t = a;
            a = b;
            b = t;
        }
        if (lineIndex < a.line() || lineIndex > b.line()) {
            return null;
        }

        int lineLen = model.getLine(lineIndex).length();
        int start = 0;
        int end = lineLen;
        if (lineIndex == a.line()) {
            start = a.col();
        }
        if (lineIndex == b.line()) {
            end = b.col();
        }
        if (end <= start) {
            return null;
        }

        int visStart = Math.max(start - leftColumn, 0);
        int visEnd = Math.min(end - leftColumn, visibleLength);
        if (visEnd <= visStart) {
            return null;
        }
        return new StyleSpan(visStart, visEnd, SELECTION_STYLE);
    }

    private StyleSpan currentMatchSpanForLine(
        EditorState state,
        TextModel model,
        int lineIndex,
        int leftColumn,
        int visibleLength,
        String query,
        SearchOptions options
    ) {
        if (!state.hasSelection() || query == null || query.isEmpty() || visibleLength <= 0) {
            return null;
        }
        Position a = state.getSelectionAnchor();
        Position b = state.getCursor();
        if (a.line() > b.line() || (a.line() == b.line() && a.col() > b.col())) {
            Position t = a;
            a = b;
            b = t;
        }
        if (a.line() != b.line() || a.line() != lineIndex || b.col() <= a.col()) {
            return null;
        }

        String selected = model.getTextRange(a, b);
        SearchOptions safe = options == null ? SearchOptions.DEFAULT : options;
        if (!selectionMatchesQuery(selected, query, safe)) {
            return null;
        }

        int visStart = Math.max(a.col() - leftColumn, 0);
        int visEnd = Math.min(b.col() - leftColumn, visibleLength);
        if (visEnd <= visStart) {
            return null;
        }
        return new StyleSpan(visStart, visEnd, CURRENT_SEARCH_MATCH_STYLE);
    }

    private static boolean selectionMatchesQuery(String selected, String query, SearchOptions options) {
        String left = options.caseInsensitive() ? selected.toLowerCase(Locale.ROOT) : selected;
        String right = options.caseInsensitive() ? query.toLowerCase(Locale.ROOT) : query;
        return switch (options.matchMode()) {
            case SUBSTRING, WHOLE_WORD -> left.equals(right);
            case WILDCARD -> wildcardPattern(right).matcher(left).matches();
        };
    }

    private static List<LineMatch> findMatches(String line, String query, SearchOptions options) {
        String haystack = options.caseInsensitive() ? line.toLowerCase(Locale.ROOT) : line;
        String needle = options.caseInsensitive() ? query.toLowerCase(Locale.ROOT) : query;
        return switch (options.matchMode()) {
            case SUBSTRING -> findLiteralMatches(haystack, needle, false);
            case WHOLE_WORD -> findLiteralMatches(haystack, needle, true);
            case WILDCARD -> findWildcardMatches(haystack, needle);
        };
    }

    private static List<LineMatch> findLiteralMatches(String text, String needle, boolean wholeWord) {
        if (needle.isEmpty()) {
            return List.of();
        }
        List<LineMatch> matches = new ArrayList<>();
        int at = 0;
        while (at <= text.length() - needle.length()) {
            int idx = text.indexOf(needle, at);
            if (idx < 0) {
                break;
            }
            int end = idx + needle.length();
            if (!wholeWord || isWholeWord(text, idx, end)) {
                matches.add(new LineMatch(idx, end));
            }
            at = end;
        }
        return matches;
    }

    private static List<LineMatch> findWildcardMatches(String text, String wildcard) {
        List<LineMatch> matches = new ArrayList<>();
        Pattern pattern = wildcardPattern(wildcard);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.start() == matcher.end()) {
                break;
            }
            matches.add(new LineMatch(matcher.start(), matcher.end()));
        }
        return matches;
    }

    private static Pattern wildcardPattern(String wildcard) {
        StringBuilder regex = new StringBuilder("(?s)");
        for (int i = 0; i < wildcard.length(); i++) {
            char ch = wildcard.charAt(i);
            if (ch == '*') {
                regex.append(".*");
            } else if (ch == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static boolean isWholeWord(String text, int start, int end) {
        boolean leftOk = start == 0 || !isWordChar(text.charAt(start - 1));
        boolean rightOk = end == text.length() || !isWordChar(text.charAt(end));
        return leftOk && rightOk;
    }

    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private record LineMatch(int start, int end) {
    }
}
