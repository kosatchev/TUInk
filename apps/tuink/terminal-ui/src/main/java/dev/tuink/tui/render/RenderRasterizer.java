package dev.tuink.tui.render;

import dev.tuink.core.GutterDiffFlag;
import dev.tuink.core.GutterDiffMarker;
import dev.tuink.core.DisplayText;
import dev.tuink.render.LineVisual;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import dev.tuink.tui.UiStyles;
import dev.tuink.tui.UiStyles.Palette;
import java.util.List;

/**
 * Converts an abstract render plan into a cell buffer.
 */
public class RenderRasterizer {
    private Palette styles;
    private final int contentTopRow;
    private boolean highlightActiveGutter = true;
    private boolean visibleWhitespace = true;
    private int tabWidth = 4;

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
        this.tabWidth = tabWidth <= 2 ? 2 : 4;
    }

    public CellBuffer rasterize(RenderPlan plan, int rows, int cols) {
        return rasterize(plan, rows, cols, contentTopRow);
    }

    public CellBuffer rasterize(RenderPlan plan, int rows, int cols, int contentTopRow) {
        CellBuffer buffer = new CellBuffer(rows, cols);
        int contentCols = Math.max(1, cols - plan.scrollbarWidth());
        int topRow = Math.max(1, contentTopRow);
        int activeGutterRow = resolveActiveGutterRow(plan);

        for (int row = 0; row < Math.min(plan.lines().size(), plan.contentRows()); row++) {
            LineVisual line = plan.lines().get(row);
            int screenRow = row + topRow;
            if (screenRow >= rows) {
                break;
            }
            Style gutterStyle = highlightActiveGutter && row == activeGutterRow
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
                markLeadingWhitespace
            );
        }

        int statusRow = Math.max(0, rows - 1);

        drawText(buffer, 0, 0, truncateOrPad(plan.topBarText(), cols), styles.renderTopbar(), cols);
        drawText(buffer, statusRow, 0, truncateOrPad(plan.bottomBarText(), cols), styles.renderStatus(), cols);
        drawScrollbar(buffer, plan, rows, cols, topRow);
        drawHorizontalScrollbar(buffer, plan, rows, cols, topRow);
        int overlayBottomRow = rows - 2;
        int overlayTopRow = overlayBottomRow;
        if (plan.interactiveBarText() != null && overlayBottomRow >= 0) {
            Style style = plan.interactiveBarStyle() == null ? styles.renderInteractive() : plan.interactiveBarStyle();
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
            Style style = plan.notificationBarStyle() == null ? styles.renderNotification() : plan.notificationBarStyle();
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

    private void drawScrollbar(CellBuffer buffer, RenderPlan plan, int rows, int cols, int contentTopRow) {
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
            buffer,
            trackTop,
            barCol,
            trackHeight,
            Math.max(1, plan.totalLines()),
            Math.max(0, plan.topLine()),
            "│",
            styles.renderScrollTrack(),
            "█",
            styles.renderScrollThumb()
        );
    }

    private void drawHorizontalScrollbar(CellBuffer buffer, RenderPlan plan, int rows, int cols, int contentTopRow) {
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
            buffer,
            row,
            plan.gutterWidth(),
            visibleCols,
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
        boolean markLeadingWhitespace
    ) {
        String text = line.text();
        List<StyleSpan> spans = line.spans();
        List<DisplayText.Grapheme> graphemes = DisplayText.graphemes(text);
        int leadingEnd = 0;
        while (leadingEnd < graphemes.size() && graphemes.get(leadingEnd).whitespace()) {
            leadingEnd++;
        }
        int trailingStart = graphemes.size();
        while (trailingStart > 0 && graphemes.get(trailingStart - 1).whitespace()) {
            trailingStart--;
        }

        int absoluteVisual = line.sourceVisualColumn();
        int sourceVisual = line.sourceVisualColumn();
        for (int index = 0; index < graphemes.size(); index++) {
            DisplayText.Grapheme grapheme = graphemes.get(index);
            Style style = styleForGrapheme(spans, grapheme.start(), grapheme.end());
            boolean mark = visibleWhitespace
                && (index >= trailingStart || (markLeadingWhitespace && index < leadingEnd))
                && (" ".equals(grapheme.text()) || grapheme.tab());
            if (mark) {
                style = markerStyle(style);
            }
            int advance = grapheme.tab()
                ? tabWidth - (absoluteVisual % tabWidth)
                : Math.max(1, grapheme.width());
            int screenCol = startCol
                + (absoluteVisual - sourceVisual)
                - line.leadingClippedCells();

            if (grapheme.tab()) {
                for (int cell = 0; cell < advance; cell++) {
                    int target = screenCol + cell;
                    if (target < startCol || target >= cols) {
                        continue;
                    }
                    String shown = mark && cell == 0 && screenCol >= startCol ? "»" : " ";
                    buffer.set(row, target, shown, style);
                }
            } else if (screenCol >= startCol && screenCol + advance <= cols) {
                buffer.set(
                    row,
                    screenCol,
                    mark && " ".equals(grapheme.text()) ? "·" : DisplayText.sanitizeGrapheme(grapheme.text()),
                    style
                );
            }
            absoluteVisual += advance;
        }
    }

    private Style styleForGrapheme(List<StyleSpan> spans, int start, int end) {
        Style style = Style.DEFAULT;
        for (StyleSpan span : spans) {
            if (span.end() > start && span.start() < end) {
                style = span.style();
            }
        }
        return style;
    }

    private void drawGutter(CellBuffer buffer, int row, LineVisual line, Style baseStyle, int cols) {
        String gutter = line.gutter();
        if (gutter == null || gutter.isEmpty()) {
            return;
        }
        drawText(buffer, row, 0, gutter, baseStyle, cols);
        if (cols <= 0) {
            return;
        }
        Style markerStyle = gutterMarkerStyle(line.gutterDiff());
        if (markerStyle == null) {
            return;
        }
        buffer.set(row, 0, String.valueOf(gutter.charAt(0)), markerStyle);
    }

    private Style gutterMarkerStyle(GutterDiffMarker marker) {
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

    private void drawText(CellBuffer buffer, int row, int col, String text, Style style, int cols) {
        int outCol = col;
        for (DisplayText.Grapheme grapheme : DisplayText.graphemes(text)) {
            String safe = DisplayText.sanitizeGrapheme(grapheme.text());
            int width = Math.max(1, DisplayText.graphemeWidth(safe));
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
        for (DisplayText.Grapheme grapheme : DisplayText.graphemes(text == null ? "" : text)) {
            String safe = DisplayText.sanitizeGrapheme(grapheme.text());
            int cellWidth = Math.max(1, DisplayText.graphemeWidth(safe));
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

    private Style markerStyle(Style base) {
        Style marker = styles.renderLeadingWhitespace();
        if (base.equals(Style.DEFAULT)) {
            return marker;
        }
        // Keep background/attributes (selection/search), override only marker foreground.
        return new Style(marker.fg(), base.bg(), base.bold(), base.underline());
    }
}
