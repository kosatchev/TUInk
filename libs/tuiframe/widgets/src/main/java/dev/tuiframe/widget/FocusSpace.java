package dev.tuiframe.widget;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
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

    /**
     * Maps a parent-local point back into terminal-global coordinates while
     * enforcing the same parent clip used for pointer routing.
     */
    public Optional<Point> terminalPoint(Point localPoint) {
        Point required = Objects.requireNonNull(localPoint, "localPoint");
        if (!parentClip.contains(required)) {
            return Optional.empty();
        }
        long terminalX = (long) terminalOrigin.x() + required.x();
        long terminalY = (long) terminalOrigin.y() + required.y();
        if (terminalX < 0 || terminalX > Integer.MAX_VALUE
            || terminalY < 0 || terminalY > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of(new Point((int) terminalX, (int) terminalY));
    }
}
