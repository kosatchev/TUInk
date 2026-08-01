package dev.tuink.tui.ui;

import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.CellText;
import dev.tuink.terminal.style.CellStyle;

/**
 * Reusable modal frame element with title and close button.
 */
public final class Modal {
    private final int top;
    private final int left;
    private final int width;
    private final int height;
    private final String title;
    private final CellStyle bodyStyle;
    private final CellStyle borderStyle;
    private final CellStyle titleStyle;

    public Modal(
        int top,
        int left,
        int width,
        int height,
        String title,
        CellStyle bodyStyle,
        CellStyle borderStyle,
        CellStyle titleStyle
    ) {
        this.top = top;
        this.left = left;
        this.width = width;
        this.height = height;
        this.title = title;
        this.bodyStyle = bodyStyle;
        this.borderStyle = borderStyle;
        this.titleStyle = titleStyle;
    }

    public int right() {
        return left + width - 1;
    }

    public int bottom() {
        return top + height - 1;
    }

    public Button closeButton() {
        return Button.plain(top, right() - 2, "X");
    }

    public void draw(CellBuffer buffer, int rows, int cols) {
        fillRect(buffer, rows, cols, top, left, height, width, bodyStyle);
        drawLine(buffer, cols, top, left, "┌" + "─".repeat(Math.max(0, width - 2)) + "┐", borderStyle);
        drawLine(buffer, cols, bottom(), left, "└", borderStyle);
        drawLine(buffer, cols, bottom(), right(), "┘", borderStyle);
        drawLine(buffer, cols, bottom(), left + 1, "─".repeat(Math.max(0, width - 2)), borderStyle);
        for (int row = top + 1; row < bottom(); row++) {
            drawLine(buffer, cols, row, left, "│", borderStyle);
            drawLine(buffer, cols, row, right(), "│", borderStyle);
        }
        drawLine(buffer, cols, top, left + 2, title, titleStyle);
        closeButton().draw(buffer, cols, titleStyle);
    }

    private static void fillRect(
        CellBuffer buffer,
        int rows,
        int cols,
        int top,
        int left,
        int height,
        int width,
        CellStyle style
    ) {
        for (int r = 0; r < height; r++) {
            int row = top + r;
            if (row < 0 || row >= rows) {
                continue;
            }
            for (int c = 0; c < width; c++) {
                int col = left + c;
                if (col < 0 || col >= cols) {
                    continue;
                }
                buffer.set(row, col, " ", style);
            }
        }
    }

    private static void drawLine(CellBuffer buffer, int cols, int row, int startCol, String text, CellStyle style) {
        CellText.draw(buffer, row, startCol, text, style, cols);
    }
}
