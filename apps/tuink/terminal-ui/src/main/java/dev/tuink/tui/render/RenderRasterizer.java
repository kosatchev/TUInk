package dev.tuink.tui.render;

import dev.tuink.core.GutterDiffFlag;
import dev.tuink.core.GutterDiffMarker;
import dev.tuiframe.text.TerminalText;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.render.PreparedRowCache;
import dev.tuiframe.render.PreparedStyledRow;
import dev.tuiframe.render.PreparedRowPainter;
import dev.tuink.render.BaseStyleIdentity;
import dev.tuink.render.Color;
import dev.tuink.render.LineVisual;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.scroll.ScrollStyle;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.geometry.Rect;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.style.UiStyles.Palette;
import dev.tuink.tui.ui.EditorScrollbars;
import java.util.Arrays;
import java.lang.ref.WeakReference;
import java.util.AbstractList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Converts an abstract render plan into a cell buffer.
 */
public class RenderRasterizer implements AutoCloseable {
    private static final int MAX_PREPARED_LINES = 1024;
    private static final int MAX_PREPARED_CACHE_WEIGHT = 16 * 1024 * 1024;
    private static final int EDITOR_COLOR_COUNT = Color.values().length;
    private static final int CELL_STYLE_VARIANTS = EDITOR_COLOR_COUNT * EDITOR_COLOR_COUNT * 8;
    private static final AtomicLong FALLBACK_PRODUCERS = new AtomicLong(Long.MIN_VALUE);

    private Palette styles;
    private boolean colorEnabled = true;
    private final int contentTopRow;
    private boolean highlightActiveGutter = true;
    private boolean visibleWhitespace = true;
    private int tabWidth = 4;
    private final RenderMemoryBudget.Tracked memory;
    private final PreparedRowCache preparedRows;
    private final LinkedHashMap<FallbackKey, FallbackIdentity> fallbackIdentities = new LinkedHashMap<>(16, 0.75f, true);
    private final long fallbackProducer = FALLBACK_PRODUCERS.getAndIncrement();
    private long fallbackSequence;
    private long preparedLineCacheHits;
    private long preparedLineCacheMisses;
    private final CellStyle[] cellStyleCache = new CellStyle[CELL_STYLE_VARIANTS];
    private final SourceOverlay overlay = new SourceOverlay();
    private PreparedRowCache.Handle[] previousFrameLines = new PreparedRowCache.Handle[0];
    private PreparedRowCache.Handle[] currentFrameLines = new PreparedRowCache.Handle[0];
    private RenderMemoryBudget.Reservation frameArrays;
    private boolean closed;

    public RenderRasterizer() {
        this(1, UiStyles.Theme.CYAN);
    }

    public RenderRasterizer(int contentTopRow) {
        this(contentTopRow, UiStyles.Theme.CYAN);
    }

    public RenderRasterizer(int contentTopRow, UiStyles.Theme theme) {
        this(contentTopRow, theme, RenderMemoryBudget.bounded(MAX_PREPARED_CACHE_WEIGHT));
    }

    /** Injected admission shares the caller's ledger; the default remains the existing 16 MiB ceiling. */
    public RenderRasterizer(int contentTopRow, UiStyles.Theme theme, RenderMemoryBudget memory) {
        this.contentTopRow = Math.max(1, contentTopRow);
        this.styles = UiStyles.forTheme(theme);
        this.memory = RenderMemoryBudget.tracked(memory);
        this.preparedRows = new PreparedRowCache(this.memory, MAX_PREPARED_LINES, MAX_PREPARED_CACHE_WEIGHT);
    }

    public void setTheme(UiStyles.Theme theme) {
        setPalette(UiStyles.forTheme(theme));
    }

    public void setPalette(Palette palette) {
        Objects.requireNonNull(palette, "palette");
        if (styles == palette) return;
        var before = bodyStyle();
        this.styles = palette;
        if (!before.equals(bodyStyle())) invalidatePhysicalStyles();
    }

    public void setColorEnabled(boolean enabled) {
        if (colorEnabled == enabled) return;
        colorEnabled = enabled;
        invalidatePhysicalStyles();
    }

    private CellStyle bodyStyle() {
        return colorEnabled ? styles.windows().renderBody() : CellStyle.DEFAULT;
    }

    private void invalidatePhysicalStyles() {
        Arrays.fill(cellStyleCache, null);
        clearPreparedLineCache();
    }

    public void setHighlightActiveGutter(boolean enabled) {
        this.highlightActiveGutter = enabled;
    }

    public void setVisibleWhitespace(boolean enabled) {
        this.visibleWhitespace = enabled;
    }

    public void setTabWidth(int tabWidth) {
        int normalized = tabWidth <= 2 ? 2 : 4;
        if (this.tabWidth == normalized) {
            return;
        }
        this.tabWidth = normalized;
        clearPreparedLineCache();
    }

    public CellBuffer rasterize(RenderPlan plan, int rows, int cols) {
        return rasterize(plan, rows, cols, contentTopRow);
    }

    public CellBuffer rasterize(RenderPlan plan, int rows, int cols, int contentTopRow) {
        int promptHeight = plan.interactiveBarText() == null ? 0 : plan.interactiveBarText().split("\n", -1).length;
        return rasterize(plan, rows, cols, contentTopRow, WindowTextPainter.Layout.fullWindow(rows, cols,
            promptHeight, plan.notificationBarText() == null ? 0 : 1));
    }

    public CellBuffer rasterize(RenderPlan plan, int rows, int cols, int contentTopRow, WindowTextPainter.Layout textLayout) {
        if (closed) throw new IllegalStateException("Rasterizer is closed");
        CellBuffer buffer = new CellBuffer(rows, cols);
        Canvas canvas = new Canvas(buffer);
        canvas.fill(new Rect(0, 0, cols, rows), bodyStyle());
        Rect editor = plan.editorBounds().intersection(new Rect(0, 0, cols, rows));
        int contentRight = Math.min(editor.rightExclusive(), plan.scrollLayout().contentBounds().rightExclusive());
        int textLeft = plan.scrollLayout().contentBounds().x();
        int topRow = plan.editorBounds().y();
        int activeGutterRow = resolveActiveGutterRow(plan);
        int visibleLineCount = Math.min(plan.lines().size(), plan.contentRows());
        beginPreparedLineFrame(visibleLineCount);

        try {
            for (int row = 0; row < visibleLineCount; row++) {
                LineVisual line = plan.lines().get(row);
                int screenRow = row + topRow;
                if (screenRow >= editor.bottomExclusive()) {
                    break;
                }
                CellStyle gutterStyle = highlightActiveGutter && row == activeGutterRow
                    ? styles.windows().renderGutterActive()
                    : styles.windows().renderGutter();
                drawGutter(buffer, screenRow, editor.x(), line, gutterStyle, Math.min(contentRight, textLeft));
                boolean markLeadingWhitespace = shouldMarkLeadingWhitespace(plan, line);
                drawHighlightedText(
                    canvas,
                    screenRow,
                    textLeft,
                    line,
                    contentRight,
                    markLeadingWhitespace,
                    row
                );
            }
        } finally { endPreparedLineFrame(); }

        drawScrollbars(canvas, plan, rows, cols, topRow);
        WindowTextPainter.paint(canvas, rows, cols,
            new WindowTextPainter.Text(plan.topBarText(), plan.bottomBarText(),
                plan.interactiveBarText(), plan.interactiveBarStyle(),
                plan.notificationBarText(), plan.notificationBarStyle()), styles, textLayout);

        return buffer;
    }

    private void drawScrollbars(Canvas canvas, RenderPlan plan, int rows, int cols, int contentTopRow) {
        EditorScrollbars scrollbars = EditorScrollbars.from(plan, rows, cols, contentTopRow);
        ScrollStyle scrollStyle = ScrollStyle.fromParent(bodyStyle(), styles.windows().renderBorder());
        if (scrollbars.vertical() != null) {
            scrollbars.vertical().draw(canvas, scrollStyle);
        }
        if (scrollbars.horizontal() != null) {
            scrollbars.horizontal().draw(canvas, scrollStyle);
        }
        scrollbars.layout().paintCorner(canvas, scrollStyle);
    }

    private void drawHighlightedText(Canvas canvas, int row, int startCol, LineVisual line, int cols,
        boolean markLeadingWhitespace, int cacheSlot) {
        CharSequence text = line.sequence() == null ? "" : line.sequence();
        List<StyleSpan> spans = line.spans() == null ? List.of() : line.spans();
        int baseCount = Math.min(line.baseSpanCount(), spans.size());
        PreparedRowCache.Handle handle = preparedLine(line, text, spans, baseCount, cacheSlot);
        try {
            overlay.bind(spans, handle == null ? 0 : baseCount);
            PreparedRowPainter.Overlay dynamic = overlay.empty() ? null : overlay;
            int endColumn = handle == null
                ? PreparedRowPainter.paintStreaming(canvas, text, tabWidth, Math.floorMod(line.sourceVisualColumn(), tabWidth),
                    bodyStyle(), row, startCol, Math.max(0, cols - startCol), line.leadingClippedCells(), dynamic,
                    visibleWhitespace && markLeadingWhitespace, visibleWhitespace, styles.windows().renderLeadingWhitespace())
                : PreparedRowPainter.paint(canvas, handle.row(), row, startCol, Math.max(0, cols - startCol),
                    line.leadingClippedCells(), dynamic, visibleWhitespace && markLeadingWhitespace,
                    visibleWhitespace, styles.windows().renderLeadingWhitespace());
            if (endColumn >= 0 && line.lineBreakSelectionStyle() != null) {
                int column = startCol + endColumn - line.leadingClippedCells();
                if (column >= startCol && column < cols) canvas.put(column, row, " ", cellStyle(line.lineBreakSelectionStyle()));
            }
        } finally {
            overlay.clear();
            if (handle != null && (cacheSlot >= currentFrameLines.length || currentFrameLines[cacheSlot] != handle)) handle.close();
        }
    }

    private void drawGutter(CellBuffer buffer, int row, int left, LineVisual line, CellStyle baseStyle, int cols) {
        String gutter = line.gutter();
        if (gutter == null || gutter.isEmpty()) {
            return;
        }
        drawText(buffer, row, left, gutter, baseStyle, cols);
        if (cols <= left) {
            return;
        }
        CellStyle markerStyle = gutterMarkerStyle(line.gutterDiff());
        if (markerStyle == null) {
            return;
        }
        buffer.set(row, left, String.valueOf(gutter.charAt(0)), markerStyle);
    }

    private CellStyle gutterMarkerStyle(GutterDiffMarker marker) {
        if (marker == null || marker.flag() == null) {
            return null;
        }
        GutterDiffFlag diff = marker.flag();
        if (marker.historical()) {
            return switch (diff) {
                case ADDED -> styles.windows().renderGutterAddedHistorical();
                case MODIFIED -> styles.windows().renderGutterModifiedHistorical();
                case DELETED -> styles.windows().renderGutterDeletedHistorical();
                default -> null;
            };
        }
        return switch (diff) {
            case ADDED -> styles.windows().renderGutterAdded();
            case MODIFIED -> styles.windows().renderGutterModified();
            case DELETED -> styles.windows().renderGutterDeleted();
            default -> null;
        };
    }

    private void drawText(CellBuffer buffer, int row, int col, String text, CellStyle style, int cols) {
        int outCol = col;
        String value = text == null ? "" : text;
        TerminalText.Metrics metrics = TerminalText.metrics(value, 1);
        for (int index = 0; index < metrics.graphemeCount(); index++) {
            String safe = displayGrapheme(value, metrics.start(index), metrics.end(index));
            int width = Math.max(1, metrics.width(index));
            if (outCol + width > cols) {
                break;
            }
            buffer.set(row, outCol, safe, style);
            outCol += width;
        }
    }

    private int resolveActiveGutterRow(RenderPlan plan) {
        int cursorRow = Math.max(0, Math.min(plan.cursorRow(), Math.max(0, plan.lines().size() - 1)));
        if (plan.lines().isEmpty()) {
            return cursorRow;
        }
        if (hasLineNumberGutter(plan.lines().get(cursorRow).gutter())) {
            return cursorRow;
        }
        for (int row = cursorRow - 1; row >= 0; row--) {
            if (hasLineNumberGutter(plan.lines().get(row).gutter())) {
                return row;
            }
        }
        return cursorRow;
    }

    private boolean hasLineNumberGutter(String gutter) {
        if (gutter == null || gutter.isBlank()) {
            return false;
        }
        String trimmed = gutter.trim();
        return !trimmed.isEmpty() && !"~".equals(trimmed);
    }

    private boolean shouldMarkLeadingWhitespace(RenderPlan plan, LineVisual line) {
        if (!plan.wrapLines()) {
            return plan.leftColumn() == 0;
        }
        return hasLineNumberGutter(line.gutter());
    }

    private PreparedRowCache.Handle preparedLine(LineVisual line, CharSequence text, List<StyleSpan> spans,
        int baseCount, int cacheSlot) {
        int phase = Math.floorMod(line.sourceVisualColumn(), tabWidth);
        PreparedStyledRow.Key key = line.baseStyleKey() == null ? fallbackIdentity(text, spans, baseCount, phase)
            : line.baseStyleKey().preparedRowKey(tabWidth, phase);
        if (key == null) { preparedLineCacheMisses++; return null; }
        PreparedRowCache.Handle frameCached = takePreviousFrame(key, cacheSlot);
        if (frameCached == null) frameCached = takePreviousFrame(key, cacheSlot + 1);
        if (frameCached == null) frameCached = takePreviousFrame(key, cacheSlot - 1);
        PreparedRowCache.Handle handle;
        if (frameCached != null) {
            preparedLineCacheHits++;
            handle = frameCached;
        } else {
            long hits = preparedRows.hits();
            handle = preparedRows.acquire(key, text, bodyStyle(), new SourceBaseSpans(text, spans, baseCount));
            if (preparedRows.hits() != hits) preparedLineCacheHits++;
            else preparedLineCacheMisses++;
        }
        if (cacheSlot < currentFrameLines.length) currentFrameLines[cacheSlot] = handle;
        return handle;
    }

    private PreparedRowCache.Handle takePreviousFrame(PreparedStyledRow.Key key, int slot) {
        if (slot < 0 || slot >= previousFrameLines.length) return null;
        var handle = previousFrameLines[slot];
        if (handle == null || !key.equals(handle.key())) return null;
        // Transfer the frame's real ownership, avoiding a new handle/lease allocation per unchanged row.
        previousFrameLines[slot] = null;
        return handle;
    }

    private PreparedStyledRow.Key fallbackIdentity(CharSequence text, List<StyleSpan> spans, int count, int phase) {
        List<StyleSpan> base = count == 0 ? List.of() : count == spans.size() ? spans : spans.subList(0, count);
        var lookup = new FallbackKey(text, base, tabWidth, phase, false);
        var found = fallbackIdentities.get(lookup);
        if (found != null) return found.key();
        while (fallbackIdentities.size() >= MAX_PREPARED_LINES) evictFallbackIdentity();
        long bytes = 256L + 2L * text.length() + 64L * count;
        var reservation = memory.tryReserve(bytes);
        if (reservation == null) {
            preparedRows.clear();
            reservation = memory.tryReserve(bytes);
            while (reservation == null && !fallbackIdentities.isEmpty()) {
                evictFallbackIdentity(); reservation = memory.tryReserve(bytes);
            }
        }
        if (reservation == null) return null;
        try {
            fallbackSequence = Math.incrementExact(fallbackSequence);
            var key = new PreparedStyledRow.Key(fallbackProducer, fallbackSequence,
                0, 0, 0, 0, text.length(), tabWidth, phase);
            fallbackIdentities.put(new FallbackKey(text, List.copyOf(base), tabWidth, phase, true),
                new FallbackIdentity(key, reservation));
            return key;
        } catch (RuntimeException | Error failure) { reservation.close(); throw failure; }
    }

    private void evictFallbackIdentity() {
        var iterator = fallbackIdentities.entrySet().iterator();
        var entry = iterator.next(); iterator.remove(); entry.getValue().reservation().close();
    }

    private void beginPreparedLineFrame(int count) {
        closeFrame(currentFrameLines);
        if (currentFrameLines.length >= count) return;
        int capacity = Math.max(count, currentFrameLines.length * 2 + 16);
        long bytes = 64L + 16L * capacity;
        var reservation = memory.tryReserve(bytes);
        if (reservation == null) {
            preparedRows.clear();
            reservation = memory.tryReserve(bytes);
        }
        if (reservation == null) {
            closeFrame(previousFrameLines);
            if (frameArrays != null) { frameArrays.close(); frameArrays = null; }
            previousFrameLines = new PreparedRowCache.Handle[0]; currentFrameLines = new PreparedRowCache.Handle[0];
            reservation = memory.tryReserve(bytes);
        }
        if (reservation == null) return;
        try {
            var previous = Arrays.copyOf(previousFrameLines, capacity);
            var current = new PreparedRowCache.Handle[capacity];
            previousFrameLines = previous; currentFrameLines = current;
            if (frameArrays != null) frameArrays.close();
            frameArrays = reservation;
        } catch (RuntimeException | Error failure) { reservation.close(); throw failure; }
    }

    private void endPreparedLineFrame() {
        closeFrame(previousFrameLines);
        var completed = currentFrameLines; currentFrameLines = previousFrameLines; previousFrameLines = completed;
    }

    private static void closeFrame(PreparedRowCache.Handle[] frame) {
        for (int index = 0; index < frame.length; index++) {
            if (frame[index] != null) { frame[index].close(); frame[index] = null; }
        }
    }

    private void clearPreparedLineCache() {
        preparedRows.clear();
        for (var identity : fallbackIdentities.values()) identity.reservation().close();
        fallbackIdentities.clear();
        closeFrame(previousFrameLines); closeFrame(currentFrameLines);
        previousFrameLines = new PreparedRowCache.Handle[0]; currentFrameLines = new PreparedRowCache.Handle[0];
        if (frameArrays != null) { frameArrays.close(); frameArrays = null; }
    }

    @Override public void close() {
        if (closed) return;
        closed = true; clearPreparedLineCache(); preparedRows.close();
    }

    /** Rows reused from the previous frame or the prepared-row LRU. */
    public long preparedRowsReused() { return preparedLineCacheHits; }
    public long preparedRowsMisses() { return preparedLineCacheMisses; }
    /** Successful metric-index builds for Source rows; excludes requests, hits and refusals. */
    public long metricsBuilds() { return preparedRows.metricsBuilds(); }
    /** Live row, fallback-identity and frame-slot reservations, including evicted frame owners. */
    public long retainedRenderBytes() { return memory.usedBytes(); }

    int preparedLineCacheSize() { return preparedRows.size(); }
    long preparedLineCacheHits() { return preparedLineCacheHits; }
    long preparedLineCacheMisses() { return preparedLineCacheMisses; }

    private final class SourceBaseSpans extends AbstractList<PreparedStyledRow.Span> {
        private final CharSequence text;
        private final List<StyleSpan> spans;
        private final int count;
        SourceBaseSpans(CharSequence text, List<StyleSpan> spans, int count) { this.text = text; this.spans = spans; this.count = count; }
        @Override public int size() { return count; }
        @Override public PreparedStyledRow.Span get(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
            var span = spans.get(index);
            int start = span.start(), end = span.end();
            if (start == end && start > 0 && start < text.length()) {
                var geometry = TerminalText.streamingGeometry(text, tabWidth);
                start = geometry.floorBoundary(start); end = geometry.ceilBoundary(end);
            }
            return new PreparedStyledRow.Span(start, end, cellStyle(span.style()));
        }
    }

    private final class SourceOverlay implements PreparedRowPainter.Overlay {
        private List<StyleSpan> spans = List.of();
        private int first;
        void bind(List<StyleSpan> spans, int first) { this.spans = spans; this.first = first; }
        boolean empty() { return first == spans.size(); }
        void clear() { spans = List.of(); first = 0; }
        @Override public CellStyle resolve(int start, int end, CellStyle base) {
            CellStyle result = base;
            for (int index = first; index < spans.size(); index++) {
                var span = spans.get(index);
                if (span.end() > start && span.start() < end) result = cellStyle(span.style());
            }
            return result;
        }
    }

    private record FallbackIdentity(PreparedStyledRow.Key key, RenderMemoryBudget.Reservation reservation) {}

    /** Exact text/span lookup for unversioned producers, never a hash promoted to identity. */
    private static final class FallbackKey {
        private final Object text;
        private final List<StyleSpan> spans;
        private final int tabWidth, phase, hash;
        FallbackKey(CharSequence value, List<StyleSpan> spans, int tabWidth, int phase, boolean retain) {
            text = value instanceof String ? value : retain ? new WeakReference<>(value) : value;
            this.spans = spans; this.tabWidth = tabWidth; this.phase = phase;
            int textHash = value instanceof String string ? string.hashCode() : System.identityHashCode(value);
            hash = 31 * (31 * (31 * textHash + spans.hashCode()) + tabWidth) + phase;
        }
        private Object value() { return text instanceof WeakReference<?> weak ? weak.get() : text; }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof FallbackKey key) || tabWidth != key.tabWidth || phase != key.phase || !spans.equals(key.spans)) return false;
            Object first = value(), second = key.value();
            return first != null && (first == second || first instanceof String && first.equals(second));
        }
    }

    private CellStyle cellStyle(Style style) {
        if (style.equals(Style.DEFAULT)) {
            return bodyStyle();
        }
        int attributes = (style.inverse() ? 4 : 0) | (style.bold() ? 2 : 0) | (style.underline() ? 1 : 0);
        int index = ((style.fg().ordinal() * EDITOR_COLOR_COUNT + style.bg().ordinal()) << 3)
            | attributes;
        CellStyle cached = cellStyleCache[index];
        if (cached != null) {
            return cached;
        }
        CellStyle raw = EditorStyleAdapter.toCell(style);
        CellStyle body = bodyStyle();
        CellStyle mapped = new CellStyle(raw.fg() == CellColor.DEFAULT ? body.fg() : raw.fg(),
            raw.bg() == CellColor.DEFAULT ? body.bg() : raw.bg(), raw.bold(), raw.underline(), raw.inverse());
        cellStyleCache[index] = mapped;
        return mapped;
    }

    private static String displayGrapheme(CharSequence text, int start, int end) {
        return PreparedRowPainter.display(text, start, end);
    }
}
