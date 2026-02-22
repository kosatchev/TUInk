package dev.tuink.render;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.TextModel;
import dev.tuink.core.ViewportMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds render data from current model and state.
 */
public class RenderPlanBuilder {
    private static final int MAX_VISUAL_CACHE_LINES = 1024;
    private static final int MAX_VISUAL_CACHE_CHARS_PER_LINE = 8192;
    private static final int MAX_SYNTAX_CACHE_LINES = 1024;
    private final LineHighlighter highlighter;
    private int tabWidth = 4;
    private boolean syntaxHighlightingEnabled = true;
    private long visualCacheRevision = Long.MIN_VALUE;
    private int visualCacheTabWidth = -1;
    private final Map<Integer, LineMetrics> lineMetricsCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, LineMetrics> eldest) {
            return size() > MAX_VISUAL_CACHE_LINES;
        }
    };
    private long syntaxCacheRevision = Long.MIN_VALUE;
    private final Map<Integer, SyntaxSpans> syntaxSpanCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, SyntaxSpans> eldest) {
            return size() > MAX_SYNTAX_CACHE_LINES;
        }
    };
    private static final Style SELECTION_STYLE = new Style(Color.BLACK, Color.BRIGHT_WHITE, false, false);
    private static final Style SEARCH_MATCH_STYLE = new Style(Color.BLACK, Color.BRIGHT_YELLOW, false, false);
    private static final Style CURRENT_SEARCH_MATCH_STYLE = SELECTION_STYLE;

    public RenderPlanBuilder(LineHighlighter highlighter) {
        this.highlighter = highlighter;
    }

    public void setTabWidth(int tabWidth) {
        this.tabWidth = tabWidth <= 2 ? 2 : 4;
    }

    public void setSyntaxHighlightingEnabled(boolean enabled) {
        this.syntaxHighlightingEnabled = enabled;
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
        ensureVisualCache(model);
        ensureSyntaxCache(model);
        int extraBottomRows = lineCount(interactiveBarText);
        int contentRows = Math.max(0, safeRows - 2 - extraBottomRows);
        boolean needVerticalScrollbar = state.getTopLine() > 0 || model.lineCount() > contentRows;
        int scrollbarWidth = safeCols >= 8 && needVerticalScrollbar ? 1 : 0;
        int gutterDigits = Math.max(1, String.valueOf(Math.max(model.lineCount(), 1)).length());
        int gutterWidth = gutterDigits + 1;
        int textWidth = Math.max(0, safeCols - gutterWidth - scrollbarWidth);
        WrapViewport wrapViewport = null;
        if (wrapLines) {
            wrapViewport = resolveWrapViewport(model, state.getTopLine(), state.getTopWrapSegment(), textWidth, wrapByWords);
            int wrappedTotal = totalWrappedRows(model, textWidth, wrapByWords);
            boolean wrappedNeedVertical = wrapViewport.topWrappedOffset() > 0 || wrappedTotal > contentRows;
            int wrappedScrollbarWidth = safeCols >= 8 && wrappedNeedVertical ? 1 : 0;
            if (wrappedScrollbarWidth != scrollbarWidth) {
                scrollbarWidth = wrappedScrollbarWidth;
                textWidth = Math.max(0, safeCols - gutterWidth - scrollbarWidth);
                wrapViewport = resolveWrapViewport(model, state.getTopLine(), state.getTopWrapSegment(), textWidth, wrapByWords);
            }
        }
        // Reuse model-level cache instead of scanning all lines on every frame.
        int totalColumns = model.maxVisualLineWidth(tabWidth);

        List<LineVisual> lines = new ArrayList<>(contentRows);
        if (!wrapLines) {
            int leftVisualColumn = Math.max(0, state.getLeftVisualColumn());
            for (int row = 0; row < contentRows; row++) {
                int lineIndex = state.getTopLine() + row;
                if (lineIndex < model.lineCount()) {
                    String line = model.getLine(lineIndex);
                    VisualSlice slice = visibleSlice(model, lineIndex, line, leftVisualColumn, textWidth);
                    int lineLeftColumn = slice.startCol();
                    String visibleText = slice.text();
                    List<StyleSpan> syntaxSpans = syntaxHighlightingEnabled
                        ? syntaxSpansForLine(model, lineIndex, line)
                        : List.of();
                    List<StyleSpan> spans = new ArrayList<>(clipSpans(syntaxSpans, lineLeftColumn, visibleText.length()));
                    spans.addAll(
                        clipSpans(
                            searchMatchSpans(line, searchQuery, searchOptions, SEARCH_MATCH_STYLE),
                            lineLeftColumn,
                            visibleText.length()
                        )
                    );
                    StyleSpan selection = selectionSpanForLine(state, model, lineIndex, lineLeftColumn, visibleText.length());
                    if (selection != null) {
                        spans.add(selection);
                    }
                    StyleSpan currentMatch = currentMatchSpanForLine(
                        state,
                        model,
                        lineIndex,
                        lineLeftColumn,
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
            int lineIndex = wrapViewport == null ? state.getTopLine() : wrapViewport.lineIndex();
            int firstSegmentOffset = wrapViewport == null ? 0 : wrapViewport.segmentOffset();
            while (lines.size() < contentRows) {
                if (lineIndex >= model.lineCount()) {
                    lines.add(new LineVisual(tildeGutter(gutterWidth), "", List.of()));
                    continue;
                }
                String line = model.getLine(lineIndex);
                List<StyleSpan> lineSpans = new ArrayList<>(
                    syntaxHighlightingEnabled ? syntaxSpansForLine(model, lineIndex, line) : List.of()
                );
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

                List<Integer> starts = wrapStarts(model, lineIndex, textWidth, wrapByWords);
                int segStart = Math.max(0, Math.min(firstSegmentOffset, starts.size() - 1));
                for (int seg = segStart; seg < starts.size() && lines.size() < contentRows; seg++) {
                    int start = starts.get(seg);
                    int end = seg + 1 < starts.size() ? starts.get(seg + 1) : line.length();
                    String part = line.substring(start, end);
                    List<StyleSpan> partSpans = clipSpans(lineSpans, start, part.length());
                    String gutter = seg == 0
                        ? lineNumberGutter(lineIndex + 1, gutterWidth)
                        : " ".repeat(gutterWidth);
                    lines.add(new LineVisual(gutter, part, partSpans));
                }
                firstSegmentOffset = 0;
                lineIndex++;
            }
        }

        Position cursor = model.clamp(state.getCursor());
        int cursorRow;
        int leftVisualColumn = Math.max(0, state.getLeftVisualColumn());
        int cursorLineLeftColumn = columnForVisualColumn(model, cursor.line(), model.getLine(cursor.line()), leftVisualColumn);
        int cursorCol;
        if (!wrapLines) {
            cursorRow = cursor.line() - state.getTopLine();
            cursorCol = gutterWidth + ViewportMetrics.textToVisualColumn(
                model.getLine(cursor.line()),
                cursorLineLeftColumn,
                cursor.col(),
                tabWidth
            );
        } else {
            String cursorLine = model.getLine(cursor.line());
            List<Integer> starts = wrapStarts(model, cursor.line(), textWidth, wrapByWords);
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
            int cursorAbsoluteWrapped = wrappedRowsBefore(model, cursor.line(), textWidth, wrapByWords) + segIndex;
            int topWrapped = wrapViewport == null ? 0 : wrapViewport.topWrappedOffset();
            cursorRow = cursorAbsoluteWrapped - topWrapped;
            cursorCol = gutterWidth + ViewportMetrics.textToVisualColumn(cursorLine, segStart, cursor.col(), tabWidth);
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
            wrapByWords,
            contentRows,
            wrapLines
                ? (wrapViewport == null ? 0 : wrapViewport.topWrappedOffset())
                : state.getTopLine(),
            wrapLines
                ? totalWrappedRows(model, textWidth, wrapByWords)
                : model.lineCount(),
            cursorLineLeftColumn,
            leftVisualColumn,
            totalColumns,
            safeCols,
            gutterWidth,
            scrollbarWidth
        );
    }

    private WrapViewport resolveWrapViewport(
        TextModel model,
        int topLine,
        int segmentOffset,
        int width,
        boolean wrapByWords
    ) {
        int lineCount = Math.max(1, model.lineCount());
        int line = Math.max(0, Math.min(topLine, lineCount - 1));
        int segment = Math.max(0, segmentOffset);
        int topWrapped = wrappedRowsBefore(model, line, width, wrapByWords);
        while (line < lineCount) {
            int segments = wrapStarts(model, line, width, wrapByWords).size();
            if (segment < segments) {
                return new WrapViewport(line, segment, topWrapped + segment);
            }
            segment -= segments;
            topWrapped += segments;
            line++;
        }
        int lastLine = lineCount - 1;
        return new WrapViewport(lastLine, 0, Math.max(0, totalWrappedRows(model, width, wrapByWords) - 1));
    }

    private record WrapViewport(int lineIndex, int segmentOffset, int topWrappedOffset) {
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

    private List<Integer> wrapStarts(TextModel model, int lineIndex, int maxVisualWidth, boolean wrapByWords) {
        return model.wrapStarts(lineIndex, maxVisualWidth, wrapByWords, tabWidth);
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

    private VisualSlice visibleSlice(TextModel model, int lineIndex, String line, int leftVisualColumn, int maxWidth) {
        if (maxWidth <= 0 || line.isEmpty()) {
            return new VisualSlice(0, "");
        }
        int safeLeftVisual = Math.max(0, leftVisualColumn);
        LineMetrics metrics = lineMetrics(model, lineIndex, line);
        if (metrics == null) {
            int start = ViewportMetrics.visualToTextColumn(line, 0, safeLeftVisual, tabWidth);
            int end = start;
            int visual = 0;
            while (end < line.length()) {
                char ch = line.charAt(end);
                int advance = ch == '\t' ? tabWidth - (visual % tabWidth) : 1;
                if (visual + advance > maxWidth) {
                    break;
                }
                visual += advance;
                end++;
            }
            return new VisualSlice(start, line.substring(start, end));
        }
        int start = columnForVisualWithMetrics(metrics, safeLeftVisual);
        int[] prefix = metrics.visualPrefix();
        int baseVisual = prefix[start];
        int maxVisual = baseVisual + maxWidth;
        int end = upperBound(prefix, maxVisual) - 1;
        end = Math.max(start, Math.min(end, line.length()));
        return new VisualSlice(start, line.substring(start, end));
    }

    private int columnForVisualColumn(TextModel model, int lineIndex, String line, int targetVisual) {
        LineMetrics metrics = lineMetrics(model, lineIndex, line);
        if (metrics == null) {
            return ViewportMetrics.visualToTextColumn(line, 0, targetVisual, tabWidth);
        }
        return columnForVisualWithMetrics(metrics, Math.max(0, targetVisual));
    }

    private int columnForVisualWithMetrics(LineMetrics metrics, int targetVisual) {
        int[] prefix = metrics.visualPrefix();
        int atOrAfter = upperBound(prefix, targetVisual);
        if (atOrAfter <= 1) {
            return 0;
        }
        if (atOrAfter > prefix.length) {
            return prefix.length - 1;
        }
        // Floor semantics are required for viewport-left mapping and cursor-follow.
        return atOrAfter - 1;
    }

    private LineMetrics lineMetrics(TextModel model, int lineIndex, String line) {
        if (line.length() > MAX_VISUAL_CACHE_CHARS_PER_LINE) {
            return null;
        }
        LineMetrics cached = lineMetricsCache.get(lineIndex);
        if (cached != null && cached.lineText().equals(line)) {
            return cached;
        }
        int[] prefix = new int[line.length() + 1];
        int visual = 0;
        prefix[0] = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            int advance = ch == '\t' ? tabWidth - (visual % tabWidth) : 1;
            visual += advance;
            prefix[i + 1] = visual;
        }
        LineMetrics computed = new LineMetrics(line, prefix);
        lineMetricsCache.put(lineIndex, computed);
        return computed;
    }

    private void ensureVisualCache(TextModel model) {
        long revision = model.revision();
        if (visualCacheRevision == revision && visualCacheTabWidth == tabWidth) {
            return;
        }
        lineMetricsCache.clear();
        visualCacheRevision = revision;
        visualCacheTabWidth = tabWidth;
    }

    private void ensureSyntaxCache(TextModel model) {
        long revision = model.revision();
        if (syntaxCacheRevision == revision) {
            return;
        }
        syntaxSpanCache.clear();
        syntaxCacheRevision = revision;
    }

    private List<StyleSpan> syntaxSpansForLine(TextModel model, int lineIndex, String line) {
        SyntaxSpans cached = syntaxSpanCache.get(lineIndex);
        if (cached != null && cached.lineText().equals(line)) {
            return cached.spans();
        }
        List<StyleSpan> spans = List.copyOf(highlighter.highlight(line));
        syntaxSpanCache.put(lineIndex, new SyntaxSpans(line, spans));
        return spans;
    }

    private static int lowerBound(int[] arr, int target) {
        int lo = 0;
        int hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int upperBound(int[] arr, int target) {
        int lo = 0;
        int hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] <= target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private int wrappedRowsBefore(TextModel model, int lineIndexExclusive, int width, boolean wrapByWords) {
        return model.wrappedRowsBefore(lineIndexExclusive, width, wrapByWords, tabWidth);
    }

    private int totalWrappedRows(TextModel model, int width, boolean wrapByWords) {
        return model.totalWrappedRows(width, wrapByWords, tabWidth);
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

    private record LineMetrics(String lineText, int[] visualPrefix) {
    }

    private record VisualSlice(int startCol, String text) {
    }

    private record SyntaxSpans(String lineText, List<StyleSpan> spans) {
    }
}
