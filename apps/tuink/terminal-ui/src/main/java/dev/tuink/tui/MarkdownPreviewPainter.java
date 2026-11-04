package dev.tuink.tui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.scroll.ScrollStyle;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.render.SyntaxStyleResolver;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuiframe.text.TerminalText;
import dev.tuiframe.render.PreparedRowPainter;
import dev.tuiframe.render.PreparedStyledRow;
import java.util.AbstractList;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Paints a prepared, immutable preview placement. */
final class MarkdownPreviewPainter {
    private static final SyntaxStyleResolver SYNTAX_STYLES = SyntaxStyleResolver.standard();
    private MarkdownPreviewPainter() {}
    static void paint(Canvas canvas, MarkdownPreviewState.Placement placement, UiStyles.Palette palette, boolean color) {
        var bounds = placement.bounds(); int mark = canvas.save();
        try {
            canvas.clip(bounds); canvas.fill(bounds, palette.renderBody());
            if (placement.searchEditing()) {
                CellText.drawClipped(canvas, bounds.x(), bounds.y(), MarkdownPreviewState.SEARCH_PREFIX,
                    palette.renderStatus(), bounds.width());
                if (placement.searchInput() != null) {
                    placement.searchInput().paint(canvas, role -> WidgetThemeKeys.TEXT_FIELD_SELECTION.equals(role)
                        ? EditorStyleAdapter.toCell(palette.renderSelection()) : palette.renderStatus(), new WidgetState(true, true));
                } else {
                    int prefix = CellText.width(MarkdownPreviewState.SEARCH_PREFIX);
                    CellText.drawClipped(canvas, bounds.x() + prefix, bounds.y(), placement.searchQuery(),
                        palette.renderStatus(), Math.max(0, bounds.width() - prefix));
                }
            }
            var scrollbars = placement.scrollbars();
            var content = scrollbars.layout().contentBounds();
            if (!placement.error().isEmpty()) {
                CellText.drawClipped(canvas, content.x(), content.y(), placement.error(), palette.notifyError(), content.width());
                return;
            }
            var result = placement.result();
            if (result == null) return;
            var overlay = new PreviewOverlay(placement, palette, color);
            int contentMark = canvas.save();
            try {
                canvas.clip(content);
                for (int y = 0; y < content.height(); y++) {
                    int index = placement.top() + y;
                    if (index >= result.lines().size()) break;
                    var line = result.lines().get(index);
                    paintLine(canvas, line, placement, content, index, content.y() + y, palette, color, overlay);
                }
            } finally { canvas.restore(contentMark); }
            ScrollStyle scrollStyle = ScrollStyle.fromParent(palette.renderBody(), palette.renderBorder());
            if (scrollbars.vertical() != null) scrollbars.vertical().draw(canvas, scrollStyle);
            if (scrollbars.horizontal() != null) scrollbars.horizontal().draw(canvas, scrollStyle);
            scrollbars.layout().paintCorner(canvas, scrollStyle);
        } finally { canvas.restore(mark); }
    }

    /** Terminal-global cursor for either the real query field or the readonly visual caret. */
    static Point cursor(MarkdownPreviewState.Placement placement) {
        if (placement.searchInput() != null) return placement.searchInput().cursorPosition(
            new FocusContext(true, true, FocusSpace.root(placement.bounds()))).orElse(null);
        if (placement.result() == null || placement.caret() == null) return null;
        var content = placement.contentBounds();
        int x = content.x() + placement.caret().column() - placement.left();
        int y = content.y() + placement.caret().row() - placement.top();
        return content.contains(x, y) ? new Point(x, y) : null;
    }

    private static final int COLOR_COUNT = CellColor.values().length;

    /** Stable rows share admitted geometry; selection/search/caret remain transient adapter effects. */
    private static void paintLine(Canvas canvas, MarkdownLayout.Line line, MarkdownPreviewState.Placement placement,
        dev.tuiframe.geometry.Rect content, int lineIndex, int row, UiStyles.Palette palette, boolean color, PreviewOverlay overlay) {
        int columns = content.width();
        if (columns == 0) return;
        int left = placement.left();
        int endColumn;
        var geometry = placement.geometry();
        try (var admitted = geometry == null ? null : geometry.acquire(lineIndex)) {
            if (admitted == null) {
                overlay.bind(line, lineIndex, 0, TerminalText.streamingGeometry(line.text(), 4), true);
                endColumn = PreparedRowPainter.paintStreaming(canvas, line.text(), 4, 0, palette.renderBody(),
                    row, content.x(), columns, left, overlay, false, false, null);
            } else {
                var metrics = admitted.metrics();
                int from = metrics.offsetForVisualColumn(left, TerminalText.Bias.FLOOR);
                int to = metrics.offsetForVisualColumn(left + columns, TerminalText.Bias.CEIL);
                int visualStart = metrics.visualColumnAt(from);
                var key = new PreparedStyledRow.Key(geometry.identity(), geometry.identity(), geometry.revision(),
                    styleRevision(palette.renderBody(), color), lineIndex, from, to - from, 4, visualStart % 4);
                try (var prepared = geometry.rows().acquire(key, admitted, from, to, palette.renderBody(),
                    new BaseSpans(line, metrics, from, to, overlay))) {
                    overlay.bind(line, lineIndex, from, metrics, prepared == null);
                    if (prepared != null) PreparedRowPainter.paint(canvas, prepared.row(), row,
                        content.x(), columns, left - visualStart, overlay, false, false, null);
                    else PreparedRowPainter.paintGeometry(canvas, metrics.view(from, to), palette.renderBody(), row,
                        content.x(), columns, left - visualStart, overlay);
                }
                endColumn = metrics.visualWidth();
            }
        }
        if (placement.searchInput() == null && placement.caret() != null && placement.caret().row() == lineIndex
            && endColumn >= 0 && placement.caret().column() >= endColumn && placement.caret().column() >= left
            && placement.caret().column() < left + columns) {
            CellStyle body = palette.renderBody();
            canvas.put(content.x() + placement.caret().column() - left, row, " ",
                new CellStyle(body.fg(), body.bg(), true, true));
        }
    }

    private static long styleRevision(CellStyle body, boolean color) {
        return ((long) (body.fg().ordinal() * COLOR_COUNT + body.bg().ordinal()) << 3)
            | (body.bold() ? 4L : 0L) | (body.underline() ? 2L : 0L) | (color ? 1L : 0L);
    }

    /** Materializes mapped spans only inside the admitted cache factory, never on a cache hit. */
    private static final class BaseSpans extends AbstractList<PreparedStyledRow.Span> {
        private final MarkdownLayout.Line line;
        private final TerminalText.Geometry geometry;
        private final int from, to;
        private final int syntaxFrom, syntaxTo;
        private final PreviewOverlay styles;
        BaseSpans(MarkdownLayout.Line line, TerminalText.Geometry geometry, int from, int to, PreviewOverlay styles) {
            this.line = line; this.geometry = geometry; this.from = from; this.to = to; this.styles = styles;
            int low = 0, high = line.syntax().size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (line.syntax().get(middle).end() <= from) low = middle + 1;
                else high = middle;
            }
            syntaxFrom = low;
            high = line.syntax().size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (line.syntax().get(middle).start() < to) low = middle + 1;
                else high = middle;
            }
            syntaxTo = low;
        }
        @Override public int size() { return line.runs().size() + syntaxTo - syntaxFrom; }
        @Override public PreparedStyledRow.Span get(int index) {
            if (index >= line.runs().size()) {
                var token = line.syntax().get(syntaxFrom + index - line.runs().size());
                int start = Math.max(from, Math.min(to, geometry.ceilBoundary(token.start())));
                int end = Math.max(start, Math.min(to, geometry.ceilBoundary(token.end())));
                return new PreparedStyledRow.Span(start - from, end - from, styles.syntax(token.styleKey()));
            }
            // Earlier runs own overlapping starts in the original renderer; later generic spans win.
            var run = line.runs().get(line.runs().size() - 1 - index);
            int start = Math.max(from, Math.min(to, geometry.ceilBoundary(run.start())));
            int end = Math.max(start, Math.min(to, geometry.ceilBoundary(run.end())));
            return new PreparedStyledRow.Span(start - from, end - from, styles.role(run.role()));
        }
    }

    private static final class PreviewOverlay implements PreparedRowPainter.Overlay {
        private final MarkdownPreviewState.Placement placement;
        private final UiStyles.Palette palette;
        private final boolean color;
        private final CellStyle selected, search, currentSearch;
        private final PreviewSelection.Range selection, currentMatch;
        private final CellStyle[] roles = new CellStyle[MarkdownLayout.Role.values().length];
        private final Map<SemanticStyleKey, CellStyle> syntaxStyles = new HashMap<>();
        private MarkdownLayout.Line line;
        private int offset, runIndex, syntaxIndex, caretStart;
        private boolean rebuildBase;

        PreviewOverlay(MarkdownPreviewState.Placement placement, UiStyles.Palette palette, boolean color) {
            this.placement = placement; this.palette = palette; this.color = color;
            selected = EditorStyleAdapter.toCell(palette.renderSelection());
            search = EditorStyleAdapter.toCell(palette.renderSearchMatch());
            currentSearch = EditorStyleAdapter.toCell(palette.renderCurrentSearchMatch());
            selection = placement.selection().selected() ? placement.selection().range() : null;
            currentMatch = placement.activeMatch() >= 0 && placement.activeMatch() < placement.searchMatches().size()
                ? placement.searchMatches().get(placement.activeMatch()) : null;
        }
        CellStyle role(MarkdownLayout.Role role) {
            var style = roles[role.ordinal()];
            if (style == null) roles[role.ordinal()] = style = MarkdownPreviewPainter.style(role, palette.renderBody(), color);
            return style;
        }
        CellStyle syntax(SemanticStyleKey key) {
            var cached = syntaxStyles.get(key);
            if (cached != null) return cached;
            var body = palette.renderBody();
            var mapped = EditorStyleAdapter.toCell(SYNTAX_STYLES.resolve(key));
            var style = new CellStyle(color && mapped.fg() != CellColor.DEFAULT ? mapped.fg() : body.fg(),
                body.bg(), mapped.bold(), mapped.underline());
            if (syntaxStyles.size() < 32) syntaxStyles.put(key, style);
            return style;
        }
        void bind(MarkdownLayout.Line line, int row, int offset, TerminalText.Geometry metrics, boolean rebuildBase) {
            this.line = line; this.offset = offset; this.rebuildBase = rebuildBase; runIndex = 0; syntaxIndex = 0;
            while (runIndex < line.runs().size() && line.runs().get(runIndex).end() <= offset) runIndex++;
            while (syntaxIndex < line.syntax().size() && line.syntax().get(syntaxIndex).end() <= offset) syntaxIndex++;
            caretStart = placement.searchInput() == null && placement.caret() != null && placement.caret().row() == row
                ? metrics.offsetForVisualColumn(placement.caret().column(), TerminalText.Bias.FLOOR) : -1;
        }
        @Override public CellStyle resolve(int start, int end, CellStyle base) {
            int absoluteStart = offset + start, absoluteEnd = offset + end;
            if (rebuildBase || selection != null || currentMatch != null || !placement.searchMatches().isEmpty()) {
                while (runIndex < line.runs().size() && line.runs().get(runIndex).end() <= absoluteStart) runIndex++;
                var run = runIndex < line.runs().size() ? line.runs().get(runIndex) : null;
                if (run != null && run.start() > absoluteStart) run = null;
                if (rebuildBase) {
                    base = run == null ? palette.renderBody() : role(run.role());
                    while (syntaxIndex < line.syntax().size() && line.syntax().get(syntaxIndex).end() <= absoluteStart) syntaxIndex++;
                    var token = syntaxIndex < line.syntax().size() ? line.syntax().get(syntaxIndex) : null;
                    if (token != null && token.start() <= absoluteStart) base = syntax(token.styleKey());
                }
                if (run != null && run.projectionStart() >= 0) {
                    int from = run.projectionStart(), to = run.projectionEnd();
                    if (PreviewSelection.linear(run)) {
                        from += Math.max(0, absoluteStart - run.start());
                        to = Math.min(to, from + absoluteEnd - absoluteStart);
                    }
                    if (overlaps(placement.searchMatches(), from, to)) base = search;
                    if (currentMatch != null && currentMatch.intersects(from, to)) base = currentSearch;
                    if (selection != null && selection.intersects(from, to)) base = selected;
                }
            }
            if (absoluteStart == caretStart) base = new CellStyle(base.fg(), base.bg(), true, true);
            return base;
        }
    }

    private static boolean overlaps(List<PreviewSelection.Range> ranges, int start, int end) {
        int low = 0, high = ranges.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ranges.get(middle).end() <= start) low = middle + 1;
            else high = middle;
        }
        return low < ranges.size() && ranges.get(low).start() < end;
    }
    private static CellStyle style(MarkdownLayout.Role role, CellStyle base, boolean color) {
        boolean bold = role == MarkdownLayout.Role.HEADING || role == MarkdownLayout.Role.STRONG;
        boolean underline = role == MarkdownLayout.Role.LINK || role == MarkdownLayout.Role.EMPHASIS;
        CellColor fg = !color ? base.fg() : switch (role) {
            case HEADING, LINK -> CellColor.CYAN;
            case CODE -> CellColor.YELLOW;
            case MATH -> CellColor.GREEN;
            case DIAGNOSTIC -> CellColor.RED;
            case QUOTE -> CellColor.BRIGHT_BLACK;
            default -> base.fg();
        };
        return new CellStyle(fg, base.bg(), bold, underline);
    }
}
