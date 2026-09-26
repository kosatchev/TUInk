package dev.tuiframe.scroll;

import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellStyle;
import java.util.Objects;

/**
 * Reusable horizontal scrollbar painter (track + thumb) for a Canvas.
 */
public final class HorizontalScrollbar {
    private HorizontalScrollbar() {
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

    /** Draws into a one-row track, honoring the canvas clip. */
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
        if (track.height() != 1) {
            throw new IllegalArgumentException("horizontal scrollbar track must be one row high");
        }
        int visibleCols = track.width();
        if (visibleCols <= 0 || totalItems <= 0) {
            return;
        }
        Cell trackCell = requireSingleCell(trackGrapheme, trackStyle, "trackGrapheme");
        Cell thumbCell = requireSingleCell(thumbGrapheme, thumbStyle, "thumbGrapheme");

        int mark = canvas.save();
        try {
            if (!canvas.clip(track)) {
                return;
            }
            for (int i = 0; i < visibleCols; i++) {
                canvas.put(track.x() + i, track.y(), trackCell);
            }

            int thumbSize = ScrollInteraction.thumbSize(visibleCols, totalItems);
            int thumbOffset = ScrollInteraction.thumbOffset(visibleCols, totalItems, scroll);

            for (int i = 0; i < thumbSize; i++) {
                canvas.put(track.x() + thumbOffset + i, track.y(), thumbCell);
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
