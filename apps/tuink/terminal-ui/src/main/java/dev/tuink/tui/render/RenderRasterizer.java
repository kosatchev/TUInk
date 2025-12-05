package dev.tuink.tui.render;

import dev.tuink.render.Color;
import dev.tuink.render.LineVisual;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.List;

/**
 * Converts an abstract render plan into a cell buffer.
 */
public class RenderRasterizer {
    private static final int TAB_WIDTH = 4;
    private static final Style GUTTER_STYLE = new Style(Color.BRIGHT_BLACK, Color.BLACK, false, false);
    private static final Style FILEBAR_STYLE = new Style(Color.BRIGHT_WHITE, Color.BLUE, false, false);
    private static final Style STATUS_STYLE = new Style(Color.BLACK, Color.BRIGHT_WHITE, false, false);
    private static final Style INTERACTIVE_STYLE = new Style(Color.BLACK, Color.BRIGHT_YELLOW, false, false);
    private static final Style NOTIFICATION_STYLE = new Style(Color.BLACK, Color.BRIGHT_GREEN, false, false);
    private static final Style LEADING_WHITESPACE_STYLE = new Style(Color.BRIGHT_BLACK, Color.DEFAULT, false, false);
    private static final Style SCROLL_TRACK_STYLE = new Style(Color.BRIGHT_BLACK, Color.DEFAULT, false, false);
    private static final Style SCROLL_THUMB_STYLE = new Style(Color.BRIGHT_WHITE, Color.DEFAULT, false, false);
    private final int contentTopRow;

    public RenderRasterizer() {
        this(1);
    }

    public RenderRasterizer(int contentTopRow) {
        this.contentTopRow = Math.max(1, contentTopRow);
    }

    public CellBuffer rasterize(RenderPlan plan, int rows, int cols) {
        return rasterize(plan, rows, cols, contentTopRow);
    }

    public CellBuffer rasterize(RenderPlan plan, int rows, int cols, int contentTopRow) {
        CellBuffer buffer = new CellBuffer(rows, cols);
        int contentCols = Math.max(1, cols - plan.scrollbarWidth());
        int topRow = Math.max(1, contentTopRow);

        for (int row = 0; row < Math.min(plan.lines().size(), plan.contentRows()); row++) {
            LineVisual line = plan.lines().get(row);
            int screenRow = row + topRow;
            if (screenRow >= rows) {
                break;
            }
            drawText(buffer, screenRow, 0, line.gutter(), GUTTER_STYLE, contentCols);
            drawHighlightedText(buffer, screenRow, plan.gutterWidth(), line.text(), line.spans(), contentCols);
        }

        int statusRow = Math.max(0, rows - 1);

        drawText(buffer, 0, 0, truncateOrPad(plan.topBarText(), cols), FILEBAR_STYLE, cols);
        drawText(buffer, statusRow, 0, truncateOrPad(plan.bottomBarText(), cols), STATUS_STYLE, cols);
        int overlayBottomRow = rows - 2;
        int overlayTopRow = overlayBottomRow;
        if (plan.interactiveBarText() != null && overlayBottomRow >= 0) {
            Style style = plan.interactiveBarStyle() == null ? INTERACTIVE_STYLE : plan.interactiveBarStyle();
            drawText(
                buffer,
                overlayBottomRow,
                0,
                truncateOrPad(plan.interactiveBarText(), cols),
                style,
                cols
            );
            overlayTopRow = overlayBottomRow - 1;
        }
        if (plan.notificationBarText() != null && overlayTopRow >= 0) {
            Style style = plan.notificationBarStyle() == null ? NOTIFICATION_STYLE : plan.notificationBarStyle();
            drawText(buffer, overlayTopRow, 0, truncateOrPad(plan.notificationBarText(), cols), style, cols);
        }
        drawScrollbar(buffer, plan, rows, cols, topRow);

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
            SCROLL_TRACK_STYLE,
            "█",
            SCROLL_THUMB_STYLE
        );
    }

    private void drawHighlightedText(
        CellBuffer buffer,
        int row,
        int startCol,
        String text,
        List<StyleSpan> spans,
        int cols
    ) {
        Style[] styles = new Style[text.length()];
        String[] graphemes = new String[text.length()];
        for (int i = 0; i < styles.length; i++) {
            styles[i] = Style.DEFAULT;
            graphemes[i] = String.valueOf(text.charAt(i));
        }
        for (StyleSpan span : spans) {
            int from = Math.max(0, span.start());
            int to = Math.min(text.length(), span.end());
            for (int i = from; i < to; i++) {
                styles[i] = span.style();
            }
        }

        // Render leading indentation markers: space -> '·', tab -> '»'
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') {
                graphemes[i] = "·";
                styles[i] = LEADING_WHITESPACE_STYLE;
                continue;
            }
            if (ch == '\t') {
                graphemes[i] = "»";
                styles[i] = LEADING_WHITESPACE_STYLE;
                continue;
            }
            break;
        }

        int outCol = startCol;
        for (int i = 0; i < text.length() && outCol < cols; i++) {
            char ch = text.charAt(i);
            if (ch == '\t') {
                int relativeCol = Math.max(0, outCol - startCol);
                int tabAdvance = TAB_WIDTH - (relativeCol % TAB_WIDTH);
                if ("»".equals(graphemes[i])) {
                    buffer.set(row, outCol, "»", styles[i]);
                    outCol++;
                    for (int j = 1; j < tabAdvance && outCol < cols; j++) {
                        buffer.set(row, outCol, " ", styles[i]);
                        outCol++;
                    }
                } else {
                    for (int j = 0; j < tabAdvance && outCol < cols; j++) {
                        buffer.set(row, outCol, " ", styles[i]);
                        outCol++;
                    }
                }
                continue;
            }
            buffer.set(row, outCol, graphemes[i], styles[i]);
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
}
