package dev.tuiframe.scroll;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import java.util.Objects;

/** Content and scrollbar geometry, including an optional pinned right column. */
public record ScrollLayout(
    Rect contentBounds,
    Rect leadingBounds,
    Rect trailingBounds,
    Rect verticalScrollbarBounds,
    Rect horizontalScrollbarBounds
) {
    public ScrollLayout {
        Objects.requireNonNull(contentBounds, "contentBounds");
        Objects.requireNonNull(leadingBounds, "leadingBounds");
    }

    public static ScrollLayout vertical(Rect bounds, int rowCount) {
        return calculate(bounds, rowCount, 0, 0);
    }

    public static ScrollLayout calculate(Rect bounds, int rowCount, int horizontalExtent, int trailingExtent) {
        Objects.requireNonNull(bounds, "bounds");
        if (rowCount < 0 || horizontalExtent < 0 || trailingExtent < 0) {
            throw new IllegalArgumentException("content extents must not be negative");
        }
        // A one-cell track cannot map dragging. In a tiny viewport, existing
        // vertical overflow takes precedence over a horizontal scrollbar.
        boolean vertical = bounds.width() >= 2 && bounds.height() >= 2 && rowCount > bounds.height();
        int widthWithVertical = Math.max(0, bounds.width() - (vertical ? 1 : 0));
        int leadingWithVertical = leadingWidth(widthWithVertical, trailingExtent);
        boolean horizontal = bounds.height() >= 2
            && (!vertical || bounds.height() >= 3)
            && leadingWithVertical >= 2
            && horizontalExtent > leadingWithVertical;
        if (!vertical && horizontal) {
            int remainingRows = bounds.height() - 1;
            if (bounds.width() >= 2 && remainingRows >= 2 && rowCount > remainingRows
                && leadingWidth(bounds.width() - 1, trailingExtent) >= 2) {
                vertical = true;
            }
        }
        int rows = Math.max(0, bounds.height() - (horizontal ? 1 : 0));
        int width = Math.max(0, bounds.width() - (vertical ? 1 : 0));
        int leadingWidth = leadingWidth(width, trailingExtent);
        int detailWidth = width - leadingWidth;
        Rect content = new Rect(bounds.x(), bounds.y(), width, rows);
        Rect leading = new Rect(bounds.x(), bounds.y(), leadingWidth, rows);
        Rect trailing = detailWidth == 0 ? null
            : new Rect(bounds.x() + leadingWidth, bounds.y(), detailWidth, rows);
        Rect verticalTrack = vertical
            ? new Rect(bounds.rightExclusive() - 1, bounds.y(), 1, rows) : null;
        Rect horizontalTrack = horizontal
            ? new Rect(bounds.x(), bounds.y() + rows, leadingWidth, 1) : null;
        return new ScrollLayout(content, leading, trailing, verticalTrack, horizontalTrack);
    }

    private static int leadingWidth(int width, int trailingExtent) {
        return Math.max(0, width - Math.min(trailingExtent, Math.max(0, width - 1)));
    }

    /** Paints the unused bottom strip and the junction of adjacent tracks. */
    public void paintCorner(Canvas canvas, ScrollStyle style) {
        Objects.requireNonNull(canvas, "canvas");
        Objects.requireNonNull(style, "style");
        if (horizontalScrollbarBounds == null) {
            return;
        }
        Rect horizontal = horizontalScrollbarBounds;
        int unusedWidth = Math.max(0, contentBounds.rightExclusive() - horizontal.rightExclusive());
        if (unusedWidth > 0) {
            canvas.fill(new Rect(horizontal.rightExclusive(), horizontal.y(), unusedWidth, 1), style.track());
        }
        if (verticalScrollbarBounds != null) {
            Rect vertical = verticalScrollbarBounds;
            String junction = horizontal.rightExclusive() == vertical.x() ? "┘" : " ";
            canvas.put(vertical.x(), horizontal.y(), junction, style.track());
        }
    }
}
