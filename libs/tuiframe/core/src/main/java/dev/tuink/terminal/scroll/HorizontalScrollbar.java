package dev.tuink.terminal.scroll;

import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.style.CellStyle;

/**
 * Reusable horizontal scrollbar painter (track + thumb) for cell buffers.
 */
public final class HorizontalScrollbar {
    private HorizontalScrollbar() {
    }

    public static boolean hitTrack(int mouseRow, int mouseCol, int row, int leftCol, int visibleCols) {
        return mouseRow == row && ScrollInteraction.hitTrack(mouseCol, leftCol, visibleCols);
    }

    public static int scrollFromPointer(int mouseCol, int leftCol, int visibleCols, int totalItems) {
        return ScrollInteraction.scrollFromPointer(mouseCol, leftCol, visibleCols, totalItems);
    }

    public static ScrollInteraction.Hit hit(
        int mouseRow,
        int mouseCol,
        int row,
        int leftCol,
        int visibleCols,
        int totalItems,
        int scroll
    ) {
        if (mouseRow != row) {
            return ScrollInteraction.Hit.NONE;
        }
        return ScrollInteraction.hit(mouseCol, leftCol, visibleCols, totalItems, scroll);
    }

    public static int pageScrollFromTrackClick(
        int currentScroll,
        int mouseCol,
        int leftCol,
        int visibleCols,
        int totalItems
    ) {
        return ScrollInteraction.pageScrollFromTrackClick(
            currentScroll,
            mouseCol,
            leftCol,
            visibleCols,
            totalItems
        );
    }

    public static void draw(
        CellBuffer buffer,
        int row,
        int leftCol,
        int visibleCols,
        int totalItems,
        int scroll,
        String trackGrapheme,
        CellStyle trackStyle,
        String thumbGrapheme,
        CellStyle thumbStyle
    ) {
        if (visibleCols <= 0 || totalItems <= 0) {
            return;
        }

        for (int i = 0; i < visibleCols; i++) {
            buffer.set(row, leftCol + i, trackGrapheme, trackStyle);
        }

        int thumbSize = ScrollInteraction.thumbSize(visibleCols, totalItems);
        int thumbOffset = ScrollInteraction.thumbOffset(visibleCols, totalItems, scroll);

        for (int i = 0; i < thumbSize; i++) {
            buffer.set(row, leftCol + thumbOffset + i, thumbGrapheme, thumbStyle);
        }
    }
}
