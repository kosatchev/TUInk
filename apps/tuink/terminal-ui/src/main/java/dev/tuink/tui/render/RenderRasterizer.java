package dev.tuink.tui.render;

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
            drawText(buffer, screenRow, 0, line.gutter(), gutterStyle, contentCols);
            boolean markLeadingWhitespace = shouldMarkLeadingWhitespace(plan, line);
            drawHighlightedText(
                buffer,
                screenRow,
                plan.gutterWidth(),
                line.text(),
                line.spans(),
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
        String text,
        List<StyleSpan> spans,
        int cols,
        boolean markLeadingWhitespace
    ) {
        Style[] charStyles = new Style[text.length()];
        String[] graphemes = new String[text.length()];
        for (int i = 0; i < charStyles.length; i++) {
            charStyles[i] = Style.DEFAULT;
            graphemes[i] = String.valueOf(text.charAt(i));
        }
        for (StyleSpan span : spans) {
            int from = Math.max(0, span.start());
            int to = Math.min(text.length(), span.end());
            for (int i = from; i < to; i++) {
                charStyles[i] = span.style();
            }
        }

        if (visibleWhitespace && markLeadingWhitespace) {
            // Render leading indentation markers: space -> '·', tab -> '»'
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == ' ') {
                    graphemes[i] = "·";
                    charStyles[i] = markerStyle(charStyles[i]);
                    continue;
                }
                if (ch == '\t') {
                    graphemes[i] = "»";
                    charStyles[i] = markerStyle(charStyles[i]);
                    continue;
                }
                break;
            }
        }

        if (visibleWhitespace) {
            // Render trailing whitespace markers so hanging spaces are visible.
            for (int i = text.length() - 1; i >= 0; i--) {
                char ch = text.charAt(i);
                if (ch == ' ') {
                    graphemes[i] = "·";
                    charStyles[i] = markerStyle(charStyles[i]);
                    continue;
                }
                if (ch == '\t') {
                    graphemes[i] = "»";
                    charStyles[i] = markerStyle(charStyles[i]);
                    continue;
                }
                break;
            }
        }

        int outCol = startCol;
        for (int i = 0; i < text.length() && outCol < cols; i++) {
            char ch = text.charAt(i);
            if (ch == '\t') {
                int relativeCol = Math.max(0, outCol - startCol);
                int tabAdvance = tabWidth - (relativeCol % tabWidth);
                if (visibleWhitespace && "»".equals(graphemes[i])) {
                    buffer.set(row, outCol, "»", charStyles[i]);
                    outCol++;
                    for (int j = 1; j < tabAdvance && outCol < cols; j++) {
                        buffer.set(row, outCol, " ", charStyles[i]);
                        outCol++;
                    }
                } else {
                    for (int j = 0; j < tabAdvance && outCol < cols; j++) {
                        buffer.set(row, outCol, " ", charStyles[i]);
                        outCol++;
                    }
                }
                continue;
            }
            buffer.set(row, outCol, graphemes[i], charStyles[i]);
            outCol++;
        }
    }

    private void drawText(CellBuffer buffer, int row, int col, String text, Style style, int cols) {
        for (int i = 0; i < text.length() && col + i < cols; i++) {
            buffer.set(row, col + i, String.valueOf(text.charAt(i)), style);
        }
    }

    private String truncateOrPad(String text, int width) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
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
