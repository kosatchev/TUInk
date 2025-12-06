package dev.tuink.tui.render;

import dev.tuink.render.Style;

/**
 * Reusable horizontal scrollbar painter (track + thumb) for cell buffers.
 */
public final class HorizontalScrollbar {
    public enum Hit {
        NONE,
        THUMB,
        TRACK
    }

    private HorizontalScrollbar() {
    }

    public static boolean hitTrack(int mouseRow, int mouseCol, int row, int leftCol, int visibleCols) {
        return visibleCols > 0 && mouseRow == row && mouseCol >= leftCol && mouseCol < leftCol + visibleCols;
    }

    public static int scrollFromPointer(int mouseCol, int leftCol, int visibleCols, int totalItems) {
        int maxScroll = Math.max(0, totalItems - Math.max(0, visibleCols));
        if (maxScroll <= 0 || visibleCols <= 0) {
            return 0;
        }
        int clampedCol = Math.max(leftCol, Math.min(mouseCol, leftCol + visibleCols - 1));
        int offset = clampedCol - leftCol;
        return (int) Math.round((offset / (double) Math.max(1, visibleCols - 1)) * maxScroll);
    }

    public static Hit hit(
        int mouseRow,
        int mouseCol,
        int row,
        int leftCol,
        int visibleCols,
        int totalItems,
        int scroll
    ) {
        if (!hitTrack(mouseRow, mouseCol, row, leftCol, visibleCols)) {
            return Hit.NONE;
        }
        int thumbLeft = leftCol + thumbOffset(visibleCols, totalItems, scroll);
        int thumbRight = thumbLeft + thumbSize(visibleCols, totalItems) - 1;
        return mouseCol >= thumbLeft && mouseCol <= thumbRight ? Hit.THUMB : Hit.TRACK;
    }

    public static int pageScrollFromTrackClick(
        int currentScroll,
        int mouseCol,
        int leftCol,
        int visibleCols,
        int totalItems
    ) {
        int maxScroll = maxScroll(visibleCols, totalItems);
        if (maxScroll <= 0) {
            return 0;
        }
        int clamped = clamp(currentScroll, 0, maxScroll);
        int thumbLeft = leftCol + thumbOffset(visibleCols, totalItems, clamped);
        int thumbRight = thumbLeft + thumbSize(visibleCols, totalItems) - 1;
        int page = Math.max(1, visibleCols - 1);
        if (mouseCol < thumbLeft) {
            return clamp(clamped - page, 0, maxScroll);
        }
        if (mouseCol > thumbRight) {
            return clamp(clamped + page, 0, maxScroll);
        }
        return clamped;
    }

    public static void draw(
        CellBuffer buffer,
        int row,
        int leftCol,
        int visibleCols,
        int totalItems,
        int scroll,
        String trackGrapheme,
        Style trackStyle,
        String thumbGrapheme,
        Style thumbStyle
    ) {
        if (visibleCols <= 0 || totalItems <= 0) {
            return;
        }

        for (int i = 0; i < visibleCols; i++) {
            buffer.set(row, leftCol + i, trackGrapheme, trackStyle);
        }

        int thumbSize = thumbSize(visibleCols, totalItems);
        int thumbOffset = thumbOffset(visibleCols, totalItems, scroll);

        for (int i = 0; i < thumbSize; i++) {
            buffer.set(row, leftCol + thumbOffset + i, thumbGrapheme, thumbStyle);
        }
    }

    private static int thumbSize(int visibleCols, int totalItems) {
        int size = Math.max(1, (int) Math.round((visibleCols / (double) Math.max(visibleCols, totalItems)) * visibleCols));
        return Math.min(visibleCols, size);
    }

    private static int thumbOffset(int visibleCols, int totalItems, int scroll) {
        int thumbSize = thumbSize(visibleCols, totalItems);
        int thumbTravel = Math.max(0, visibleCols - thumbSize);
        int maxScroll = maxScroll(visibleCols, totalItems);
        int clampedScroll = clamp(scroll, 0, maxScroll);
        return maxScroll == 0 ? 0 : (int) Math.round((clampedScroll / (double) maxScroll) * thumbTravel);
    }

    private static int maxScroll(int visibleCols, int totalItems) {
        return Math.max(0, totalItems - visibleCols);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
