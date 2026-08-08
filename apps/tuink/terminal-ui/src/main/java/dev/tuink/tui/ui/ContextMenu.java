package dev.tuink.tui.ui;

import dev.tuink.terminal.cell.Cell;
import dev.tuink.terminal.text.TerminalText;
import dev.tuink.terminal.style.CellStyle;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.CellText;
import dev.tuink.terminal.cell.Canvas;
import java.util.Objects;

/**
 * Reusable dropdown menu element with frame, item highlighting and hit-testing.
 */
final class ContextMenu {
    private static final int MIN_WIDTH = 16;
    private static final CellStyle DISABLED_ITEM_STYLE =
        UiStyles.forTheme(UiStyles.Theme.CYAN).contextMenuDisabledItem();

    private ContextMenu() {
    }

    static int widthForItems(int cols, MenuItem[] items) {
        int width = MIN_WIDTH;
        for (MenuItem item : items) {
            width = Math.max(width, CellText.width(item.text()) + 4);
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
        CellStyle popupStyle,
        CellStyle selectedStyle,
        CellStyle selectedMonoStyle
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
            CellStyle itemStyle;
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

    static void draw(
        Canvas canvas,
        int rows,
        int cols,
        boolean visible,
        int startCol,
        int topRow,
        int selectedIndex,
        MenuItem[] items,
        int[] mnemonicIndexes,
        boolean colorEnabled,
        CellStyle popupStyle,
        CellStyle selectedStyle,
        CellStyle selectedMonoStyle
    ) {
        Objects.requireNonNull(canvas, "canvas");
        if (!visible || rows < topRow + 3 || cols < 8) {
            return;
        }

        int width = widthForItems(cols, items);
        int height = Math.min(rows - topRow, items.length + 2);
        if (height < 3) {
            return;
        }

        int mark = canvas.save();
        try {
            if (!canvas.clip(0, 0, cols, rows)
                || !canvas.clip(startCol, topRow, width, height)) {
                return;
            }
            Cell horizontal = new Cell("─", popupStyle);
            Cell vertical = new Cell("│", popupStyle);
            Cell topLeft = new Cell("┌", popupStyle);
            Cell topRight = new Cell("┐", popupStyle);
            Cell bottomLeft = new Cell("└", popupStyle);
            Cell bottomRight = new Cell("┘", popupStyle);
            int rightCol = Math.addExact(startCol, width - 1);
            int bottomRow = Math.addExact(topRow, height - 1);

            canvas.put(startCol, topRow, topLeft);
            canvas.fillCell(startCol + 1, topRow, width - 2, 1, horizontal);
            canvas.put(rightCol, topRow, topRight);
            for (int i = 0; i < height - 2; i++) {
                String label = i < items.length ? items[i].text() : "";
                String content = padRight(" " + label, width - 2);
                boolean selectable = i < items.length && items[i].selectable();
                CellStyle itemStyle;
                if (!selectable) {
                    itemStyle = colorEnabled ? DISABLED_ITEM_STYLE : popupStyle;
                } else {
                    itemStyle = i == selectedIndex
                        ? (colorEnabled ? selectedStyle : selectedMonoStyle)
                        : popupStyle;
                }
                int row = topRow + 1 + i;
                canvas.put(startCol, row, vertical);
                drawLineWithMnemonic(
                    canvas,
                    row,
                    startCol + 1,
                    content,
                    itemStyle,
                    mnemonicAt(mnemonicIndexes, i),
                    selectable
                );
                canvas.put(rightCol, row, vertical);
            }
            canvas.put(startCol, bottomRow, bottomLeft);
            canvas.fillCell(startCol + 1, bottomRow, width - 2, 1, horizontal);
            canvas.put(rightCol, bottomRow, bottomRight);
        } finally {
            canvas.restore(mark);
        }
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

    private static void drawLine(CellBuffer buffer, int row, int startCol, String text, CellStyle style, int cols) {
        CellText.draw(buffer, row, startCol, text, style, cols);
    }

    private static void drawLineWithMnemonic(
        CellBuffer buffer,
        int row,
        int startCol,
        String text,
        CellStyle style,
        int mnemonicInLabel,
        boolean selectable,
        int cols
    ) {
        int mnemonicInContent = mnemonicInLabel >= 0 ? mnemonicInLabel + 1 : -1;
        CellStyle mnemonicStyle = new CellStyle(style.fg(), style.bg(), style.bold(), true);
        int outCol = startCol;
        for (TerminalText.Grapheme grapheme : TerminalText.graphemes(text)) {
            int advance = Math.max(1, grapheme.width());
            if (outCol + advance > cols) {
                break;
            }
            boolean mnemonic = selectable
                && mnemonicInContent >= grapheme.start()
                && mnemonicInContent < grapheme.end();
            CellText.draw(buffer, row, outCol, grapheme.text(), mnemonic ? mnemonicStyle : style, cols);
            outCol += advance;
        }
    }

    private static void drawLineWithMnemonic(
        Canvas canvas,
        int row,
        int startCol,
        String text,
        CellStyle style,
        int mnemonicInLabel,
        boolean selectable
    ) {
        CellText.draw(canvas, startCol, row, text, style);
        if (!selectable || mnemonicInLabel < 0) {
            return;
        }
        int mnemonicInContent = mnemonicInLabel + 1;
        for (TerminalText.Grapheme grapheme : TerminalText.graphemes(text)) {
            if (mnemonicInContent < grapheme.start() || mnemonicInContent >= grapheme.end()) {
                continue;
            }
            if (!grapheme.tab()) {
                int visualOffset = CellText.visualColumnAt(text, grapheme.start());
                CellStyle mnemonicStyle = new CellStyle(style.fg(), style.bg(), style.bold(), true);
                canvas.put(Math.addExact(startCol, visualOffset), row, grapheme.text(), mnemonicStyle);
            }
            return;
        }
    }

    private static int mnemonicAt(int[] mnemonicIndexes, int itemIndex) {
        if (mnemonicIndexes == null || itemIndex < 0 || itemIndex >= mnemonicIndexes.length) {
            return -1;
        }
        return mnemonicIndexes[itemIndex];
    }

    private static String padRight(String text, int width) {
        return CellText.fit(text, width);
    }

    record Hit(boolean insidePopup, boolean insideItemContent, int itemIndex) {
    }
}
