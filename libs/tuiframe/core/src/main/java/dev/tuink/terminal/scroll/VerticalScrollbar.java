package dev.tuink.terminal.scroll;

import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.style.CellStyle;

/**
 * Reusable vertical scrollbar painter (track + thumb) for cell buffers.
 */
public final class VerticalScrollbar {
    private VerticalScrollbar() {
    }

    public static boolean hitTrack(int mouseRow, int mouseCol, int topRow, int col, int visibleRows) {
        return mouseCol == col && ScrollInteraction.hitTrack(mouseRow, topRow, visibleRows);
    }

    public static int scrollFromPointer(int mouseRow, int topRow, int visibleRows, int totalItems) {
        return ScrollInteraction.scrollFromPointer(mouseRow, topRow, visibleRows, totalItems);
    }

    public static ScrollInteraction.Hit hit(
        int mouseRow,
        int mouseCol,
        int topRow,
        int col,
        int visibleRows,
        int totalItems,
        int scroll
    ) {
        if (mouseCol != col) {
            return ScrollInteraction.Hit.NONE;
        }
        return ScrollInteraction.hit(mouseRow, topRow, visibleRows, totalItems, scroll);
    }

    public static int pageScrollFromTrackClick(
        int currentScroll,
        int mouseRow,
        int topRow,
        int visibleRows,
        int totalItems
    ) {
        return ScrollInteraction.pageScrollFromTrackClick(
            currentScroll,
            mouseRow,
            topRow,
            visibleRows,
            totalItems
        );
    }

    public static void draw(
        CellBuffer buffer,
        int topRow,
        int col,
        int visibleRows,
        int totalItems,
        int scroll,
        String trackGrapheme,
        CellStyle trackStyle,
        String thumbGrapheme,
        CellStyle thumbStyle
    ) {
        if (visibleRows <= 0 || totalItems <= 0) {
            return;
        }

        for (int i = 0; i < visibleRows; i++) {
            buffer.set(topRow + i, col, trackGrapheme, trackStyle);
        }

        int thumbSize = ScrollInteraction.thumbSize(visibleRows, totalItems);
        int thumbOffset = ScrollInteraction.thumbOffset(visibleRows, totalItems, scroll);

        for (int i = 0; i < thumbSize; i++) {
            buffer.set(topRow + thumbOffset + i, col, thumbGrapheme, thumbStyle);
        }
    }
}
