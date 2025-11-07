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
    private static final Style STATUS_STYLE = new Style(Color.BLACK, Color.BRIGHT_WHITE, false, false);

    public CellBuffer toCellBuffer(RenderPlan plan, int rows, int cols) {
        CellBuffer buffer = new CellBuffer(rows, cols);

        for (int row = 0; row < Math.min(plan.lines().size(), plan.contentRows()); row++) {
            LineVisual line = plan.lines().get(row);
            drawText(buffer, row, 0, line.gutter(), GUTTER_STYLE, cols);
            drawHighlightedText(buffer, row, plan.gutterWidth(), line.text(), line.spans(), cols);
        }

        int statusRow = Math.max(0, rows - 1);
        String status = truncateOrPad(plan.statusText(), cols);
        drawText(buffer, statusRow, 0, status, STATUS_STYLE, cols);

        return buffer;
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
