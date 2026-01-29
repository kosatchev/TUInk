package dev.tuink.tui.ui;

import dev.tuink.render.Style;
import dev.tuink.tui.UiStyles;
import dev.tuink.tui.render.CellBuffer;

/**
 * Reusable dropdown menu element with frame, item highlighting and hit-testing.
 */
final class ContextMenu {
    private static final int MIN_WIDTH = 16;
    private static final Style DISABLED_ITEM_STYLE =
        UiStyles.forTheme(UiStyles.Theme.CYAN).contextMenuDisabledItem();

    private ContextMenu() {
    }

    static int widthForItems(int cols, MenuItem[] items) {
        int width = MIN_WIDTH;
        for (MenuItem item : items) {
            width = Math.max(width, item.text().length() + 4);
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
        MenuItem[] items,
        int[] mnemonicIndexes,
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
            String label = i < items.length ? items[i].text() : "";
            String content = padRight(" " + label, Math.max(0, width - 2));
            boolean selectable = i < items.length && items[i].selectable();
            Style itemStyle;
            if (!selectable) {
                itemStyle = colorEnabled ? DISABLED_ITEM_STYLE : popupStyle;
            } else {
                itemStyle = i == selectedIndex
                    ? (colorEnabled ? selectedStyle : selectedMonoStyle)
                    : popupStyle;
            }
            int row = topRow + 1 + i;
            drawLine(buffer, row, startCol, "│", popupStyle, cols);
            drawLineWithMnemonic(
                buffer,
                row,
                startCol + 1,
                content,
                itemStyle,
                mnemonicAt(mnemonicIndexes, i),
                selectable,
                cols
            );
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

    static Hit hit(int row, int col, int cols, int startCol, int topRow, MenuItem[] items) {
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

    private static void drawLineWithMnemonic(
        CellBuffer buffer,
        int row,
        int startCol,
        String text,
        Style style,
        int mnemonicInLabel,
        boolean selectable,
        int cols
    ) {
        int mnemonicInContent = mnemonicInLabel >= 0 ? mnemonicInLabel + 1 : -1;
        Style mnemonicStyle = new Style(style.fg(), style.bg(), style.bold(), true);
        for (int i = 0; i < text.length() && startCol + i < cols; i++) {
            Style charStyle = selectable && i == mnemonicInContent ? mnemonicStyle : style;
            buffer.set(row, startCol + i, String.valueOf(text.charAt(i)), charStyle);
        }
    }

    private static int mnemonicAt(int[] mnemonicIndexes, int itemIndex) {
        if (mnemonicIndexes == null || itemIndex < 0 || itemIndex >= mnemonicIndexes.length) {
            return -1;
        }
        return mnemonicIndexes[itemIndex];
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
