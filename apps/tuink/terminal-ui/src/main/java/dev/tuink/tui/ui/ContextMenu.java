package dev.tuink.tui.ui;

import dev.tuink.tui.render.CellBuffer;
import dev.tuink.render.Style;

/**
 * Reusable dropdown menu element with frame, item highlighting and hit-testing.
 */
final class ContextMenu {
    private static final int MIN_WIDTH = 16;

    private ContextMenu() {
    }

    static int widthForItems(int cols, String[] items) {
        int width = MIN_WIDTH;
        for (String item : items) {
            width = Math.max(width, item.length() + 4);
        }
        return Math.min(cols, width);
    }

    static void draw(
        CellBuffer buffer,
        int cols,
        boolean visible,
        int startCol,
        int topRow,
        int selectedIndex,
        String[] items,
        boolean colorEnabled,
        Style popupStyle,
        Style selectedStyle,
        Style selectedMonoStyle
    ) {
        if (!visible || buffer.rows() < topRow + 3 || cols < 8) {
            return;
        }

        int width = widthForItems(cols, items);
        int height = Math.min(buffer.rows() - topRow, items.length + 2);
        if (height < 3) {
            return;
        }

        drawLine(buffer, topRow, startCol, "┌" + "─".repeat(Math.max(0, width - 2)) + "┐", popupStyle, cols);
        for (int i = 0; i < height - 2; i++) {
            String label = i < items.length ? items[i] : "";
            String content = padRight(" " + label, Math.max(0, width - 2));
            Style itemStyle = i == selectedIndex
                ? (colorEnabled ? selectedStyle : selectedMonoStyle)
                : popupStyle;
            int row = topRow + 1 + i;
            drawLine(buffer, row, startCol, "│", popupStyle, cols);
            drawLine(buffer, row, startCol + 1, content, itemStyle, cols);
            drawLine(buffer, row, startCol + width - 1, "│", popupStyle, cols);
        }
        drawLine(
            buffer,
            topRow + height - 1,
            startCol,
            "└" + "─".repeat(Math.max(0, width - 2)) + "┘",
            popupStyle,
            cols
        );
    }

    static Hit hit(int row, int col, int cols, int startCol, int topRow, String[] items) {
        int width = widthForItems(cols, items);
        int popupTopRow = topRow;
        int popupBottomRow = topRow + items.length + 1;

        boolean insidePopup = col >= startCol
            && col < startCol + width
            && row >= popupTopRow
            && row <= popupBottomRow;
        boolean insideItemContent = row >= topRow + 1
            && row < topRow + 1 + items.length
            && col > startCol
            && col < startCol + width - 1;
        int itemIndex = insideItemContent ? row - (topRow + 1) : -1;
        return new Hit(insidePopup, insideItemContent, itemIndex);
    }

    private static void drawLine(CellBuffer buffer, int row, int startCol, String text, Style style, int cols) {
        for (int i = 0; i < text.length() && startCol + i < cols; i++) {
            buffer.set(row, startCol + i, String.valueOf(text.charAt(i)), style);
        }
    }

    private static String padRight(String text, int width) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }

    record Hit(boolean insidePopup, boolean insideItemContent, int itemIndex) {
    }
}
