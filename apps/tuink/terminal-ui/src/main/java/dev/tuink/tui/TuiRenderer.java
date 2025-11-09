package dev.tuink.tui;

import dev.tuink.render.Color;
import dev.tuink.render.LineVisual;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.List;

/**
 * Converts a render plan into a terminal cell buffer.
 */
public class TuiRenderer {
    private static final Style GUTTER_STYLE = new Style(Color.BRIGHT_BLACK, Color.DEFAULT, false, false);
    private static final Style FILEBAR_STYLE = new Style(Color.BLACK, Color.BRIGHT_CYAN, true, false);
    private static final Style STATUS_STYLE = new Style(Color.BLACK, Color.BRIGHT_WHITE, false, false);
    private static final Style SCROLL_TRACK_STYLE = new Style(Color.BRIGHT_BLACK, Color.DEFAULT, false, false);
    private static final Style SCROLL_THUMB_STYLE = new Style(Color.BRIGHT_WHITE, Color.DEFAULT, false, false);

    public CellBuffer toCellBuffer(RenderPlan plan, int rows, int cols) {
        CellBuffer buffer = new CellBuffer(rows, cols);
        int contentCols = Math.max(1, cols - plan.scrollbarWidth());

        for (int row = 0; row < Math.min(plan.lines().size(), plan.contentRows()) && row + 1 < rows; row++) {
            LineVisual line = plan.lines().get(row);
            int screenRow = row + 1;
            drawText(buffer, screenRow, 0, line.gutter(), GUTTER_STYLE, contentCols);
            drawHighlightedText(buffer, screenRow, plan.gutterWidth(), line.text(), line.spans(), contentCols);
        }

        drawText(buffer, 0, 0, truncateOrPad(plan.topBarText(), cols), FILEBAR_STYLE, cols);
        drawText(buffer, Math.max(0, rows - 1), 0, truncateOrPad(plan.bottomBarText(), cols), STATUS_STYLE, cols);
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

    private void drawHighlightedText(CellBuffer buffer, int row, int startCol, String text, List<StyleSpan> spans, int cols) {
        Style[] styles = new Style[text.length()];
        for (int i = 0; i < styles.length; i++) {
            styles[i] = Style.DEFAULT;
        }
        for (StyleSpan span : spans) {
            int from = Math.max(0, span.start());
            int to = Math.min(text.length(), span.end());
            for (int i = from; i < to; i++) {
                styles[i] = span.style();
            }
        }

        for (int i = 0; i < text.length() && startCol + i < cols; i++) {
            buffer.set(row, startCol + i, String.valueOf(text.charAt(i)), styles[i]);
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
