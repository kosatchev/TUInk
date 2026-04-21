package dev.tuink.render;

import dev.tuink.core.EditorState;
import dev.tuink.core.EditorViewportBehavior;
import dev.tuink.core.DisplayText;
import dev.tuink.core.GutterDiffFlag;
import dev.tuink.core.GutterDiffMarker;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.TextModel;
import dev.tuink.core.ViewportMetrics;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds render data from current model and state.
 */
public class RenderPlanBuilder {
    private static final int MAX_SYNTAX_CACHE_LINES = 1024;
    private static final int MAX_BRACKET_SCAN_CHARS = 200_000;
    private static final int MAX_UNMATCHED_SCAN_CHARS = 1_000_000;
    private final LineHighlighter highlighter;
    private int tabWidth = 4;
    private boolean syntaxHighlightingEnabled = true;
    private boolean bracketMatchingEnabled = true;
    private boolean rainbowBracketsEnabled = true;
    private IntFunction<GutterDiffMarker> gutterDiffResolver = line -> GutterDiffMarker.NONE;
    private CommentSyntaxProfile commentSyntaxProfile = CommentSyntaxProfile.GENERIC;
    private long syntaxCacheRevision = Long.MIN_VALUE;
    private final Map<Integer, SyntaxSpans> syntaxSpanCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, SyntaxSpans> eldest) {
            return size() > MAX_SYNTAX_CACHE_LINES;
        }
    };
    private long quoteMaskCacheRevision = Long.MIN_VALUE;
    private CommentSyntaxProfile quoteMaskCacheProfile = CommentSyntaxProfile.GENERIC;
    private final Map<Integer, QuoteMask> quoteMaskCache = new HashMap<>();
    private int quoteMaskComputedUntil = -1;
    private QuoteContinuationState quoteMaskContinuationState = QuoteContinuationState.NONE;
    private long bracketCacheRevision = Long.MIN_VALUE;
    private Position bracketCacheCursor;
    private BracketPair bracketCachePair;
    private boolean bracketCacheRainbowEnabled;
    private long unmatchedCacheRevision = Long.MIN_VALUE;
    private Map<Integer, List<Integer>> unmatchedBracketColumnsByLine = Map.of();
    private Map<Integer, List<StyleSpan>> rainbowBracketSpansByLine = Map.of();
    private Style selectionStyle = Style.DEFAULT;
    private Style searchMatchStyle = Style.DEFAULT;
    private Style currentSearchMatchStyle = Style.DEFAULT;
    private Style bracketMatchStyle = Style.DEFAULT;
    private Style quoteMatchStyle = Style.DEFAULT;
    private Style unmatchedBracketStyle = Style.DEFAULT;
    private List<Style> rainbowBracketStyles = List.of(Style.DEFAULT);

    public RenderPlanBuilder(LineHighlighter highlighter) {
        this.highlighter = highlighter;
    }

    public void setTabWidth(int tabWidth) {
        int normalized = tabWidth <= 2 ? 2 : 4;
        if (this.tabWidth == normalized) {
            return;
        }
        this.tabWidth = normalized;
    }

    public void setSyntaxHighlightingEnabled(boolean enabled) {
        this.syntaxHighlightingEnabled = enabled;
    }

    public void setBracketMatchingEnabled(boolean enabled) {
        this.bracketMatchingEnabled = enabled;
    }

    public void setRainbowBracketsEnabled(boolean enabled) {
        this.rainbowBracketsEnabled = enabled;
        this.bracketCacheRevision = Long.MIN_VALUE;
    }

    public void setGutterDiffResolver(IntFunction<GutterDiffMarker> resolver) {
        this.gutterDiffResolver = resolver == null ? line -> GutterDiffMarker.NONE : resolver;
    }

    public void setCommentSyntaxProfile(CommentSyntaxProfile profile) {
        CommentSyntaxProfile next = profile == null ? CommentSyntaxProfile.GENERIC : profile;
        if (this.commentSyntaxProfile == next) {
            return;
        }
        this.commentSyntaxProfile = next;
        this.quoteMaskCacheRevision = Long.MIN_VALUE;
        this.unmatchedCacheRevision = Long.MIN_VALUE;
        this.bracketCacheRevision = Long.MIN_VALUE;
    }

    public void setDecorationStyles(
        Style selectionStyle,
        Style searchMatchStyle,
        Style currentSearchMatchStyle,
        Style bracketMatchStyle,
        Style quoteMatchStyle,
        Style unmatchedBracketStyle,
        List<Style> rainbowBracketStyles
    ) {
        this.selectionStyle = selectionStyle == null ? Style.DEFAULT : selectionStyle;
        this.searchMatchStyle = searchMatchStyle == null ? Style.DEFAULT : searchMatchStyle;
        this.currentSearchMatchStyle = currentSearchMatchStyle == null ? Style.DEFAULT : currentSearchMatchStyle;
        this.bracketMatchStyle = bracketMatchStyle == null ? Style.DEFAULT : bracketMatchStyle;
        this.quoteMatchStyle = quoteMatchStyle == null ? Style.DEFAULT : quoteMatchStyle;
        this.unmatchedBracketStyle = unmatchedBracketStyle == null ? Style.DEFAULT : unmatchedBracketStyle;
        this.rainbowBracketStyles = rainbowBracketStyles == null || rainbowBracketStyles.isEmpty()
            ? List.of(Style.DEFAULT)
            : List.copyOf(rainbowBracketStyles);
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
        EditorLayout layout = EditorLayout.resolve(
            model,
            state,
            rows,
            cols,
            1,
            lineCount(interactiveBarText),
            wrapLines,
            wrapByWords,
            tabWidth
        );
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
            wrapByWords,
            layout
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
        boolean wrapByWords,
        EditorLayout layout
    ) {
        EditorLayout resolvedLayout = layout == null
            ? EditorLayout.resolve(
                model,
                state,
                rows,
                cols,
                1,
                lineCount(interactiveBarText),
                wrapLines,
                wrapByWords,
                tabWidth
            )
            : layout;
        int safeRows = resolvedLayout.rows();
        int safeCols = resolvedLayout.cols();
        ensureSyntaxCache(model);
        ensureQuoteMaskCache(model);
        int contentRows = resolvedLayout.contentRows();
        int scrollbarWidth = resolvedLayout.scrollbarWidth();
        int gutterWidth = resolvedLayout.gutterWidth();
        int textWidth = resolvedLayout.contentColumns();
        int layoutWidth = Math.max(1, textWidth);
        WrapViewport wrapViewport = null;
        if (wrapLines) {
            wrapViewport = resolveWrapViewport(
                model,
                state.getTopLine(),
                state.getTopWrapSegment(),
                layoutWidth,
                wrapByWords
            );
        }
        // Reuse model-level cache instead of scanning all lines on every frame.
        Position layoutCursor = model.clamp(state.getCursor());
        int cursorExtent = lineMetrics(model, layoutCursor.line())
            .visualColumnAt(layoutCursor.col()) + 1;
        int totalColumns = Math.max(model.maxVisualLineWidth(tabWidth), cursorExtent);
        BracketPair bracketPair = bracketMatchingEnabled ? bracketPairForCursor(model, state) : null;
        if (bracketMatchingEnabled || rainbowBracketsEnabled) {
            ensureBracketDecorationCache(model);
        }

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
                    if (rainbowBracketsEnabled) {
                        spans.addAll(rainbowBracketSpansForLine(lineIndex, lineLeftColumn, visibleText.length()));
                    }
                    if (bracketMatchingEnabled) {
                        spans.addAll(unmatchedBracketSpansForLine(lineIndex, lineLeftColumn, visibleText.length()));
                        spans.addAll(bracketSpansForLine(bracketPair, lineIndex, lineLeftColumn, visibleText.length()));
                    }
                    spans.addAll(
                        clipSpans(
                            searchMatchSpans(line, searchQuery, searchOptions, searchMatchStyle),
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
                    GutterDiffMarker gutterDiff = gutterDiffForLine(lineIndex);
                    String gutter = lineNumberGutter(lineIndex + 1, gutterWidth, gutterDiff.flag());
                    lines.add(
                        new LineVisual(
                            gutter,
                            visibleText,
                            spans,
                            gutterDiff,
                            slice.sourceVisualColumn(),
                            slice.leadingClippedCells()
                        )
                    );
                } else {
                    lines.add(new LineVisual(tildeGutter(gutterWidth), "", List.of(), GutterDiffMarker.NONE));
                }
            }
        } else {
            int lineIndex = wrapViewport == null ? state.getTopLine() : wrapViewport.lineIndex();
            int firstSegmentOffset = wrapViewport == null ? 0 : wrapViewport.segmentOffset();
            while (lines.size() < contentRows) {
                if (lineIndex >= model.lineCount()) {
                    lines.add(new LineVisual(tildeGutter(gutterWidth), "", List.of(), GutterDiffMarker.NONE));
                    continue;
                }
                String line = model.getLine(lineIndex);
                GutterDiffMarker gutterDiff = gutterDiffForLine(lineIndex);
                List<StyleSpan> lineSpans = new ArrayList<>(
                    syntaxHighlightingEnabled ? syntaxSpansForLine(model, lineIndex, line) : List.of()
                );
                if (rainbowBracketsEnabled) {
                    lineSpans.addAll(rainbowBracketSpansForLine(lineIndex, 0, line.length()));
                }
                if (bracketMatchingEnabled) {
                    lineSpans.addAll(unmatchedBracketSpansForLine(lineIndex, 0, line.length()));
                    lineSpans.addAll(bracketSpansForLine(bracketPair, lineIndex, 0, line.length()));
                }
                lineSpans.addAll(searchMatchSpans(line, searchQuery, searchOptions, searchMatchStyle));
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

                List<Integer> starts = wrapStarts(model, lineIndex, layoutWidth, wrapByWords);
                int segStart = Math.max(0, Math.min(firstSegmentOffset, starts.size() - 1));
                for (int seg = segStart; seg < starts.size() && lines.size() < contentRows; seg++) {
                    int start = starts.get(seg);
                    int end = seg + 1 < starts.size() ? starts.get(seg + 1) : line.length();
                    String part = line.substring(start, end);
                    List<StyleSpan> partSpans = clipSpans(lineSpans, start, part.length());
                    String gutter = seg == 0
                        ? lineNumberGutter(lineIndex + 1, gutterWidth, gutterDiff.flag())
                        : " ".repeat(gutterWidth);
                    lines.add(
                        new LineVisual(
                            gutter,
                            part,
                            partSpans,
                            seg == 0 ? gutterDiff : GutterDiffMarker.NONE,
                            lineMetrics(model, lineIndex).visualColumnAt(start),
                            0
                        )
                    );
                }
                firstSegmentOffset = 0;
                lineIndex++;
            }
        }

        Position cursor = model.clamp(state.getCursor());
        String cursorLine = model.getLine(cursor.line());
        DisplayText.Metrics cursorMetrics = lineMetrics(model, cursor.line());
        int cursorRow;
        int leftVisualColumn = Math.max(0, state.getLeftVisualColumn());
        int cursorLineLeftColumn = columnForVisualColumn(model, cursor.line(), leftVisualColumn);
        int cursorCol;
        if (!wrapLines) {
            cursorRow = cursor.line() - state.getTopLine();
            cursorCol = gutterWidth
                + cursorMetrics.visualColumnAt(cursor.col())
                - leftVisualColumn;
        } else {
            List<Integer> starts = wrapStarts(model, cursor.line(), layoutWidth, wrapByWords);
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
            int cursorAbsoluteWrapped = wrappedRowsBefore(model, cursor.line(), layoutWidth, wrapByWords) + segIndex;
            int topWrapped = wrapViewport == null ? 0 : wrapViewport.topWrappedOffset();
            cursorRow = cursorAbsoluteWrapped - topWrapped;
            cursorCol = gutterWidth
                + cursorMetrics.visualColumnAt(cursor.col())
                - cursorMetrics.visualColumnAt(segStart);
        }
        int maxCursorCol = Math.max(0, safeCols - 1 - scrollbarWidth);
        boolean cursorInViewport = contentRows > 0
            && textWidth > 0
            && cursorRow >= 0
            && cursorRow < contentRows
            && cursorCol >= gutterWidth
            && cursorCol <= maxCursorCol;
        int minCursorCol = Math.min(gutterWidth, maxCursorCol);
        cursorRow = Math.max(0, Math.min(cursorRow, Math.max(contentRows - 1, 0)));
        cursorCol = Math.max(minCursorCol, Math.min(cursorCol, maxCursorCol));

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
            cursorInViewport,
            wrapLines,
            wrapByWords,
            contentRows,
            wrapLines
                ? (wrapViewport == null ? 0 : wrapViewport.topWrappedOffset())
                : state.getTopLine(),
            wrapLines
                ? totalWrappedRows(model, layoutWidth, wrapByWords)
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
        EditorViewportBehavior.WrapTop normalized = EditorViewportBehavior.normalizeWrapTop(
            model,
            topLine,
            segmentOffset,
            width,
            wrapByWords,
            tabWidth
        );
        int topWrapped = EditorViewportBehavior.wrappedOffsetForWrapTop(
            model,
            normalized,
            width,
            wrapByWords,
            tabWidth
        );
        return new WrapViewport(normalized.line(), normalized.segmentOffset(), topWrapped);
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
            return new VisualSlice(0, "", 0, 0);
        }
        int safeLeftVisual = Math.max(0, leftVisualColumn);
        DisplayText.Metrics metrics = lineMetrics(model, lineIndex);
        int start = metrics.offsetForVisualColumn(
            safeLeftVisual,
            DisplayText.Bias.FLOOR
        );
        int sourceVisual = metrics.visualColumnAt(start);
        int rightVisual = (int) Math.min(Integer.MAX_VALUE, (long) safeLeftVisual + maxWidth);
        int end = metrics.offsetForVisualColumn(
            rightVisual,
            DisplayText.Bias.CEIL
        );
        end = Math.max(start, Math.min(end, line.length()));
        return new VisualSlice(
            start,
            line.substring(start, end),
            sourceVisual,
            Math.max(0, safeLeftVisual - sourceVisual)
        );
    }

    private int columnForVisualColumn(TextModel model, int lineIndex, int targetVisual) {
        return lineMetrics(model, lineIndex).offsetForVisualColumn(
            Math.max(0, targetVisual),
            DisplayText.Bias.FLOOR
        );
    }

    private DisplayText.Metrics lineMetrics(TextModel model, int lineIndex) {
        return model.displayMetrics(lineIndex, tabWidth);
    }

    private void ensureSyntaxCache(TextModel model) {
        long revision = model.revision();
        if (syntaxCacheRevision == revision) {
            return;
        }
        syntaxSpanCache.clear();
        syntaxCacheRevision = revision;
    }

    private void ensureQuoteMaskCache(TextModel model) {
        long revision = model.revision();
        if (quoteMaskCacheRevision == revision && quoteMaskCacheProfile == commentSyntaxProfile) {
            return;
        }
        quoteMaskCache.clear();
        quoteMaskComputedUntil = -1;
        quoteMaskContinuationState = QuoteContinuationState.NONE;
        quoteMaskCacheRevision = revision;
        quoteMaskCacheProfile = commentSyntaxProfile;
    }

    private boolean[] quotedMaskForLine(TextModel model, int lineIndex, String line) {
        QuoteMask cached = quoteMaskCache.get(lineIndex);
        if (cached != null && cached.lineText().equals(line)) {
            return cached.mask();
        }

        int target = Math.max(0, Math.min(lineIndex, Math.max(0, model.lineCount() - 1)));
        for (int i = quoteMaskComputedUntil + 1; i <= target; i++) {
            String lineText = model.getLine(i);
            QuoteMaskResult result = computeQuotedMask(lineText, commentSyntaxProfile, quoteMaskContinuationState);
            quoteMaskCache.put(i, new QuoteMask(lineText, result.mask()));
            quoteMaskContinuationState = result.endState();
            quoteMaskComputedUntil = i;
        }
        QuoteMask resolved = quoteMaskCache.get(target);
        if (resolved != null && resolved.lineText().equals(model.getLine(target))) {
            return resolved.mask();
        }
        // If cache entry is stale for any reason, rebuild masks from start.
        quoteMaskCache.clear();
        quoteMaskComputedUntil = -1;
        quoteMaskContinuationState = QuoteContinuationState.NONE;
        return quotedMaskForLine(model, target, model.getLine(target));
    }

    private static QuoteMaskResult computeQuotedMask(
        String line,
        CommentSyntaxProfile profile,
        QuoteContinuationState startState
    ) {
        boolean[] mask = new boolean[line.length()];
        if (profile.markdownNumberParen()) {
            int markerParen = markdownNumberParenIndex(line);
            if (markerParen >= 0) {
                mask[markerParen] = true;
            }
        }
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        boolean inBlockComment = startState.inSlashBlockComment();
        boolean inHtmlComment = startState.inHtmlComment();
        boolean escaped = false;

        for (int i = 0; i < line.length(); i++) {
            if (mask[i]) {
                continue;
            }
            char ch = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
            char next2 = i + 2 < line.length() ? line.charAt(i + 2) : '\0';
            char next3 = i + 3 < line.length() ? line.charAt(i + 3) : '\0';
            if (inBlockComment) {
                mask[i] = true;
                if (ch == '*' && next == '/') {
                    mask[i + 1] = true;
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inHtmlComment) {
                mask[i] = true;
                if (ch == '-' && next == '-' && next2 == '>') {
                    mask[i + 1] = true;
                    mask[i + 2] = true;
                    inHtmlComment = false;
                    i += 2;
                }
                continue;
            }
            if (inSingle || inDouble || inBacktick) {
                mask[i] = true;
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (inSingle && ch == '\'') {
                    inSingle = false;
                } else if (inDouble && ch == '"') {
                    inDouble = false;
                } else if (inBacktick && ch == '`') {
                    inBacktick = false;
                }
                continue;
            }

            if (profile.slashLine() && ch == '/' && next == '/') {
                for (int j = i; j < line.length(); j++) {
                    mask[j] = true;
                }
                break;
            }
            if (profile.slashBlock() && ch == '/' && next == '*') {
                mask[i] = true;
                mask[i + 1] = true;
                inBlockComment = true;
                i++;
                continue;
            }
            if (profile.htmlBlock() && ch == '<' && next == '!' && next2 == '-' && next3 == '-') {
                mask[i] = true;
                mask[i + 1] = true;
                mask[i + 2] = true;
                mask[i + 3] = true;
                inHtmlComment = true;
                i += 3;
                continue;
            }
            if (profile.hashLine() && ch == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                for (int j = i; j < line.length(); j++) {
                    mask[j] = true;
                }
                break;
            }
            if (profile.dashDashLine() && ch == '-' && next == '-') {
                for (int j = i; j < line.length(); j++) {
                    mask[j] = true;
                }
                break;
            }
            if (ch == '\'' && !isApostropheInWord(line, i)) {
                mask[i] = true;
                inSingle = true;
            } else if (ch == '"') {
                mask[i] = true;
                inDouble = true;
            } else if (ch == '`') {
                mask[i] = true;
                inBacktick = true;
            }
        }
        return new QuoteMaskResult(mask, new QuoteContinuationState(inBlockComment, inHtmlComment));
    }

    private static int markdownNumberParenIndex(String line) {
        if (line == null || line.isEmpty()) {
            return -1;
        }
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        if (i >= line.length()) {
            return -1;
        }

        if (line.charAt(i) == '#') {
            int hashStart = i;
            while (i < line.length() && line.charAt(i) == '#') {
                i++;
            }
            int hashes = i - hashStart;
            if (hashes > 6) {
                return -1;
            }
            if (i >= line.length() || !Character.isWhitespace(line.charAt(i))) {
                return -1;
            }
            while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
        }

        int numberStart = i;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i == numberStart || i >= line.length() || line.charAt(i) != ')') {
            return -1;
        }
        if (i + 1 < line.length() && !Character.isWhitespace(line.charAt(i + 1))) {
            return -1;
        }
        return i;
    }

    private List<StyleSpan> syntaxSpansForLine(TextModel model, int lineIndex, String line) {
        SyntaxSpans cached = syntaxSpanCache.get(lineIndex);
        if (cached != null && cached.lineText().equals(line)) {
            return cached.spans();
        }
        List<StyleSpan> spans = highlighter instanceof ContextualLineHighlighter contextual
            ? List.copyOf(contextual.highlight(model, lineIndex, line))
            : List.copyOf(highlighter.highlight(line));
        syntaxSpanCache.put(lineIndex, new SyntaxSpans(line, spans));
        return spans;
    }

    private int wrappedRowsBefore(TextModel model, int lineIndexExclusive, int width, boolean wrapByWords) {
        return model.wrappedRowsBefore(lineIndexExclusive, width, wrapByWords, tabWidth);
    }

    private int totalWrappedRows(TextModel model, int width, boolean wrapByWords) {
        return model.totalWrappedRows(width, wrapByWords, tabWidth);
    }

    private GutterDiffMarker gutterDiffForLine(int lineIndex) {
        if (lineIndex < 0) {
            return GutterDiffMarker.NONE;
        }
        GutterDiffMarker marker = gutterDiffResolver.apply(lineIndex);
        return marker == null ? GutterDiffMarker.NONE : marker;
    }

    private static String lineNumberGutter(int lineNumber, int gutterWidth, GutterDiffFlag diff) {
        char marker = markerChar(diff);
        return marker + String.format("%" + (gutterWidth - 2) + "d ", lineNumber);
    }

    private static String tildeGutter(int gutterWidth) {
        return " " + String.format("%" + (gutterWidth - 2) + "s ", "~");
    }

    private static char markerChar(GutterDiffFlag diff) {
        if (diff == null) {
            return ' ';
        }
        return switch (diff) {
            case ADDED -> '+';
            case MODIFIED -> '~';
            case DELETED -> '-';
            default -> ' ';
        };
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

    private List<StyleSpan> bracketSpansForLine(BracketPair pair, int lineIndex, int leftColumn, int visibleLength) {
        if (pair == null || visibleLength <= 0) {
            return List.of();
        }
        List<StyleSpan> spans = new ArrayList<>(2);
        addBracketSpan(spans, pair.primary(), lineIndex, leftColumn, visibleLength, pair.style());
        addBracketSpan(spans, pair.match(), lineIndex, leftColumn, visibleLength, pair.style());
        return spans;
    }

    private List<StyleSpan> unmatchedBracketSpansForLine(int lineIndex, int leftColumn, int visibleLength) {
        if (visibleLength <= 0) {
            return List.of();
        }
        List<Integer> columns = unmatchedBracketColumnsByLine.get(lineIndex);
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        List<StyleSpan> spans = new ArrayList<>(columns.size());
        for (int column : columns) {
            int visStart = column - leftColumn;
            int visEnd = visStart + 1;
            if (visEnd <= 0 || visStart >= visibleLength) {
                continue;
            }
            spans.add(
                new StyleSpan(
                    Math.max(0, visStart),
                    Math.min(visibleLength, visEnd),
                    unmatchedBracketStyle
                )
            );
        }
        return spans;
    }

    private List<StyleSpan> rainbowBracketSpansForLine(int lineIndex, int leftColumn, int visibleLength) {
        if (visibleLength <= 0) {
            return List.of();
        }
        List<StyleSpan> spans = rainbowBracketSpansByLine.get(lineIndex);
        if (spans == null || spans.isEmpty()) {
            return List.of();
        }
        return clipSpans(spans, leftColumn, visibleLength);
    }

    private void addBracketSpan(
        List<StyleSpan> spans,
        Position pos,
        int lineIndex,
        int leftColumn,
        int visibleLength,
        Style style
    ) {
        if (pos == null || pos.line() != lineIndex) {
            return;
        }
        int visStart = pos.col() - leftColumn;
        int visEnd = visStart + 1;
        if (visEnd <= 0 || visStart >= visibleLength) {
            return;
        }
        spans.add(new StyleSpan(Math.max(0, visStart), Math.min(visibleLength, visEnd), style));
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
        return new StyleSpan(visStart, visEnd, selectionStyle);
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
        return new StyleSpan(visStart, visEnd, currentSearchMatchStyle);
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

    private BracketPair bracketPairForCursor(TextModel model, EditorState state) {
        Position cursor = model.clamp(state.getCursor());
        long revision = model.revision();
        if (bracketCacheRevision == revision
            && cursor.equals(bracketCacheCursor)
            && bracketCacheRainbowEnabled == rainbowBracketsEnabled) {
            return bracketCachePair;
        }
        if (model.lineCount() <= 0) {
            bracketCacheRevision = revision;
            bracketCacheCursor = cursor;
            bracketCachePair = null;
            bracketCacheRainbowEnabled = rainbowBracketsEnabled;
            return null;
        }
        BracketAtCursor at = bracketAtCursor(model, cursor);
        if (at == null) {
            bracketCacheRevision = revision;
            bracketCacheCursor = cursor;
            bracketCachePair = null;
            bracketCacheRainbowEnabled = rainbowBracketsEnabled;
            return null;
        }
        Position match = switch (at.kind()) {
            case BRACKET -> switch (at.ch()) {
                case '(', '[', '{' -> findBracketForward(model, at.position(), at.ch(), closingBracketFor(at.ch()));
                case ')', ']', '}' -> findBracketBackward(model, at.position(), openingBracketFor(at.ch()), at.ch());
                default -> null;
            };
            case QUOTE -> findMatchingQuote(model, at.position(), at.ch());
        };
        Style style = switch (at.kind()) {
            case BRACKET -> match == null
                ? unmatchedBracketStyle
                : bracketMatchStyle;
            case QUOTE -> match == null
                ? unmatchedBracketStyle
                : quoteMatchStyle;
        };
        BracketPair pair = new BracketPair(at.position(), match, style);
        bracketCacheRevision = revision;
        bracketCacheCursor = cursor;
        bracketCachePair = pair;
        bracketCacheRainbowEnabled = rainbowBracketsEnabled;
        return pair;
    }

    private void ensureBracketDecorationCache(TextModel model) {
        long revision = model.revision();
        if (unmatchedCacheRevision == revision) {
            return;
        }
        Map<Integer, List<Integer>> unmatched = new HashMap<>();
        Map<Integer, List<StyleSpan>> rainbow = new HashMap<>();
        ArrayDeque<OpenBracket> roundStack = new ArrayDeque<>();
        ArrayDeque<OpenBracket> squareStack = new ArrayDeque<>();
        ArrayDeque<OpenBracket> curlyStack = new ArrayDeque<>();
        int scanned = 0;
        boolean complete = true;
        outer:
        for (int line = 0; line < model.lineCount(); line++) {
            String text = model.getLine(line);
            boolean[] quotedMask = quotedMaskForLine(model, line, text);
            for (int col = 0; col < text.length(); col++) {
                if (quotedMask[col]) {
                    continue;
                }
                char ch = text.charAt(col);
                if (!isBracket(ch)) {
                    continue;
                }
                scanned++;
                if (scanned > MAX_UNMATCHED_SCAN_CHARS) {
                    complete = false;
                    break outer;
                }
                if (isOpeningBracket(ch)) {
                    int depth = totalOpenBracketCount(roundStack, squareStack, curlyStack) + 1;
                    addRainbowBracket(rainbow, line, col, depth);
                    stackForOpeningBracket(ch, roundStack, squareStack, curlyStack)
                        .push(new OpenBracket(new Position(line, col), ch, depth));
                    continue;
                }
                ArrayDeque<OpenBracket> typedStack = stackForClosingBracket(ch, roundStack, squareStack, curlyStack);
                if (!typedStack.isEmpty()) {
                    OpenBracket open = typedStack.pop();
                    addRainbowBracket(rainbow, line, col, open.depth());
                } else {
                    addRainbowBracket(
                        rainbow,
                        line,
                        col,
                        Math.max(1, totalOpenBracketCount(roundStack, squareStack, curlyStack) + 1)
                    );
                    addUnmatchedBracket(unmatched, line, col);
                }
            }
        }
        if (complete) {
            markUnmatchedOpenBrackets(unmatched, roundStack);
            markUnmatchedOpenBrackets(unmatched, squareStack);
            markUnmatchedOpenBrackets(unmatched, curlyStack);
        }
        unmatchedBracketColumnsByLine = unmatched;
        rainbowBracketSpansByLine = rainbow;
        unmatchedCacheRevision = revision;
    }

    private static int totalOpenBracketCount(
        ArrayDeque<OpenBracket> roundStack,
        ArrayDeque<OpenBracket> squareStack,
        ArrayDeque<OpenBracket> curlyStack
    ) {
        return roundStack.size() + squareStack.size() + curlyStack.size();
    }

    private static ArrayDeque<OpenBracket> stackForOpeningBracket(
        char ch,
        ArrayDeque<OpenBracket> roundStack,
        ArrayDeque<OpenBracket> squareStack,
        ArrayDeque<OpenBracket> curlyStack
    ) {
        return switch (ch) {
            case '(' -> roundStack;
            case '[' -> squareStack;
            case '{' -> curlyStack;
            default -> roundStack;
        };
    }

    private static ArrayDeque<OpenBracket> stackForClosingBracket(
        char ch,
        ArrayDeque<OpenBracket> roundStack,
        ArrayDeque<OpenBracket> squareStack,
        ArrayDeque<OpenBracket> curlyStack
    ) {
        return switch (ch) {
            case ')' -> roundStack;
            case ']' -> squareStack;
            case '}' -> curlyStack;
            default -> roundStack;
        };
    }

    private static void markUnmatchedOpenBrackets(
        Map<Integer, List<Integer>> unmatched,
        ArrayDeque<OpenBracket> stack
    ) {
        while (!stack.isEmpty()) {
            OpenBracket bracket = stack.pop();
            addUnmatchedBracket(unmatched, bracket.position().line(), bracket.position().col());
        }
    }

    private static void addUnmatchedBracket(Map<Integer, List<Integer>> map, int line, int col) {
        map.computeIfAbsent(line, key -> new ArrayList<>(2)).add(col);
    }

    private void addRainbowBracket(Map<Integer, List<StyleSpan>> map, int line, int col, int depth) {
        map.computeIfAbsent(line, key -> new ArrayList<>(4))
            .add(new StyleSpan(col, col + 1, rainbowBracketStyle(depth)));
    }

    private BracketAtCursor bracketAtCursor(TextModel model, Position cursor) {
        String line = model.getLine(cursor.line());
        boolean[] quotedMask = quotedMaskForLine(model, cursor.line(), line);
        int col = Math.max(0, Math.min(cursor.col(), line.length()));
        if (col < line.length()) {
            char ch = line.charAt(col);
            if (isBracket(ch) && !quotedMask[col]) {
                return new BracketAtCursor(new Position(cursor.line(), col), ch, MatchKind.BRACKET);
            }
            if (isQuote(ch) && !quotedMask[col] && !isEscaped(line, col) && !isApostropheInWord(line, col)) {
                return new BracketAtCursor(new Position(cursor.line(), col), ch, MatchKind.QUOTE);
            }
        }
        if (col > 0) {
            char ch = line.charAt(col - 1);
            if (isBracket(ch) && !quotedMask[col - 1]) {
                return new BracketAtCursor(new Position(cursor.line(), col - 1), ch, MatchKind.BRACKET);
            }
            if (isQuote(ch) && !quotedMask[col - 1] && !isEscaped(line, col - 1) && !isApostropheInWord(line, col - 1)) {
                return new BracketAtCursor(new Position(cursor.line(), col - 1), ch, MatchKind.QUOTE);
            }
        }
        return null;
    }

    private Position findMatchingQuote(TextModel model, Position from, char quote) {
        String line = model.getLine(from.line());
        if (from.col() < 0 || from.col() >= line.length()) {
            return null;
        }
        int before = countUnescapedQuote(line, quote, from.col());
        Position pair = (before % 2 == 0)
            ? findQuoteForward(from.line(), line, from.col(), quote)
            : findQuoteBackward(from.line(), line, from.col(), quote);
        if (pair != null) {
            return pair;
        }
        return (before % 2 == 0)
            ? findQuoteBackward(from.line(), line, from.col(), quote)
            : findQuoteForward(from.line(), line, from.col(), quote);
    }

    private static Position findQuoteForward(int lineIndex, String line, int fromCol, char quote) {
        for (int col = fromCol + 1; col < line.length(); col++) {
            if (line.charAt(col) == quote && !isEscaped(line, col)) {
                return new Position(lineIndex, col);
            }
        }
        return null;
    }

    private static Position findQuoteBackward(int lineIndex, String line, int fromCol, char quote) {
        for (int col = fromCol - 1; col >= 0; col--) {
            if (line.charAt(col) == quote && !isEscaped(line, col)) {
                return new Position(lineIndex, col);
            }
        }
        return null;
    }

    private static int countUnescapedQuote(String line, char quote, int endExclusive) {
        int count = 0;
        int limit = Math.max(0, Math.min(endExclusive, line.length()));
        for (int i = 0; i < limit; i++) {
            if (line.charAt(i) == quote && !isEscaped(line, i)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isEscaped(String line, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static boolean isQuote(char ch) {
        return ch == '"' || ch == '\'';
    }

    private static boolean isApostropheInWord(String line, int index) {
        if (index < 0 || index >= line.length() || line.charAt(index) != '\'') {
            return false;
        }
        boolean leftWord = index > 0 && Character.isLetterOrDigit(line.charAt(index - 1));
        boolean rightWord = index + 1 < line.length() && Character.isLetterOrDigit(line.charAt(index + 1));
        return leftWord && rightWord;
    }

    private Position findBracketForward(TextModel model, Position from, char open, char close) {
        int depth = 1;
        int scanned = 0;
        for (int line = from.line(); line < model.lineCount(); line++) {
            String text = model.getLine(line);
            boolean[] quotedMask = quotedMaskForLine(model, line, text);
            int start = line == from.line() ? from.col() + 1 : 0;
            for (int col = start; col < text.length(); col++) {
                if (quotedMask[col]) {
                    continue;
                }
                char ch = text.charAt(col);
                scanned++;
                if (scanned > MAX_BRACKET_SCAN_CHARS) {
                    return null;
                }
                if (ch == open) {
                    depth++;
                } else if (ch == close) {
                    depth--;
                    if (depth == 0) {
                        return new Position(line, col);
                    }
                }
            }
        }
        return null;
    }

    private Position findBracketBackward(TextModel model, Position from, char open, char close) {
        int depth = 1;
        int scanned = 0;
        for (int line = from.line(); line >= 0; line--) {
            String text = model.getLine(line);
            boolean[] quotedMask = quotedMaskForLine(model, line, text);
            int start = line == from.line() ? from.col() - 1 : text.length() - 1;
            for (int col = start; col >= 0; col--) {
                if (quotedMask[col]) {
                    continue;
                }
                char ch = text.charAt(col);
                scanned++;
                if (scanned > MAX_BRACKET_SCAN_CHARS) {
                    return null;
                }
                if (ch == close) {
                    depth++;
                } else if (ch == open) {
                    depth--;
                    if (depth == 0) {
                        return new Position(line, col);
                    }
                }
            }
        }
        return null;
    }

    private static boolean isBracket(char ch) {
        return ch == '(' || ch == ')' || ch == '[' || ch == ']' || ch == '{' || ch == '}';
    }

    private int nestingDepthAt(TextModel model, Position pos, char bracket) {
        int depth = 0;
        int scanned = 0;
        for (int line = 0; line <= pos.line(); line++) {
            String text = model.getLine(line);
            boolean[] quotedMask = quotedMaskForLine(model, line, text);
            int endExclusive = line == pos.line() ? Math.min(pos.col(), text.length()) : text.length();
            for (int col = 0; col < endExclusive; col++) {
                if (quotedMask[col]) {
                    continue;
                }
                char ch = text.charAt(col);
                if (isOpeningBracket(ch)) {
                    depth++;
                } else if (isClosingBracket(ch)) {
                    depth = Math.max(0, depth - 1);
                }
                scanned++;
                if (scanned > MAX_BRACKET_SCAN_CHARS) {
                    return fallbackDepthFor(bracket, depth);
                }
            }
        }
        if (isOpeningBracket(bracket)) {
            return Math.max(1, depth + 1);
        }
        return Math.max(1, depth);
    }

    private static int fallbackDepthFor(char bracket, int depth) {
        if (isOpeningBracket(bracket)) {
            return Math.max(1, depth + 1);
        }
        return Math.max(1, depth);
    }

    private static boolean isOpeningBracket(char ch) {
        return ch == '(' || ch == '[' || ch == '{';
    }

    private static boolean isClosingBracket(char ch) {
        return ch == ')' || ch == ']' || ch == '}';
    }

    private static boolean isMatchingPair(char open, char close) {
        return (open == '(' && close == ')')
            || (open == '[' && close == ']')
            || (open == '{' && close == '}');
    }

    private Style rainbowBracketStyle(int depth) {
        int idx = Math.floorMod(Math.max(depth, 1) - 1, rainbowBracketStyles.size());
        return rainbowBracketStyles.get(idx);
    }

    private static char closingBracketFor(char open) {
        return switch (open) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            default -> open;
        };
    }

    private static char openingBracketFor(char close) {
        return switch (close) {
            case ')' -> '(';
            case ']' -> '[';
            case '}' -> '{';
            default -> close;
        };
    }

    private record LineMatch(int start, int end) {
    }

    private record BracketPair(Position primary, Position match, Style style) {
    }

    private record BracketAtCursor(Position position, char ch, MatchKind kind) {
    }

    private enum MatchKind {
        BRACKET,
        QUOTE
    }

    public enum CommentSyntaxProfile {
        NONE(false, false, false, false, false, false),
        HASH(true, false, false, false, false, false),
        C_STYLE(false, true, true, false, false, false),
        SQL(false, false, true, true, false, false),
        HTML(false, false, false, false, true, false),
        MARKDOWN(false, false, false, false, true, true),
        GENERIC(true, true, true, true, false, false);

        private final boolean hashLine;
        private final boolean slashLine;
        private final boolean slashBlock;
        private final boolean dashDashLine;
        private final boolean htmlBlock;
        private final boolean markdownNumberParen;

        CommentSyntaxProfile(
            boolean hashLine,
            boolean slashLine,
            boolean slashBlock,
            boolean dashDashLine,
            boolean htmlBlock,
            boolean markdownNumberParen
        ) {
            this.hashLine = hashLine;
            this.slashLine = slashLine;
            this.slashBlock = slashBlock;
            this.dashDashLine = dashDashLine;
            this.htmlBlock = htmlBlock;
            this.markdownNumberParen = markdownNumberParen;
        }

        private boolean hashLine() {
            return hashLine;
        }

        private boolean slashLine() {
            return slashLine;
        }

        private boolean slashBlock() {
            return slashBlock;
        }

        private boolean dashDashLine() {
            return dashDashLine;
        }

        private boolean htmlBlock() {
            return htmlBlock;
        }

        private boolean markdownNumberParen() {
            return markdownNumberParen;
        }
    }

    private record OpenBracket(Position position, char ch, int depth) {
    }

    private record VisualSlice(int startCol, String text, int sourceVisualColumn, int leadingClippedCells) {
    }

    private record SyntaxSpans(String lineText, List<StyleSpan> spans) {
    }

    private record QuoteMask(String lineText, boolean[] mask) {
    }

    private record QuoteMaskResult(boolean[] mask, QuoteContinuationState endState) {
    }

    private record QuoteContinuationState(boolean inSlashBlockComment, boolean inHtmlComment) {
        private static final QuoteContinuationState NONE = new QuoteContinuationState(false, false);
    }
}
