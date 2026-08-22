package dev.tuink.terminal.widget;

import dev.tuink.terminal.geometry.Point;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.input.PointerEvent;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps terminal-global pointer coordinates into a placement's parent-local
 * coordinate space while enforcing the parent clip.
 */
public record FocusSpace(Point terminalOrigin, Rect parentClip) {
    public FocusSpace {
        Objects.requireNonNull(terminalOrigin, "terminalOrigin");
        Objects.requireNonNull(parentClip, "parentClip");
    }

    public static FocusSpace root(Rect terminalClip) {
        return new FocusSpace(Point.ORIGIN, Objects.requireNonNull(terminalClip, "terminalClip"));
    }

    public Optional<Point> localPoint(PointerEvent event) {
        Objects.requireNonNull(event, "event");
        long localX = (long) event.column() - terminalOrigin.x();
        long localY = (long) event.row() - terminalOrigin.y();
        if (localX < Integer.MIN_VALUE || localX > Integer.MAX_VALUE
            || localY < Integer.MIN_VALUE || localY > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        Point point = new Point((int) localX, (int) localY);
        return parentClip.contains(point) ? Optional.of(point) : Optional.empty();
    }
}
