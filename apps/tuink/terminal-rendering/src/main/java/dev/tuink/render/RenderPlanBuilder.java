package dev.tuink.render;

import dev.tuink.core.CommentSyntaxProfile;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.GutterDiffFlag;
import dev.tuink.core.GutterDiffMarker;
import dev.tuink.core.LineText;
import dev.tuink.core.Position;
import dev.tuink.core.SearchLimitException;
import dev.tuink.core.SearchMatcher;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * Builds render data from current model and state.
 */
public class RenderPlanBuilder {
    private static final AtomicLong NEXT_BASE_STYLE_OWNER_ID = new AtomicLong();
    private static final AtomicLong NEXT_STANDALONE_DOCUMENT_ID = new AtomicLong();
    private static final int MAX_SYNTAX_CACHE_ENTRIES = 1024;
    private static final int MAX_CONTIGUOUS_VISIBLE_CODE_UNITS = 8192;
    static final int MAX_SYNTAX_CACHE_WEIGHT = 65_536;
    private static final int MAX_SYNTAX_SCAN_CODE_UNITS = 65_536;
    private static final int MAX_SYNTAX_SPANS = 4096;
    private static final long MAX_SYNTAX_WORK = 1_048_576L;
    private static final int MAX_BRACKET_SCAN_CHARS = 200_000;
    private static final int MAX_UNMATCHED_SCAN_CHARS = 1_000_000;
    private static final int MAX_LEXICAL_SCAN_LINES = 32_768;
    static final int MAX_LEXICAL_CACHE_OUTPUTS = 32_768;
    static final int MAX_BRACKET_DEPTH = 32_768;
    static final int MAX_BRACKET_FACTS = 131_072;
    private final LineHighlighter highlighter;
    private final LineLexicalScanner lexicalScanner;
    private final EditorTextLayout textLayout;
    private final DocumentView documentView;
    private final long baseStyleOwnerId = NEXT_BASE_STYLE_OWNER_ID.incrementAndGet();
    private long baseStyleGeneration;
    private int tabWidth = 4;
    private boolean syntaxHighlightingEnabled = true;
    private boolean bracketMatchingEnabled = true;
    private boolean rainbowBracketsEnabled = true;
    private IntFunction<GutterDiffMarker> gutterDiffResolver = line -> GutterDiffMarker.NONE;
    private CommentSyntaxProfile commentSyntaxProfile = CommentSyntaxProfile.GENERIC;
    private long syntaxCacheRevision = Long.MIN_VALUE;
    private final Map<SyntaxKey, SyntaxSpans> syntaxSpanCache = new LinkedHashMap<>(256, 0.75f, true);
    private int syntaxCacheWeight;
    private boolean pendingSyntaxWork;
    private long quoteMaskCacheRevision = Long.MIN_VALUE;
    private CommentSyntaxProfile quoteMaskCacheProfile = CommentSyntaxProfile.GENERIC;
    private final Map<Integer, QuoteMask> quoteMaskCache = new HashMap<>();
    private int quoteMaskComputedUntil = -1;
    private LineLexicalScanner.State lexicalContinuationState;
    private int remainingLexicalScanChars;
    private int remainingLexicalOutputs;
    private boolean lexicalScanExhausted;
    private long bracketCacheRevision = Long.MIN_VALUE;
    private Position bracketCacheCursor;
    private BracketPair bracketCachePair;
    private long bracketFactsRevision = Long.MIN_VALUE;
    private BracketFacts bracketFacts;
    private Style selectionStyle = Style.DEFAULT;
    private Style searchMatchStyle = Style.DEFAULT;
    private Style currentSearchMatchStyle = Style.DEFAULT;
    private Style bracketMatchStyle = Style.DEFAULT;
    private Style quoteMatchStyle = Style.DEFAULT;
    private Style unmatchedBracketStyle = Style.DEFAULT;
    private List<Style> rainbowBracketStyles = List.of(Style.DEFAULT);
    private SearchFailure failedSearch;
    private String highlightQuery;
    private SearchOptions highlightOptions;
    private SearchMatcher highlightMatcher;

    private record SearchFailure(String query, SearchOptions options, long identity, long revision) {}

    /** Optional highlights must not prevent a document frame from being built. */
    private final class SearchHighlights {
        private final SearchFailure key;
        private SearchMatcher matcher;

        private SearchHighlights(TextModel model, String query, SearchOptions options) {
            key = new SearchFailure(query, options, model.cacheIdentity(), model.revision());
            if (key.equals(failedSearch)) return;
            try {
                if (highlightMatcher == null || !Objects.equals(query, highlightQuery)
                    || !Objects.equals(options, highlightOptions)) {
                    SearchMatcher compiled = SearchMatcher.compile(query, options);
                    highlightQuery = query;
                    highlightOptions = options;
                    highlightMatcher = compiled;
                }
                matcher = highlightMatcher;
                failedSearch = null;
            } catch (SearchLimitException limit) {
                failedSearch = key;
            }
        }

        private List<SearchMatcher.Match> findIntersecting(CharSequence source, int start, int end) {
            if (matcher == null) return List.of();
            try {
                return matcher.findIntersecting(source, start, end);
            } catch (SearchLimitException limit) {
                refuse();
                return List.of();
            }
        }

        private boolean matchesRange(CharSequence source, int start, int end) {
            if (matcher == null) return false;
            try {
                return matcher.matchesRange(source, start, end);
            } catch (SearchLimitException limit) {
                refuse();
                return false;
            }
        }

        private void refuse() {
            matcher = null;
            failedSearch = key;
        }
    }

    public RenderPlanBuilder(LineHighlighter highlighter, EditorTextLayout textLayout, DocumentId documentId) {
        this.highlighter = highlighter;
        this.lexicalScanner = highlighter instanceof ContextualLineHighlighter contextual
            ? contextual.lexicalScanner() : null;
        this.textLayout = Objects.requireNonNull(textLayout, "textLayout");
        this.documentView = new TextModelDocumentView(textLayout.model(), documentId);
    }

    /**
     * Creates an individual model/layout fixture without a document session. Each
     * call gets a separate logical identity; attached production views must use
     * the constructor with their session's canonical document ID instead.
     */
    public static RenderPlanBuilder standalone(LineHighlighter highlighter, EditorTextLayout textLayout) {
        DocumentId identity = new DocumentId("standalone-render-"
            + Long.toUnsignedString(NEXT_STANDALONE_DOCUMENT_ID.incrementAndGet()));
        return new RenderPlanBuilder(highlighter, textLayout, identity);
    }

    /** Logical identity passed to highlighters, independent of render/cache ownership. */
    public DocumentId documentId() {
        return documentView.documentId();
    }

    /** Returns the shared layout instance used by this builder. */
    public EditorTextLayout textLayout() {
        return textLayout;
    }

    /** Whether the last build retained syntax context work that needs another bounded frame. */
    public boolean hasPendingSyntaxWork() {
        return pendingSyntaxWork;
    }

    public void setTabWidth(int tabWidth) {
        int normalized = tabWidth <= 2 ? 2 : 4;
        if (this.tabWidth == normalized) {
            return;
        }
        this.tabWidth = normalized;
    }

    public void setSyntaxHighlightingEnabled(boolean enabled) {
        if (this.syntaxHighlightingEnabled == enabled) {
            return;
        }
        this.syntaxHighlightingEnabled = enabled;
        baseStyleGeneration++;
    }

    public void setBracketMatchingEnabled(boolean enabled) {
        if (this.bracketMatchingEnabled == enabled) {
            return;
        }
        this.bracketMatchingEnabled = enabled;
        releaseDisabledBracketCaches();
        baseStyleGeneration++;
    }

    public void setRainbowBracketsEnabled(boolean enabled) {
        if (this.rainbowBracketsEnabled == enabled) {
            return;
        }
        this.rainbowBracketsEnabled = enabled;
        releaseDisabledBracketCaches();
        baseStyleGeneration++;
    }

    private void releaseDisabledBracketCaches() {
        if (bracketMatchingEnabled || rainbowBracketsEnabled) return;
        bracketFacts = null;
        bracketFactsRevision = Long.MIN_VALUE;
        bracketCachePair = null;
        bracketCacheCursor = null;
        bracketCacheRevision = Long.MIN_VALUE;
        quoteMaskCache.clear();
        quoteMaskCacheRevision = Long.MIN_VALUE;
        lexicalContinuationState = null;
        remainingLexicalOutputs = MAX_LEXICAL_CACHE_OUTPUTS;
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
        this.bracketFactsRevision = Long.MIN_VALUE;
        this.bracketCacheRevision = Long.MIN_VALUE;
        baseStyleGeneration++;
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
        Style nextSelection = selectionStyle == null ? Style.DEFAULT : selectionStyle;
        Style nextSearchMatch = searchMatchStyle == null ? Style.DEFAULT : searchMatchStyle;
        Style nextCurrentSearchMatch = currentSearchMatchStyle == null ? Style.DEFAULT : currentSearchMatchStyle;
        Style nextBracketMatch = bracketMatchStyle == null ? Style.DEFAULT : bracketMatchStyle;
        Style nextQuoteMatch = quoteMatchStyle == null ? Style.DEFAULT : quoteMatchStyle;
        Style nextUnmatchedBracket = unmatchedBracketStyle == null ? Style.DEFAULT : unmatchedBracketStyle;
        List<Style> nextRainbowBracketStyles = rainbowBracketStyles == null || rainbowBracketStyles.isEmpty()
            ? List.of(Style.DEFAULT)
            : List.copyOf(rainbowBracketStyles);
        if (
            !this.unmatchedBracketStyle.equals(nextUnmatchedBracket)
                || !this.rainbowBracketStyles.equals(nextRainbowBracketStyles)
        ) {
            baseStyleGeneration++;
        }
        this.selectionStyle = nextSelection;
        this.searchMatchStyle = nextSearchMatch;
        this.currentSearchMatchStyle = nextCurrentSearchMatch;
        this.bracketMatchStyle = nextBracketMatch;
        this.quoteMatchStyle = nextQuoteMatch;
        this.unmatchedBracketStyle = nextUnmatchedBracket;
        this.rainbowBracketStyles = nextRainbowBracketStyles;
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
        requireBoundModel(model);
        EditorLayout layout = EditorLayout.resolve(
            model,
            textLayout,
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
        requireBoundModel(model);
        if (pendingSyntaxWork) {
            // Resumed syntax can change prepared styles without a document edit.
            baseStyleGeneration++;
        }
        pendingSyntaxWork = false;
        EditorLayout resolvedLayout = layout == null
            ? EditorLayout.resolve(
                model,
                textLayout,
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
        EditorViewCoordinates coordinates = new EditorViewCoordinates(
            textLayout, layoutWidth, wrapLines, wrapByWords, tabWidth
        );
        EditorViewCoordinates.Top viewportTop = coordinates.normalizeTop(EditorViewCoordinates.Top.from(state));
        int topVisualRow = coordinates.rowOffset(viewportTop);
        // Reuse the injected per-model layout cache instead of scanning all lines on every frame.
        Position layoutCursor = model.clamp(state.getCursor());
        int cursorExtent = textLayout.visualColumnAt(
            layoutCursor.line(),
            layoutCursor.col(),
            tabWidth
        ) + 1;
        int totalColumns = Math.max(textLayout.maxVisualLineWidth(tabWidth), cursorExtent);
        BracketPair bracketPair = bracketMatchingEnabled ? bracketPairForCursor(model, state) : null;
        if (bracketMatchingEnabled || rainbowBracketsEnabled) {
            ensureBracketDecorationCache(model);
        }
        SearchHighlights searchMatcher = new SearchHighlights(model, searchQuery, searchOptions);

        List<LineVisual> lines = new ArrayList<>(contentRows);
        if (!wrapLines) {
            int leftVisualColumn = Math.max(0, state.getLeftVisualColumn());
            for (int row = 0; row < contentRows; row++) {
                int lineIndex = state.getTopLine() + row;
                if (lineIndex < model.lineCount()) {
                    CharSequence line = model.lineText(lineIndex);
                    VisualSlice slice = visibleSlice(lineIndex, line, leftVisualColumn, textWidth);
                    int lineLeftColumn = slice.startCol();
                    CharSequence visibleText = slice.text();
                    List<StyleSpan> syntaxSpans = syntaxHighlightingEnabled
                        ? syntaxSpansForLine(lineIndex, line, lineLeftColumn, lineLeftColumn + visibleText.length())
                        : List.of();
                    List<StyleSpan> spans = new ArrayList<>(clipSpans(syntaxSpans, lineLeftColumn, visibleText.length()));
                    if (rainbowBracketsEnabled) {
                        spans.addAll(rainbowBracketSpansForLine(lineIndex, lineLeftColumn, visibleText.length()));
                    }
                    if (bracketMatchingEnabled) {
                        spans.addAll(unmatchedBracketSpansForLine(lineIndex, lineLeftColumn, visibleText.length()));
                    }
                    int baseSpanCount = spans.size();
                    if (bracketMatchingEnabled) {
                        spans.addAll(bracketSpansForLine(bracketPair, lineIndex, lineLeftColumn, visibleText.length()));
                    }
                    spans.addAll(
                        clipSpans(
                            searchMatchSpans(
                                line, searchMatcher, searchMatchStyle,
                                lineLeftColumn, lineLeftColumn + visibleText.length()
                            ),
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
                        searchMatcher
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
                            slice.leadingClippedCells(),
                            baseSpanCount,
                            baseStyleKey(
                                model,
                                lineIndex,
                                lineLeftColumn,
                                visibleText.length()
                            ),
                            selectionStyleForLineBreak(state, model, lineIndex, leftVisualColumn, textWidth)
                        )
                    );
                } else {
                    lines.add(new LineVisual(tildeGutter(gutterWidth), "", List.of(), GutterDiffMarker.NONE));
                }
            }
        } else {
            int lineIndex = viewportTop.line();
            int firstSegmentOffset = viewportTop.segment();
            while (lines.size() < contentRows) {
                if (lineIndex >= model.lineCount()) {
                    lines.add(new LineVisual(tildeGutter(gutterWidth), "", List.of(), GutterDiffMarker.NONE));
                    continue;
                }
                CharSequence line = model.lineText(lineIndex);
                List<Integer> starts = wrapStarts(lineIndex, layoutWidth, wrapByWords);
                int segStart = Math.max(0, Math.min(firstSegmentOffset, starts.size() - 1));
                int segEnd = Math.min(starts.size(), segStart + contentRows - lines.size());
                int visibleStart = starts.get(segStart);
                int visibleEnd = segEnd < starts.size() ? starts.get(segEnd) : line.length();
                GutterDiffMarker gutterDiff = gutterDiffForLine(lineIndex);
                List<StyleSpan> lineSpans = new ArrayList<>(
                    syntaxHighlightingEnabled
                        ? syntaxSpansForLine(lineIndex, line, visibleStart, visibleEnd) : List.of()
                );
                if (rainbowBracketsEnabled) {
                    lineSpans.addAll(bracketDecorationSpans(lineIndex, visibleStart, visibleEnd, 0, false));
                }
                if (bracketMatchingEnabled) {
                    lineSpans.addAll(bracketDecorationSpans(lineIndex, visibleStart, visibleEnd, 0, true));
                }
                int baseSpanCount = lineSpans.size();
                if (bracketMatchingEnabled) {
                    lineSpans.addAll(bracketSpansForLine(bracketPair, lineIndex, 0, line.length()));
                }
                lineSpans.addAll(searchMatchSpans(
                    line, searchMatcher, searchMatchStyle, starts.get(segStart), visibleEnd
                ));
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
                    searchMatcher
                );
                if (currentMatch != null) {
                    lineSpans.add(currentMatch);
                }

                for (int seg = segStart; seg < starts.size() && lines.size() < contentRows; seg++) {
                    int start = starts.get(seg);
                    int end = seg + 1 < starts.size() ? starts.get(seg + 1) : line.length();
                    CharSequence part = visibleText(line, start, end);
                    List<StyleSpan> partSpans = new ArrayList<>(
                        clipSpans(lineSpans.subList(0, baseSpanCount), start, part.length())
                    );
                    int partBaseSpanCount = partSpans.size();
                    partSpans.addAll(
                        clipSpans(lineSpans.subList(baseSpanCount, lineSpans.size()), start, part.length())
                    );
                    String gutter = seg == 0
                        ? lineNumberGutter(lineIndex + 1, gutterWidth, gutterDiff.flag())
                        : " ".repeat(gutterWidth);
                    lines.add(
                        new LineVisual(
                            gutter,
                            part,
                            partSpans,
                            seg == 0 ? gutterDiff : GutterDiffMarker.NONE,
                            textLayout.visualColumnAt(lineIndex, start, tabWidth),
                            0,
                            partBaseSpanCount,
                            baseStyleKey(model, lineIndex, start, part.length()),
                            seg == starts.size() - 1
                                ? selectionStyleForLineBreak(
                                    state, model, lineIndex,
                                    textLayout.visualColumnAt(lineIndex, start, tabWidth), textWidth
                                )
                                : null
                        )
                    );
                }
                firstSegmentOffset = 0;
                lineIndex++;
            }
        }

        Position cursor = model.clamp(state.getCursor());
        int leftVisualColumn = Math.max(0, state.getLeftVisualColumn());
        int cursorLineLeftColumn = columnForVisualColumn(cursor.line(), leftVisualColumn);
        EditorViewCoordinates.VisualPosition cursorVisual = coordinates.inViewport(cursor, state);
        int cursorRow = cursorVisual.row();
        int cursorCol = gutterWidth + cursorVisual.column();
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
            topVisualRow,
            coordinates.totalRows(),
            cursorLineLeftColumn,
            leftVisualColumn,
            totalColumns,
            safeCols,
            gutterWidth,
            scrollbarWidth,
            resolvedLayout.scrollLayout()
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

    private List<Integer> wrapStarts(int lineIndex, int maxVisualWidth, boolean wrapByWords) {
        return textLayout.wrapStarts(lineIndex, maxVisualWidth, wrapByWords, tabWidth);
    }

    private BaseStyleIdentity baseStyleKey(
        TextModel model,
        int lineIndex,
        int sliceStart,
        int sliceLength
    ) {
        return BaseStyleIdentity.of(
            baseStyleOwnerId,
            model.cacheIdentity(),
            baseStyleGeneration,
            model.revision(),
            lineIndex,
            sliceStart,
            sliceLength
        );
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

    private VisualSlice visibleSlice(int lineIndex, CharSequence line, int leftVisualColumn, int maxWidth) {
        if (maxWidth <= 0 || line.isEmpty()) {
            return new VisualSlice(0, "", 0, 0);
        }
        int safeLeftVisual = Math.max(0, leftVisualColumn);
        EditorTextLayout.VisualRange range = textLayout.visualRange(
            lineIndex,
            safeLeftVisual,
            maxWidth,
            tabWidth
        );
        int start = range.startOffset();
        int sourceVisual = range.sourceVisualColumn();
        int end = range.endOffset();
        end = Math.max(start, Math.min(end, line.length()));
        return new VisualSlice(
            start,
            visibleText(line, start, end),
            sourceVisual,
            Math.max(0, safeLeftVisual - sourceVisual)
        );
    }

    /** Keep normal rows contiguous without slicing storage blocks or flattening oversized clusters. */
    private static CharSequence visibleText(CharSequence line, int start, int end) {
        if (end - start > MAX_CONTIGUOUS_VISIBLE_CODE_UNITS) return line.subSequence(start, end);
        return line instanceof LineText text ? text.sliceString(start, end) : line.subSequence(start, end).toString();
    }

    private int columnForVisualColumn(int lineIndex, int targetVisual) {
        return textLayout.textOffsetAtVisualColumn(
            lineIndex,
            Math.max(0, targetVisual),
            tabWidth,
            EditorTextLayout.Bias.FLOOR
        );
    }

    private void ensureSyntaxCache(TextModel model) {
        long revision = model.revision();
        if (syntaxCacheRevision == revision) {
            return;
        }
        syntaxSpanCache.clear();
        syntaxCacheWeight = 0;
        syntaxCacheRevision = revision;
    }

    private void ensureQuoteMaskCache(TextModel model) {
        long revision = model.revision();
        if (quoteMaskCacheRevision == revision && quoteMaskCacheProfile == commentSyntaxProfile) {
            return;
        }
        quoteMaskCache.clear();
        quoteMaskComputedUntil = -1;
        lexicalContinuationState = null;
        remainingLexicalScanChars = MAX_UNMATCHED_SCAN_CHARS;
        remainingLexicalOutputs = MAX_LEXICAL_CACHE_OUTPUTS;
        lexicalScanExhausted = false;
        quoteMaskCacheRevision = revision;
        quoteMaskCacheProfile = commentSyntaxProfile;
    }

    private QuoteMask quoteMaskForLine(TextModel model, int lineIndex, CharSequence line) {
        QuoteMask cached = quoteMaskCache.get(lineIndex);
        if (cached != null && cached.lineText().equals(line)) {
            return cached;
        }

        int target = Math.max(0, Math.min(lineIndex, Math.max(0, model.lineCount() - 1)));
        for (int i = quoteMaskComputedUntil + 1; i <= target; i++) {
            if (lexicalScanExhausted || remainingLexicalScanChars <= 0 || remainingLexicalOutputs <= 0
                || i >= MAX_LEXICAL_SCAN_LINES) {
                lexicalScanExhausted = true;
                break;
            }
            CharSequence lineText = model.lineText(i);
            int limit = Math.min(lineText.length(), remainingLexicalScanChars);
            LineLexicalScanner scanner = lexicalScanner == null
                ? new GenericLineLexicalScanner(commentSyntaxProfile) : lexicalScanner;
            // Retain one real lookahead unit when the prefix is incomplete so
            // scanners cannot mistake the budget boundary for the actual EOL.
            String scanText = lineText.subSequence(0,
                (int) Math.min(lineText.length(), (long) limit + 1L)).toString();
            LineLexicalScanner.Result result = scanner.scan(scanText, limit, lexicalContinuationState);
            if (result.scannedCodeUnits() > limit) {
                throw new IllegalStateException("Lexical scanner exceeded its prefix budget");
            }
            int outputs = result.excludedRanges().size() + result.quotes().size();
            if (outputs > remainingLexicalOutputs) {
                lexicalScanExhausted = true;
                lexicalContinuationState = null;
                break;
            }
            remainingLexicalOutputs -= outputs;
            boolean[] mask = new boolean[result.scannedCodeUnits()];
            for (LineLexicalScanner.Range range : result.excludedRanges()) {
                Arrays.fill(mask, range.start(), range.end(), true);
            }
            boolean completeLine = result.contextKnown() && result.scannedCodeUnits() == lineText.length();
            List<QuoteRange> quotes = result.quotes().stream()
                .filter(quote -> completeLine || quote.end() >= 0)
                .map(quote -> new QuoteRange(quote.start(), quote.end())).toList();
            quoteMaskCache.put(i, new QuoteMask(lineText, mask, quotes, completeLine));
            lexicalContinuationState = completeLine ? result.endState() : null;
            // Empty lines still consume budget, and the line cap bounds cache/object overhead.
            remainingLexicalScanChars -= Math.max(1, quoteMaskCache.get(i).mask().length);
            quoteMaskComputedUntil = i;
            if (!completeLine) {
                lexicalScanExhausted = true;
                break;
            }
        }
        QuoteMask resolved = quoteMaskCache.get(target);
        return resolved != null && resolved.lineText().equals(line) ? resolved : null;
    }

    private List<StyleSpan> syntaxSpansForLine(int lineIndex, CharSequence line, int startUtf16, int endUtf16) {
        SyntaxKey key = new SyntaxKey(lineIndex, startUtf16, endUtf16);
        SyntaxSpans cached = syntaxSpanCache.get(key);
        if (cached != null && cached.lineText().equals(line)) {
            return cached.spans();
        }
        if (cached != null) {
            syntaxSpanCache.remove(key);
            syntaxCacheWeight -= cached.weight();
        }
        HighlightRequest request = new HighlightRequest(startUtf16, endUtf16,
            MAX_SYNTAX_SCAN_CODE_UNITS, MAX_SYNTAX_SPANS, MAX_SYNTAX_WORK);
        // Keep one real lookahead unit: a bounded prefix must not look like EOL
        // to a highlighter which declines incomplete input.
        String syntaxText = line.subSequence(0, Math.min(line.length(), MAX_SYNTAX_SCAN_CODE_UNITS + 1)).toString();
        HighlightResult result = highlighter instanceof ContextualLineHighlighter contextual
            ? contextual.highlightResult(documentView, lineIndex, syntaxText, request)
            : HighlightResult.completed(highlighter.highlight(syntaxText, request));
        List<StyleSpan> spans = request.clipSpans(result.spans());
        if (result.retryPending()) {
            pendingSyntaxWork = true;
            return spans;
        }
        SyntaxSpans computed = new SyntaxSpans(line, spans);
        syntaxSpanCache.put(key, computed);
        syntaxCacheWeight += computed.weight();
        var iterator = syntaxSpanCache.entrySet().iterator();
        while (syntaxSpanCache.size() > MAX_SYNTAX_CACHE_ENTRIES || syntaxCacheWeight > MAX_SYNTAX_CACHE_WEIGHT) {
            SyntaxSpans removed = iterator.next().getValue();
            syntaxCacheWeight -= removed.weight();
            iterator.remove();
        }
        return spans;
    }

    int syntaxCacheWeight() {
        return syntaxCacheWeight;
    }

    int syntaxCacheSize() {
        return syntaxSpanCache.size();
    }

    private void requireBoundModel(TextModel model) {
        if (model == null || textLayout.model() != model) {
            throw new IllegalArgumentException("textLayout must be bound to model");
        }
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

    private List<StyleSpan> searchMatchSpans(
        CharSequence line, SearchHighlights matcher, Style style, int start, int end
    ) {
        return matcher.findIntersecting(line, start, end).stream()
            .map(m -> new StyleSpan(m.startUtf16(), m.endUtf16(), style))
            .toList();
    }

    private List<StyleSpan> bracketSpansForLine(BracketPair pair, int lineIndex, int leftColumn, int visibleLength) {
        if (pair == null || visibleLength <= 0) {
            return List.of();
        }
        List<StyleSpan> spans = new ArrayList<>(2);
        Style style = pair.match() == null ? unmatchedBracketStyle
            : pair.kind() == MatchKind.QUOTE ? quoteMatchStyle : bracketMatchStyle;
        addBracketSpan(spans, pair.primary(), lineIndex, leftColumn, visibleLength, style);
        addBracketSpan(spans, pair.match(), lineIndex, leftColumn, visibleLength, style);
        return spans;
    }

    private List<StyleSpan> unmatchedBracketSpansForLine(int lineIndex, int leftColumn, int visibleLength) {
        return bracketDecorationSpans(lineIndex, leftColumn,
            (int) Math.min(Integer.MAX_VALUE, (long) leftColumn + visibleLength), leftColumn, true);
    }

    private List<StyleSpan> rainbowBracketSpansForLine(int lineIndex, int leftColumn, int visibleLength) {
        return bracketDecorationSpans(lineIndex, leftColumn,
            (int) Math.min(Integer.MAX_VALUE, (long) leftColumn + visibleLength), leftColumn, false);
    }

    /** Facts retain positions and depths; only the visible interval creates styled objects. */
    private List<StyleSpan> bracketDecorationSpans(int line, int start, int end, int origin, boolean unmatchedOnly) {
        if (bracketFacts == null || end <= start) return List.of();
        int first = bracketFacts.lowerBound(BracketFacts.position(line, start));
        long after = BracketFacts.position(line, end);
        List<StyleSpan> spans = new ArrayList<>();
        for (int index = first; index < bracketFacts.size && bracketFacts.positions[index] < after; index++) {
            if (unmatchedOnly && !bracketFacts.unmatched.get(index)) continue;
            int column = (int) bracketFacts.positions[index] - origin;
            spans.add(new StyleSpan(column, column + 1,
                unmatchedOnly ? unmatchedBracketStyle : rainbowBracketStyle(bracketFacts.depths[index])));
        }
        return spans;
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

    private Style selectionStyleForLineBreak(
        EditorState state, TextModel model, int lineIndex, int leftVisualColumn, int visibleWidth
    ) {
        if (!state.hasSelection() || visibleWidth <= 0 || lineIndex >= model.lineCount() - 1) {
            return null;
        }
        int firstLine = Math.min(state.getSelectionAnchor().line(), state.getCursor().line());
        int lastLine = Math.max(state.getSelectionAnchor().line(), state.getCursor().line());
        if (lineIndex < firstLine || lineIndex >= lastLine) {
            return null;
        }
        int column = textLayout.visualWidth(lineIndex, tabWidth) - leftVisualColumn;
        return column >= 0 && column < visibleWidth ? selectionStyle : null;
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

        int lineLen = model.lineLength(lineIndex);
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
        SearchHighlights matcher
    ) {
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
        if (a.line() != b.line() || a.line() != lineIndex || b.col() <= a.col()) {
            return null;
        }

        CharSequence line = model.lineText(lineIndex);
        if (!matcher.matchesRange(line, a.col(), b.col())) {
            return null;
        }

        int visStart = Math.max(a.col() - leftColumn, 0);
        int visEnd = Math.min(b.col() - leftColumn, visibleLength);
        if (visEnd <= visStart) {
            return null;
        }
        return new StyleSpan(visStart, visEnd, currentSearchMatchStyle);
    }

    private BracketPair bracketPairForCursor(TextModel model, EditorState state) {
        Position cursor = model.clamp(state.getCursor());
        long revision = model.revision();
        if (bracketCacheRevision == revision
            && cursor.equals(bracketCacheCursor)) {
            return bracketCachePair;
        }
        if (model.lineCount() <= 0) {
            bracketCacheRevision = revision;
            bracketCacheCursor = cursor;
            bracketCachePair = null;
            return null;
        }
        BracketAtCursor at = bracketAtCursor(model, cursor);
        if (at == null) {
            bracketCacheRevision = revision;
            bracketCacheCursor = cursor;
            bracketCachePair = null;
            return null;
        }
        BracketSearch search = switch (at.kind()) {
            case BRACKET -> switch (at.ch()) {
                case '(', '[', '{' -> findBracketForward(model, at.position(), at.ch());
                case ')', ']', '}' -> findBracketBackward(model, at.position(), at.ch());
                default -> new BracketSearch(null, true);
            };
            case QUOTE -> new BracketSearch(findMatchingQuote(model, at.position()), true);
        };
        BracketPair pair = search.complete() ? new BracketPair(at.position(), search.match(), at.kind()) : null;
        bracketCacheRevision = revision;
        bracketCacheCursor = cursor;
        bracketCachePair = pair;
        return pair;
    }

    private void ensureBracketDecorationCache(TextModel model) {
        long revision = model.revision();
        if (bracketFacts != null && bracketFactsRevision == revision) {
            return;
        }
        BracketFacts facts = new BracketFacts();
        BracketStack stack = new BracketStack();
        int scanned = 0;
        boolean complete = true;
        outer:
        for (int line = 0; line < model.lineCount(); line++) {
            CharSequence text = model.lineText(line);
            QuoteMask lexical = quoteMaskForLine(model, line, text);
            if (lexical == null) {
                complete = false;
                break;
            }
            boolean[] quotedMask = lexical.mask();
            for (int col = 0; col < quotedMask.length; col++) {
                if (++scanned > MAX_UNMATCHED_SCAN_CHARS) {
                    complete = false;
                    break outer;
                }
                if (quotedMask[col]) {
                    continue;
                }
                char ch = text.charAt(col);
                if (!isBracket(ch)) {
                    continue;
                }
                if (facts.size == MAX_BRACKET_FACTS || isOpeningBracket(ch) && stack.size == MAX_BRACKET_DEPTH) {
                    complete = false;
                    break outer;
                }
                if (isOpeningBracket(ch)) {
                    int index = facts.add(line, col, ch, stack.size + 1);
                    stack.push(index);
                    continue;
                }
                if (!stack.isEmpty()) {
                    int open = stack.pop();
                    int closing = facts.add(line, col, ch, facts.depths[open]);
                    if (closingBracketFor((char) facts.kinds[open]) != ch) {
                        facts.unmatched.set(open);
                        facts.unmatched.set(closing);
                    }
                } else {
                    facts.unmatched.set(facts.add(line, col, ch, 1));
                }
            }
            if (!lexical.completeLine()) {
                complete = false;
                break;
            }
        }
        if (complete) {
            while (!stack.isEmpty()) facts.unmatched.set(stack.pop());
        }
        bracketFacts = facts;
        bracketFactsRevision = revision;
    }

    int bracketFactCount() {
        return bracketFacts == null ? 0 : bracketFacts.size;
    }

    long bracketFactsStorageBytes() {
        return bracketFacts == null ? 0 : bracketFacts.storageBytes();
    }

    int lexicalCacheSize() {
        return quoteMaskCache.size();
    }

    int lexicalOutputCount() {
        return MAX_LEXICAL_CACHE_OUTPUTS - remainingLexicalOutputs;
    }

    private BracketAtCursor bracketAtCursor(TextModel model, Position cursor) {
        CharSequence line = model.lineText(cursor.line());
        QuoteMask quoteMask = quoteMaskForLine(model, cursor.line(), line);
        if (quoteMask == null) {
            return null;
        }
        boolean[] quotedMask = quoteMask.mask();
        int col = Math.max(0, Math.min(cursor.col(), line.length()));
        if (col < quotedMask.length) {
            char ch = line.charAt(col);
            if (isBracket(ch) && !quotedMask[col]) {
                return new BracketAtCursor(new Position(cursor.line(), col), ch, MatchKind.BRACKET);
            }
            if (isQuote(ch) && quoteRangeAt(quoteMask, col) != null) {
                return new BracketAtCursor(new Position(cursor.line(), col), ch, MatchKind.QUOTE);
            }
        }
        if (col > 0 && col <= quotedMask.length) {
            char ch = line.charAt(col - 1);
            if (isBracket(ch) && !quotedMask[col - 1]) {
                return new BracketAtCursor(new Position(cursor.line(), col - 1), ch, MatchKind.BRACKET);
            }
            if (isQuote(ch) && quoteRangeAt(quoteMask, col - 1) != null) {
                return new BracketAtCursor(new Position(cursor.line(), col - 1), ch, MatchKind.QUOTE);
            }
        }
        return null;
    }

    private Position findMatchingQuote(TextModel model, Position from) {
        CharSequence line = model.lineText(from.line());
        QuoteRange range = quoteRangeAt(quoteMaskForLine(model, from.line(), line), from.col());
        if (range == null || range.end() < 0) {
            return null;
        }
        return new Position(from.line(), from.col() == range.start() ? range.end() : range.start());
    }

    private static QuoteRange quoteRangeAt(QuoteMask mask, int column) {
        if (mask == null) {
            return null;
        }
        for (QuoteRange range : mask.quoteRanges()) {
            if (range.start() == column || range.end() == column) {
                return range;
            }
        }
        return null;
    }

    private static boolean isQuote(char ch) {
        return ch == '"' || ch == '\'';
    }

    private BracketSearch findBracketForward(TextModel model, Position from, char open) {
        BracketStack stack = new BracketStack();
        stack.push(open);
        int scanned = 0;
        for (int line = from.line(); line < model.lineCount(); line++) {
            CharSequence text = model.lineText(line);
            QuoteMask lexical = quoteMaskForLine(model, line, text);
            if (lexical == null) {
                return new BracketSearch(null, false);
            }
            boolean[] quotedMask = lexical.mask();
            int start = line == from.line() ? from.col() + 1 : 0;
            for (int col = start; col < quotedMask.length; col++) {
                if (++scanned > MAX_BRACKET_SCAN_CHARS) {
                    return new BracketSearch(null, false);
                }
                if (quotedMask[col]) {
                    continue;
                }
                char ch = text.charAt(col);
                if (isOpeningBracket(ch)) {
                    if (!stack.push(ch)) return new BracketSearch(null, false);
                } else if (isBracket(ch)) {
                    if (closingBracketFor((char) stack.pop()) != ch) {
                        return new BracketSearch(null, true);
                    }
                    if (stack.isEmpty()) {
                        return new BracketSearch(new Position(line, col), true);
                    }
                }
            }
            if (!lexical.completeLine() || ++scanned > MAX_BRACKET_SCAN_CHARS) {
                return new BracketSearch(null, false);
            }
        }
        return new BracketSearch(null, true);
    }

    private BracketSearch findBracketBackward(TextModel model, Position from, char close) {
        BracketStack stack = new BracketStack();
        stack.push(close);
        int scanned = 0;
        for (int line = from.line(); line >= 0; line--) {
            CharSequence text = model.lineText(line);
            QuoteMask lexical = quoteMaskForLine(model, line, text);
            boolean[] quotedMask = lexical == null ? null : lexical.mask();
            int start = line == from.line() ? from.col() - 1 : text.length() - 1;
            if (quotedMask == null || start >= quotedMask.length) {
                return new BracketSearch(null, false);
            }
            for (int col = start; col >= 0; col--) {
                if (++scanned > MAX_BRACKET_SCAN_CHARS) {
                    return new BracketSearch(null, false);
                }
                if (quotedMask[col]) {
                    continue;
                }
                char ch = text.charAt(col);
                if (isBracket(ch) && !isOpeningBracket(ch)) {
                    if (!stack.push(ch)) return new BracketSearch(null, false);
                } else if (isOpeningBracket(ch)) {
                    if (openingBracketFor((char) stack.pop()) != ch) {
                        return new BracketSearch(null, true);
                    }
                    if (stack.isEmpty()) {
                        return new BracketSearch(new Position(line, col), true);
                    }
                }
            }
            if (!lexical.completeLine() || ++scanned > MAX_BRACKET_SCAN_CHARS) {
                return new BracketSearch(null, false);
            }
        }
        return new BracketSearch(null, true);
    }

    private static boolean isBracket(char ch) {
        return ch == '(' || ch == ')' || ch == '[' || ch == ']' || ch == '{' || ch == '}';
    }

    private static boolean isOpeningBracket(char ch) {
        return ch == '(' || ch == '[' || ch == '{';
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

    private record BracketPair(Position primary, Position match, MatchKind kind) {
    }

    private record BracketSearch(Position match, boolean complete) {
    }

    private record BracketAtCursor(Position position, char ch, MatchKind kind) {
    }

    private enum MatchKind {
        BRACKET,
        QUOTE
    }

    /** Bounded primitive stack, shared by decoration and cursor-pair scans. */
    private static final class BracketStack {
        private int[] values = new int[32];
        private int size;

        private boolean push(int value) {
            if (size == MAX_BRACKET_DEPTH) return false;
            if (size == values.length) {
                values = Arrays.copyOf(values, Math.min(MAX_BRACKET_DEPTH, values.length * 2));
            }
            values[size++] = value;
            return true;
        }

        private int pop() {
            return values[--size];
        }

        private boolean isEmpty() {
            return size == 0;
        }
    }

    /**
     * Sorted source facts contain no styles or per-bracket objects. Capacity is
     * capped before growth; even the transient old/new arrays have a fixed bound.
     */
    private static final class BracketFacts {
        private long[] positions = new long[64];
        private int[] depths = new int[64];
        private byte[] kinds = new byte[64];
        private final BitSet unmatched = new BitSet();
        private int size;

        private int add(int line, int column, char kind, int depth) {
            if (size == positions.length) {
                int capacity = Math.min(MAX_BRACKET_FACTS, positions.length * 2);
                positions = Arrays.copyOf(positions, capacity);
                depths = Arrays.copyOf(depths, capacity);
                kinds = Arrays.copyOf(kinds, capacity);
            }
            int index = size++;
            positions[index] = position(line, column);
            depths[index] = depth;
            kinds[index] = (byte) kind;
            return index;
        }

        private static long position(int line, int column) {
            return ((long) line << 32) | (column & 0xFFFF_FFFFL);
        }

        private int lowerBound(long position) {
            int low = 0;
            int high = size;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (positions[middle] < position) low = middle + 1;
                else high = middle;
            }
            return low;
        }

        private long storageBytes() {
            return 13L * positions.length + unmatched.size() / 8L;
        }
    }

    private record VisualSlice(int startCol, CharSequence text, int sourceVisualColumn, int leadingClippedCells) {
    }

    private record SyntaxKey(int lineIndex, int startUtf16, int endUtf16) {
    }

    private record SyntaxSpans(CharSequence lineText, List<StyleSpan> spans) {
        int weight() {
            return 1 + spans.size();
        }
    }

    private record QuoteRange(int start, int end) {
    }

    private record QuoteMask(CharSequence lineText, boolean[] mask, List<QuoteRange> quoteRanges, boolean completeLine) {
    }

}
