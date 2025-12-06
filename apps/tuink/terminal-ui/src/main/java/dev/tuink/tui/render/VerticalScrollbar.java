package dev.tuink.tui.render;

import dev.tuink.render.Style;

/**
 * Reusable vertical scrollbar painter (track + thumb) for cell buffers.
 */
public final class VerticalScrollbar {
    public enum Hit {
        NONE,
        THUMB,
        TRACK
    }

    private VerticalScrollbar() {
    }

    public static boolean hitTrack(int mouseRow, int mouseCol, int topRow, int col, int visibleRows) {
        return visibleRows > 0 && mouseCol == col && mouseRow >= topRow && mouseRow < topRow + visibleRows;
    }

    public static int scrollFromPointer(int mouseRow, int topRow, int visibleRows, int totalItems) {
        int maxScroll = Math.max(0, totalItems - Math.max(0, visibleRows));
        if (maxScroll <= 0 || visibleRows <= 0) {
            return 0;
        }
        int clampedRow = Math.max(topRow, Math.min(mouseRow, topRow + visibleRows - 1));
        int offset = clampedRow - topRow;
        return (int) Math.round((offset / (double) Math.max(1, visibleRows - 1)) * maxScroll);
    }

    public static Hit hit(
        int mouseRow,
        int mouseCol,
        int topRow,
        int col,
        int visibleRows,
        int totalItems,
        int scroll
    ) {
        if (!hitTrack(mouseRow, mouseCol, topRow, col, visibleRows)) {
            return Hit.NONE;
        }
        int thumbTop = topRow + thumbOffset(visibleRows, totalItems, scroll);
        int thumbBottom = thumbTop + thumbSize(visibleRows, totalItems) - 1;
        return mouseRow >= thumbTop && mouseRow <= thumbBottom ? Hit.THUMB : Hit.TRACK;
    }

    public static int pageScrollFromTrackClick(
        int currentScroll,
        int mouseRow,
        int topRow,
        int visibleRows,
        int totalItems
    ) {
        int maxScroll = maxScroll(visibleRows, totalItems);
        if (maxScroll <= 0) {
            return 0;
        }
        int clamped = clamp(currentScroll, 0, maxScroll);
        int thumbTop = topRow + thumbOffset(visibleRows, totalItems, clamped);
        int thumbBottom = thumbTop + thumbSize(visibleRows, totalItems) - 1;
        int page = Math.max(1, visibleRows - 1);
        if (mouseRow < thumbTop) {
            return clamp(clamped - page, 0, maxScroll);
        }
        if (mouseRow > thumbBottom) {
            return clamp(clamped + page, 0, maxScroll);
        }
        return clamped;
    }

    public static void draw(
        CellBuffer buffer,
        int topRow,
        int col,
        int visibleRows,
        int totalItems,
        int scroll,
        String trackGrapheme,
        Style trackStyle,
        String thumbGrapheme,
        Style thumbStyle
    ) {
        if (visibleRows <= 0 || totalItems <= 0) {
            return;
        }

        for (int i = 0; i < visibleRows; i++) {
            buffer.set(topRow + i, col, trackGrapheme, trackStyle);
        }

        int thumbSize = thumbSize(visibleRows, totalItems);
        int thumbOffset = thumbOffset(visibleRows, totalItems, scroll);

        for (int i = 0; i < thumbSize; i++) {
            buffer.set(topRow + thumbOffset + i, col, thumbGrapheme, thumbStyle);
        }
    }

    private static int thumbSize(int visibleRows, int totalItems) {
        int size = Math.max(1, (int) Math.round((visibleRows / (double) Math.max(visibleRows, totalItems)) * visibleRows));
        return Math.min(visibleRows, size);
    }

    private static int thumbOffset(int visibleRows, int totalItems, int scroll) {
        int thumbSize = thumbSize(visibleRows, totalItems);
        int thumbTravel = Math.max(0, visibleRows - thumbSize);
        int maxScroll = maxScroll(visibleRows, totalItems);
        int clampedScroll = clamp(scroll, 0, maxScroll);
        return maxScroll == 0 ? 0 : (int) Math.round((clampedScroll / (double) maxScroll) * thumbTravel);
    }

    private static int maxScroll(int visibleRows, int totalItems) {
        return Math.max(0, totalItems - visibleRows);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
