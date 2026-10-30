package dev.tuiframe.widget;

import dev.tuiframe.geometry.Rect;
import java.util.Objects;

/** Current-frame visibility required for a control or an application-owned action context. */
public enum InteractionVisibility {
    /** Atomic controls must fit completely inside their parent's interaction clip. */
    FULL_BOUNDS,
    /** Viewports and containers may explicitly accept a visible portion of their bounds. */
    VISIBLE_INTERSECTION;

    /** Bounds and clip share the placement's parent-local coordinate space; empty bounds never qualify. */
    public boolean allows(Rect bounds, Rect clip) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(clip, "clip");
        return !bounds.isEmpty() && (this == FULL_BOUNDS ? clip.contains(bounds) : clip.intersects(bounds));
    }
}
