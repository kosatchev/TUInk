package dev.tuiframe.screen;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.cell.CellBuffer;
import java.util.Objects;

/**
 * Completed virtual screen submitted to a screen presenter.
 *
 * <p>The frame takes read ownership of {@code cells}. Callers must not mutate
 * the buffer after constructing a frame that has been submitted for
 * presentation.</p>
 */
public record Frame(CellBuffer cells, ScreenCursor cursor, ScrollHint scrollHint) {
    public Frame {
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(scrollHint, "scrollHint");
        if (cells.cols() <= 0 || cells.rows() <= 0) {
            throw new IllegalArgumentException("frame width and height must be positive");
        }

        Rect bounds = bounds(cells);
        if (cursor.visible() && !bounds.contains(cursor.position())) {
            throw new IllegalArgumentException("visible cursor must be inside frame bounds");
        }

        Rect hintRegion = hintRegion(scrollHint);
        if (hintRegion != null && !bounds.contains(hintRegion)) {
            throw new IllegalArgumentException("scroll hint region must be inside frame bounds");
        }
    }

    public Size size() {
        return new Size(cells.cols(), cells.rows());
    }

    public Rect bounds() {
        return bounds(cells);
    }

    private static Rect bounds(CellBuffer cells) {
        return new Rect(0, 0, cells.cols(), cells.rows());
    }

    private static Rect hintRegion(ScrollHint hint) {
        if (hint instanceof ScrollHint.Vertical vertical) {
            return vertical.region();
        }
        if (hint instanceof ScrollHint.Horizontal horizontal) {
            return horizontal.region();
        }
        return null;
    }
}
