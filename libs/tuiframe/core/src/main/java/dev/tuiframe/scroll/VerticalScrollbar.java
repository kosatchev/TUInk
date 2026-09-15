package dev.tuiframe.scroll;

import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellStyle;
import java.util.Objects;

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

    /** Draws into a one-column track, honoring the canvas clip. */
    public static void draw(
        Canvas canvas,
        Rect track,
        int totalItems,
        int scroll,
        String trackGrapheme,
        CellStyle trackStyle,
        String thumbGrapheme,
        CellStyle thumbStyle
    ) {
        Objects.requireNonNull(canvas, "canvas");
        Objects.requireNonNull(track, "track");
        if (track.width() != 1) {
            throw new IllegalArgumentException("vertical scrollbar track must be one column wide");
        }
        int visibleRows = track.height();
        if (visibleRows <= 0 || totalItems <= 0) {
            return;
        }
        Cell trackCell = requireSingleCell(trackGrapheme, trackStyle, "trackGrapheme");
        Cell thumbCell = requireSingleCell(thumbGrapheme, thumbStyle, "thumbGrapheme");

        int mark = canvas.save();
        try {
            if (!canvas.clip(track)) {
                return;
            }
            for (int i = 0; i < visibleRows; i++) {
                canvas.put(track.x(), track.y() + i, trackCell);
            }

            int thumbSize = ScrollInteraction.thumbSize(visibleRows, totalItems);
            int thumbOffset = ScrollInteraction.thumbOffset(visibleRows, totalItems, scroll);

            for (int i = 0; i < thumbSize; i++) {
                canvas.put(track.x(), track.y() + thumbOffset + i, thumbCell);
            }
        } finally {
            canvas.restore(mark);
        }
    }

    private static Cell requireSingleCell(String grapheme, CellStyle style, String name) {
        Cell cell = new Cell(grapheme, style);
        if (cell.width() != 1) {
            throw new IllegalArgumentException(name + " must occupy exactly one cell");
        }
        return cell;
    }
}
