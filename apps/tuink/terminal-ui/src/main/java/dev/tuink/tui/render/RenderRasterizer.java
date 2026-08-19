package dev.tuink.tui.render;

import dev.tuink.core.GutterDiffFlag;
import dev.tuink.core.GutterDiffMarker;
import dev.tuink.terminal.text.TerminalText;
import dev.tuink.render.BaseStyleIdentity;
import dev.tuink.render.Color;
import dev.tuink.render.LineVisual;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.scroll.HorizontalScrollbar;
import dev.tuink.terminal.scroll.VerticalScrollbar;
import dev.tuink.terminal.style.CellStyle;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.style.UiStyles.Palette;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an abstract render plan into a cell buffer.
 */
public class RenderRasterizer {
    private static final int MAX_PREPARED_LINES = 1024;
    private static final int MAX_PREPARED_CACHE_WEIGHT = 16 * 1024 * 1024;
    private static final int EDITOR_COLOR_COUNT = Color.values().length;
    private static final int CELL_STYLE_VARIANTS = EDITOR_COLOR_COUNT * EDITOR_COLOR_COUNT * 4;
    private static final String[] ASCII_DISPLAY = createAsciiDisplay();

    private Palette styles;
    private final int contentTopRow;
    private boolean highlightActiveGutter = true;
    private boolean visibleWhitespace = true;
    private int tabWidth = 4;
    private final LinkedHashMap<PreparedLineKey, PreparedLine> preparedLineCache =
        new LinkedHashMap<>(256, 0.75f, true);
    private int preparedLineCacheWeight;
    private long preparedLineCacheHits;
    private long preparedLineCacheMisses;
    private final CellStyle[] cellStyleCache = new CellStyle[CELL_STYLE_VARIANTS];
    private CellStyle[] styleScratch = new CellStyle[0];
    private PreparedLineKey[] previousFrameLineKeys = new PreparedLineKey[0];
    private PreparedLine[] previousFrameLines = new PreparedLine[0];
    private PreparedLineKey[] currentFrameLineKeys = new PreparedLineKey[0];
    private PreparedLine[] currentFrameLines = new PreparedLine[0];

    public RenderRasterizer() {
        this(1, UiStyles.Theme.CYAN);
    }

    public RenderRasterizer(int contentTopRow) {
        this(contentTopRow, UiStyles.Theme.CYAN);
    }

    public RenderRasterizer(int contentTopRow, UiStyles.Theme theme) {
        this.contentTopRow = Math.max(1, contentTopRow);
        this.styles = UiStyles.forTheme(theme);
    }

    public void setTheme(UiStyles.Theme theme) {
        this.styles = UiStyles.forTheme(theme);
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
        CellBuffer buffer = new CellBuffer(rows, cols);
        Canvas canvas = new Canvas(buffer);
        int contentCols = Math.max(1, cols - plan.scrollbarWidth());
        int topRow = Math.max(1, contentTopRow);
        int activeGutterRow = resolveActiveGutterRow(plan);
        int visibleLineCount = Math.min(plan.lines().size(), plan.contentRows());
        beginPreparedLineFrame(visibleLineCount);

        for (int row = 0; row < visibleLineCount; row++) {
            LineVisual line = plan.lines().get(row);
            int screenRow = row + topRow;
            if (screenRow >= rows) {
                break;
            }
            CellStyle gutterStyle = highlightActiveGutter && row == activeGutterRow
                ? styles.renderGutterActive()
                : styles.renderGutter();
            drawGutter(buffer, screenRow, line, gutterStyle, contentCols);
            boolean markLeadingWhitespace = shouldMarkLeadingWhitespace(plan, line);
            drawHighlightedText(
                buffer,
                screenRow,
                plan.gutterWidth(),
                line,
                contentCols,
                markLeadingWhitespace,
                row
            );
        }
        endPreparedLineFrame();

        int statusRow = Math.max(0, rows - 1);

        drawText(buffer, 0, 0, truncateOrPad(plan.topBarText(), cols), styles.renderTopbar(), cols);
        drawText(buffer, statusRow, 0, truncateOrPad(plan.bottomBarText(), cols), styles.renderStatus(), cols);
        drawScrollbar(canvas, plan, rows, cols, topRow);
        drawHorizontalScrollbar(canvas, plan, rows, cols, topRow);
        int overlayBottomRow = rows - 2;
        int overlayTopRow = overlayBottomRow;
        if (plan.interactiveBarText() != null && overlayBottomRow >= 0) {
            CellStyle style = plan.interactiveBarStyle() == null
                ? styles.renderInteractive()
                : cellStyle(plan.interactiveBarStyle());
            String[] lines = splitLines(plan.interactiveBarText());
            for (int i = lines.length - 1; i >= 0; i--) {
                int row = overlayBottomRow - (lines.length - 1 - i);
                if (row < 0) {
                    continue;
                }
                drawText(buffer, row, 0, truncateOrPad(lines[i], cols), style, cols);
            }
            overlayTopRow = overlayBottomRow - lines.length;
        }
        if (plan.notificationBarText() != null && overlayTopRow >= 0) {
            CellStyle style = plan.notificationBarStyle() == null
                ? styles.renderNotification()
                : cellStyle(plan.notificationBarStyle());
            String[] lines = splitLines(plan.notificationBarText());
            for (int i = lines.length - 1; i >= 0; i--) {
                int row = overlayTopRow - (lines.length - 1 - i);
                if (row < 0) {
                    continue;
                }
                drawText(buffer, row, 0, truncateOrPad(lines[i], cols), style, cols);
            }
        }

        return buffer;
    }

    private void drawScrollbar(Canvas canvas, RenderPlan plan, int rows, int cols, int contentTopRow) {
        if (plan.scrollbarWidth() == 0 || cols <= 0 || rows <= 2) {
            return;
        }

        int barCol = cols - 1;
        int trackTop = contentTopRow;
        int trackHeight = Math.min(plan.contentRows(), Math.max(0, rows - contentTopRow - 1));
        if (trackHeight <= 0) {
            return;
        }
        VerticalScrollbar.draw(
            canvas,
            new Rect(barCol, trackTop, 1, trackHeight),
            Math.max(1, plan.totalLines()),
            Math.max(0, plan.topLine()),
            "│",
            styles.renderScrollTrack(),
            "█",
            styles.renderScrollThumb()
        );
    }

    private void drawHorizontalScrollbar(Canvas canvas, RenderPlan plan, int rows, int cols, int contentTopRow) {
        if (plan.wrapLines()) {
            return;
        }
        if (rows <= 2 || cols <= 0) {
            return;
        }
        int visibleCols = Math.max(0, cols - plan.gutterWidth() - plan.scrollbarWidth());
        if (visibleCols <= 0) {
            return;
        }
        if (plan.totalColumns() <= visibleCols && plan.leftVisualColumn() <= 0) {
            return;
        }
        int row = contentTopRow + plan.contentRows();
        if (row < contentTopRow || row >= rows - 1) {
            return;
        }
        HorizontalScrollbar.draw(
            canvas,
            new Rect(plan.gutterWidth(), row, visibleCols, 1),
            Math.max(visibleCols, plan.totalColumns()),
            Math.max(0, plan.leftVisualColumn()),
            "─",
            styles.renderScrollTrack(),
            "█",
            styles.renderScrollThumb()
        );
    }

    private void drawHighlightedText(
        CellBuffer buffer,
        int row,
        int startCol,
        LineVisual line,
        int cols,
        boolean markLeadingWhitespace,
        int cacheSlot
    ) {
        List<StyleSpan> spans = line.spans();
        int baseSpanCount = Math.max(0, Math.min(line.baseSpanCount(), spans.size()));
        List<StyleSpan> baseSpans = baseSpanCount == 0
            ? List.of()
            : (baseSpanCount == spans.size() ? spans : spans.subList(0, baseSpanCount));
        PreparedLine prepared = preparedLine(
            line.text(),
            baseSpans,
            line.sourceVisualColumn(),
            line.baseStyleKey(),
            cacheSlot
        );
        int graphemeCount = prepared.metrics().graphemeCount();
        CellStyle[] resolvedStyles = prepared.baseStyles();
        if (baseSpanCount < spans.size()) {
            ensureStyleScratch(graphemeCount);
            System.arraycopy(prepared.baseStyles(), 0, styleScratch, 0, graphemeCount);
            applySpans(
                prepared.metrics(),
                spans,
                baseSpanCount,
                spans.size(),
                styleScratch
            );
            resolvedStyles = styleScratch;
        }

        for (int index = 0; index < graphemeCount; index++) {
            CellStyle style = resolvedStyles[index];
            boolean mark = visibleWhitespace
                && (
                    index >= prepared.trailingStart()
                        || (markLeadingWhitespace && index < prepared.leadingEnd())
                )
                && (prepared.exactSpaces()[index] || prepared.tabs()[index]);
            if (mark) {
                style = markerStyle(style);
            }
            int screenCol = startCol
                + prepared.visualOffsets()[index]
                - line.leadingClippedCells();
            int advance = prepared.advances()[index];

            if (prepared.tabs()[index]) {
                for (int cell = 0; cell < advance; cell++) {
                    int target = screenCol + cell;
                    if (target < startCol || target >= cols) {
                        continue;
                    }
                    String shown = mark && cell == 0 && screenCol >= startCol ? "»" : " ";
                    buffer.set(row, target, shown, style);
                }
            } else if (screenCol >= startCol && screenCol + advance <= cols) {
                String shown = mark && prepared.exactSpaces()[index]
                    ? "·"
                    : prepared.display()[index];
                buffer.set(row, screenCol, shown, style);
            }
        }
    }

    private void drawGutter(CellBuffer buffer, int row, LineVisual line, CellStyle baseStyle, int cols) {
        String gutter = line.gutter();
        if (gutter == null || gutter.isEmpty()) {
            return;
        }
        drawText(buffer, row, 0, gutter, baseStyle, cols);
        if (cols <= 0) {
            return;
        }
        CellStyle markerStyle = gutterMarkerStyle(line.gutterDiff());
        if (markerStyle == null) {
            return;
        }
        buffer.set(row, 0, String.valueOf(gutter.charAt(0)), markerStyle);
    }

    private CellStyle gutterMarkerStyle(GutterDiffMarker marker) {
        if (marker == null || marker.flag() == null) {
            return null;
        }
        GutterDiffFlag diff = marker.flag();
        if (marker.historical()) {
            return switch (diff) {
                case ADDED -> styles.renderGutterAddedHistorical();
                case MODIFIED -> styles.renderGutterModifiedHistorical();
                case DELETED -> styles.renderGutterDeletedHistorical();
                default -> null;
            };
        }
        return switch (diff) {
            case ADDED -> styles.renderGutterAdded();
            case MODIFIED -> styles.renderGutterModified();
            case DELETED -> styles.renderGutterDeleted();
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

    private String truncateOrPad(String text, int width) {
        if (width <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int used = 0;
        String value = text == null ? "" : text;
        TerminalText.Metrics metrics = TerminalText.metrics(value, 1);
        for (int index = 0; index < metrics.graphemeCount(); index++) {
            String safe = displayGrapheme(value, metrics.start(index), metrics.end(index));
            int cellWidth = Math.max(1, metrics.width(index));
            if (used + cellWidth > width) {
                break;
            }
            out.append(safe);
            used += cellWidth;
        }
        return out.append(" ".repeat(Math.max(0, width - used))).toString();
    }

    private String[] splitLines(String text) {
        if (text == null) {
            return new String[0];
        }
        return text.split("\\n", -1);
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

    private CellStyle markerStyle(CellStyle base) {
        CellStyle marker = styles.renderLeadingWhitespace();
        if (base.equals(CellStyle.DEFAULT)) {
            return marker;
        }
        // Keep background/attributes (selection/search), override only marker foreground.
        return new CellStyle(marker.fg(), base.bg(), base.bold(), base.underline());
    }

    private PreparedLine preparedLine(
        String text,
        List<StyleSpan> baseSpans,
        int sourceVisualColumn,
        BaseStyleIdentity baseStyleKey,
        int cacheSlot
    ) {
        String value = text == null ? "" : text;
        int tabPhase = Math.floorMod(sourceVisualColumn, tabWidth);
        String keyedText = baseStyleKey == null ? value : "";
        List<StyleSpan> keyedBaseSpans = baseStyleKey == null ? baseSpans : List.of();
        PreparedLineKey lookupKey = new PreparedLineKey(
            keyedText,
            keyedBaseSpans,
            baseStyleKey,
            tabWidth,
            tabPhase
        );
        PreparedLine frameCached = previousFrameLine(lookupKey, cacheSlot);
        if (frameCached != null) {
            preparedLineCacheHits++;
            currentFrameLineKeys[cacheSlot] = lookupKey;
            currentFrameLines[cacheSlot] = frameCached;
            return frameCached;
        }
        PreparedLine cached = preparedLineCache.get(lookupKey);
        if (cached != null) {
            preparedLineCacheHits++;
            currentFrameLineKeys[cacheSlot] = lookupKey;
            currentFrameLines[cacheSlot] = cached;
            return cached;
        }

        preparedLineCacheMisses++;
        TerminalText.Metrics metrics = TerminalText.metrics(value, tabWidth);
        int count = metrics.graphemeCount();
        String[] display = new String[count];
        int[] advances = new int[count];
        int[] visualOffsets = new int[count];
        boolean[] tabs = new boolean[count];
        boolean[] exactSpaces = new boolean[count];
        CellStyle[] baseStyles = new CellStyle[count];
        Arrays.fill(baseStyles, CellStyle.DEFAULT);
        applySpans(metrics, baseSpans, 0, baseSpans.size(), baseStyles);

        int absoluteVisual = tabPhase;
        int leadingEnd = 0;
        while (leadingEnd < count && metrics.isWhitespace(leadingEnd)) {
            leadingEnd++;
        }
        int trailingStart = count;
        while (trailingStart > 0 && metrics.isWhitespace(trailingStart - 1)) {
            trailingStart--;
        }
        for (int index = 0; index < count; index++) {
            int start = metrics.start(index);
            int end = metrics.end(index);
            boolean tab = metrics.isTab(index);
            int width = Math.max(1, metrics.width(index));
            int advance = tab
                ? tabWidth - (absoluteVisual % tabWidth)
                : width;
            String shown = tab ? " " : displayGrapheme(value, start, end);
            display[index] = shown;
            advances[index] = advance;
            visualOffsets[index] = absoluteVisual - tabPhase;
            tabs[index] = tab;
            exactSpaces[index] = end == start + 1 && value.charAt(start) == ' ';
            absoluteVisual += advance;
        }

        PreparedLine prepared = new PreparedLine(
            metrics,
            display,
            advances,
            visualOffsets,
            tabs,
            exactSpaces,
            baseStyles,
            leadingEnd,
            trailingStart,
            preparedLineWeight(value, metrics, baseSpans.size())
        );
        List<StyleSpan> stableBaseSpans = keyedBaseSpans.isEmpty()
            ? List.of()
            : List.copyOf(keyedBaseSpans);
        PreparedLineKey storedKey = new PreparedLineKey(
            keyedText,
            stableBaseSpans,
            baseStyleKey,
            tabWidth,
            tabPhase
        );
        if (prepared.cacheWeight() <= MAX_PREPARED_CACHE_WEIGHT) {
            preparedLineCache.put(storedKey, prepared);
            preparedLineCacheWeight += prepared.cacheWeight();
            trimPreparedLineCache();
        }
        currentFrameLineKeys[cacheSlot] = prepared.cacheWeight() <= MAX_PREPARED_CACHE_WEIGHT
            ? storedKey
            : lookupKey;
        currentFrameLines[cacheSlot] = prepared;
        return prepared;
    }

    private PreparedLine previousFrameLine(PreparedLineKey key, int cacheSlot) {
        PreparedLine sameRow = previousFrameLineAt(key, cacheSlot);
        if (sameRow != null) {
            return sameRow;
        }
        PreparedLine nextRow = previousFrameLineAt(key, cacheSlot + 1);
        if (nextRow != null) {
            return nextRow;
        }
        return previousFrameLineAt(key, cacheSlot - 1);
    }

    private PreparedLine previousFrameLineAt(PreparedLineKey key, int cacheSlot) {
        if (cacheSlot < 0 || cacheSlot >= previousFrameLineKeys.length) {
            return null;
        }
        PreparedLineKey previousKey = previousFrameLineKeys[cacheSlot];
        return key.equals(previousKey) ? previousFrameLines[cacheSlot] : null;
    }

    private void beginPreparedLineFrame(int lineCount) {
        if (currentFrameLineKeys.length >= lineCount) {
            return;
        }
        int capacity = Math.max(lineCount, currentFrameLineKeys.length * 2 + 16);
        previousFrameLineKeys = Arrays.copyOf(previousFrameLineKeys, capacity);
        previousFrameLines = Arrays.copyOf(previousFrameLines, capacity);
        currentFrameLineKeys = Arrays.copyOf(currentFrameLineKeys, capacity);
        currentFrameLines = Arrays.copyOf(currentFrameLines, capacity);
    }

    private void endPreparedLineFrame() {
        PreparedLineKey[] completedKeys = currentFrameLineKeys;
        currentFrameLineKeys = previousFrameLineKeys;
        previousFrameLineKeys = completedKeys;
        PreparedLine[] completedLines = currentFrameLines;
        currentFrameLines = previousFrameLines;
        previousFrameLines = completedLines;
    }

    private void applySpans(
        TerminalText.Metrics metrics,
        List<StyleSpan> spans,
        int fromIndex,
        int toIndex,
        CellStyle[] target
    ) {
        int count = metrics.graphemeCount();
        if (count == 0) {
            return;
        }
        for (int index = fromIndex; index < toIndex; index++) {
            StyleSpan span = spans.get(index);
            int first = firstOverlappingGrapheme(metrics, span.start());
            int afterLast = firstStartingAtOrAfter(metrics, span.end());
            if (first < afterLast) {
                CellStyle style = cellStyle(span.style());
                Arrays.fill(target, first, afterLast, style);
            }
        }
    }

    private int firstOverlappingGrapheme(TerminalText.Metrics metrics, int offset) {
        int low = 0;
        int high = metrics.graphemeCount();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (metrics.end(middle) <= offset) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private int firstStartingAtOrAfter(TerminalText.Metrics metrics, int offset) {
        int low = 0;
        int high = metrics.graphemeCount();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (metrics.start(middle) < offset) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private void ensureStyleScratch(int required) {
        if (styleScratch.length < required) {
            styleScratch = new CellStyle[Math.max(required, styleScratch.length * 2 + 16)];
        }
    }

    private CellStyle cellStyle(Style style) {
        if (style.equals(Style.DEFAULT)) {
            return CellStyle.DEFAULT;
        }
        int attributes = (style.bold() ? 2 : 0) | (style.underline() ? 1 : 0);
        int index = ((style.fg().ordinal() * EDITOR_COLOR_COUNT + style.bg().ordinal()) << 2)
            | attributes;
        CellStyle cached = cellStyleCache[index];
        if (cached != null) {
            return cached;
        }
        CellStyle mapped = EditorStyleAdapter.toCell(style);
        cellStyleCache[index] = mapped;
        return mapped;
    }

    private void trimPreparedLineCache() {
        Iterator<Map.Entry<PreparedLineKey, PreparedLine>> entries =
            preparedLineCache.entrySet().iterator();
        while (
            entries.hasNext()
                && (
                    preparedLineCache.size() > MAX_PREPARED_LINES
                        || preparedLineCacheWeight > MAX_PREPARED_CACHE_WEIGHT
                )
        ) {
            Map.Entry<PreparedLineKey, PreparedLine> eldest = entries.next();
            preparedLineCacheWeight -= eldest.getValue().cacheWeight();
            entries.remove();
        }
    }

    private void clearPreparedLineCache() {
        preparedLineCache.clear();
        preparedLineCacheWeight = 0;
        Arrays.fill(previousFrameLineKeys, null);
        Arrays.fill(previousFrameLines, null);
        Arrays.fill(currentFrameLineKeys, null);
        Arrays.fill(currentFrameLines, null);
    }

    int preparedLineCacheSize() {
        return preparedLineCache.size();
    }

    long preparedLineCacheHits() {
        return preparedLineCacheHits;
    }

    long preparedLineCacheMisses() {
        return preparedLineCacheMisses;
    }

    private static String displayGrapheme(String text, int start, int end) {
        if (end == start + 1) {
            char value = text.charAt(start);
            if (value < ASCII_DISPLAY.length) {
                return ASCII_DISPLAY[value];
            }
        }
        return TerminalText.sanitizeGrapheme(text.substring(start, end));
    }

    private static String[] createAsciiDisplay() {
        String[] display = new String[128];
        for (int value = 0; value < display.length; value++) {
            display[value] = TerminalText.sanitizeGrapheme(Character.toString(value));
        }
        return display;
    }

    private static int preparedLineWeight(
        String text,
        TerminalText.Metrics metrics,
        int baseSpanCount
    ) {
        long estimated = 64L
            + (long) text.length() * Character.BYTES
            + (long) metrics.storageWeight() * Integer.BYTES
            + (long) baseSpanCount * 32L
            + (long) metrics.graphemeCount() * 64L;
        return (int) Math.min(Integer.MAX_VALUE, estimated);
    }

    private static final class PreparedLineKey {
        private final String text;
        private final List<StyleSpan> baseSpans;
        private final BaseStyleIdentity baseStyleKey;
        private final int tabWidth;
        private final int tabPhase;
        private final int hashCode;

        private PreparedLineKey(
            String text,
            List<StyleSpan> baseSpans,
            BaseStyleIdentity baseStyleKey,
            int tabWidth,
            int tabPhase
        ) {
            this.text = text;
            this.baseSpans = baseSpans;
            this.baseStyleKey = baseStyleKey;
            this.tabWidth = tabWidth;
            this.tabPhase = tabPhase;
            int hash = 31 * tabWidth + tabPhase;
            hash = 31 * hash + (baseStyleKey == null ? text.hashCode() : baseStyleKey.hashCode());
            this.hashCode = baseStyleKey == null ? 31 * hash + baseSpans.hashCode() : hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PreparedLineKey key)) {
                return false;
            }
            if (tabWidth != key.tabWidth || tabPhase != key.tabPhase) {
                return false;
            }
            if (baseStyleKey != null || key.baseStyleKey != null) {
                return baseStyleKey != null && baseStyleKey.equals(key.baseStyleKey);
            }
            return text.equals(key.text) && baseSpans.equals(key.baseSpans);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private record PreparedLine(
        TerminalText.Metrics metrics,
        String[] display,
        int[] advances,
        int[] visualOffsets,
        boolean[] tabs,
        boolean[] exactSpaces,
        CellStyle[] baseStyles,
        int leadingEnd,
        int trailingStart,
        int cacheWeight
    ) {
    }
}
