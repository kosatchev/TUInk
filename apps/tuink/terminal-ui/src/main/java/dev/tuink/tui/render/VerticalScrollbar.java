package dev.tuink.tui.render;

import dev.tuink.render.Style;

/**
 * Reusable vertical scrollbar painter (track + thumb) for cell buffers.
 */
public final class VerticalScrollbar {
    private VerticalScrollbar() {
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

        int thumbSize = Math.max(1, (int) Math.round((visibleRows / (double) Math.max(visibleRows, totalItems)) * visibleRows));
        thumbSize = Math.min(visibleRows, thumbSize);
        int thumbTravel = Math.max(0, visibleRows - thumbSize);
        int maxScroll = Math.max(0, totalItems - visibleRows);
        int clampedScroll = Math.max(0, Math.min(scroll, maxScroll));
        int thumbOffset = maxScroll == 0 ? 0 : (int) Math.round((clampedScroll / (double) maxScroll) * thumbTravel);

        for (int i = 0; i < thumbSize; i++) {
            buffer.set(topRow + thumbOffset + i, col, thumbGrapheme, thumbStyle);
        }
    }
}
