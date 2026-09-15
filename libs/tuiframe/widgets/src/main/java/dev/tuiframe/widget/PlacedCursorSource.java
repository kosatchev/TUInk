package dev.tuiframe.widget;

import dev.tuiframe.geometry.Point;
import java.util.Optional;

/** Optional parent-local cursor geometry exposed by a placed widget. */
public interface PlacedCursorSource {
    /**
     * Returns the desired zero-based cursor point in the placement's
     * parent-local coordinate space, or empty when this widget does not own
     * the cursor for the supplied focus snapshot.
     */
    Optional<Point> cursorPosition(FocusContext context);
}
