package dev.tuink.terminal.widget;

import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.geometry.Size;

/**
 * Passive, immediate-mode-friendly layout/paint boundary. Interactive widgets
 * add a typed event contract separately instead of weakening this interface.
 */
public interface Widget {
    /** Returns the desired size, constrained by {@code available}. */
    Size measure(Size available);

    /**
     * Places the widget in an application-owned final rectangle. Bounds,
     * Canvas operations, and hit points use the same parent-local coordinates.
     */
    PlacedWidget layout(Rect bounds);
}
