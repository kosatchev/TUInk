package dev.tuink.terminal.screen;

import dev.tuink.terminal.geometry.Point;
import java.util.Objects;

/** Desired zero-based cursor position and visibility for a completed frame. */
public record ScreenCursor(Point position, boolean visible) {
    public ScreenCursor {
        Objects.requireNonNull(position, "position");
    }

    public static ScreenCursor visibleAt(Point position) {
        return new ScreenCursor(position, true);
    }

    public static ScreenCursor hidden() {
        return new ScreenCursor(Point.ORIGIN, false);
    }
}
