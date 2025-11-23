package dev.tuink.render;

import java.util.List;

/**
 * Converts an abstract render plan into a cell buffer.
 */
public class RenderRasterizer {
    private static final Style GUTTER_STYLE = new Style(Color.BRIGHT_BLACK, Color.DEFAULT, false, false);
    private static final Style FILEBAR_STYLE = new Style(Color.BLACK, Color.BRIGHT_CYAN, true, false);
    private static final Style STATUS_STYLE = new Style(Color.BLACK, Color.BRIGHT_WHITE, false, false);
    private static final Style INTERACTIVE_STYLE = new Style(Color.BLACK, Color.BRIGHT_YELLOW, false, false);
    private static final Style NOTIFICATION_STYLE = new Style(Color.BLACK, Color.BRIGHT_GREEN, false, false);
    private static final Style LEADING_WHITESPACE_STYLE = new Style(Color.BRIGHT_BLACK, Color.DEFAULT, false, false);
    private static final Style SCROLL_TRACK_STYLE = new Style(Color.BRIGHT_BLACK, Color.DEFAULT, false, false);
    private static final Style SCROLL_THUMB_STYLE = new Style(Color.BRIGHT_WHITE, Color.DEFAULT, false, false);

    public CellBuffer rasterize(RenderPlan plan, int rows, int cols) {
        CellBuffer buffer = new CellBuffer(rows, cols);
        int contentCols = Math.max(1, cols - plan.scrollbarWidth());

        for (int row = 0; row < Math.min(plan.lines().size(), plan.contentRows()) && row + 1 < rows; row++) {
            LineVisual line = plan.lines().get(row);
            int screenRow = row + 1;
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
        drawScrollbar(buffer, plan, rows, cols);

        return buffer;
    }

    private void drawScrollbar(CellBuffer buffer, RenderPlan plan, int rows, int cols) {
        if (plan.scrollbarWidth() == 0 || cols <= 0 || rows <= 2) {
            return;
        }

        int barCol = cols - 1;
        int trackTop = 1;
        int trackHeight = Math.min(plan.contentRows(), Math.max(0, rows - 2));
        if (trackHeight <= 0) {
            return;
        }

        for (int i = 0; i < trackHeight; i++) {
            buffer.set(trackTop + i, barCol, "│", SCROLL_TRACK_STYLE);
        }

        int totalLines = Math.max(1, plan.totalLines());
        int visibleLines = Math.max(1, plan.contentRows());
        int thumbSize = Math.max(1, (int) Math.round((visibleLines / (double) totalLines) * trackHeight));
        thumbSize = Math.min(trackHeight, thumbSize);

        int thumbTravel = Math.max(0, trackHeight - thumbSize);
        int maxTopLine = Math.max(1, totalLines - visibleLines);
        int topLine = Math.max(0, plan.topLine());
        int thumbOffset = thumbTravel == 0 ? 0 : (int) Math.round((topLine / (double) maxTopLine) * thumbTravel);

        for (int i = 0; i < thumbSize; i++) {
            buffer.set(trackTop + thumbOffset + i, barCol, "█", SCROLL_THUMB_STYLE);
        }
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

        for (int i = 0; i < text.length() && startCol + i < cols; i++) {
            buffer.set(row, startCol + i, graphemes[i], styles[i]);
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
