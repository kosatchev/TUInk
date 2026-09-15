package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import java.util.Objects;

/** Immutable passive placement produced by layout and reusable across paint-only frames. */
public interface PlacedWidget {
    Rect bounds();

    void paint(Canvas canvas, Theme theme, WidgetState state);

    default void paint(Canvas canvas, Theme theme) {
        paint(canvas, theme, WidgetState.NORMAL);
    }

    /**
     * Hit test in the placement's parent-local coordinate space. The argument
     * order matches terminal events, but a translated parent must first map a
     * physical pointer into its local coordinates.
     */
    default boolean hit(int row, int column) {
        return bounds().contains(column, row);
    }

    /** Hit test for a parent-local x/y point. */
    default boolean hit(Point point) {
        return bounds().contains(Objects.requireNonNull(point, "point"));
    }
}
