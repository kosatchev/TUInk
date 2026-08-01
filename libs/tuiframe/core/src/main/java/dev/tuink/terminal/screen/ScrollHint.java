package dev.tuink.terminal.screen;

import dev.tuink.terminal.geometry.Rect;
import java.util.Objects;

/**
 * Optional optimization hint for presenting a frame. Ignoring a hint must not
 * change the resulting screen.
 */
public sealed interface ScrollHint permits ScrollHint.None, ScrollHint.Vertical, ScrollHint.Horizontal {
    ScrollHint NONE = new None();

    static ScrollHint none() {
        return NONE;
    }

    record None() implements ScrollHint {
    }

    /** Region whose content may be presented using vertical terminal scrolling. */
    record Vertical(Rect region) implements ScrollHint {
        public Vertical {
            requireRegion(region);
        }
    }

    /**
     * Region whose content moved by a non-zero number of logical columns.
     * Positive values mean that the viewport moved to the right.
     */
    record Horizontal(Rect region, int deltaColumns) implements ScrollHint {
        public Horizontal {
            requireRegion(region);
            if (deltaColumns == 0) {
                throw new IllegalArgumentException("deltaColumns must not be zero");
            }
        }
    }

    private static void requireRegion(Rect region) {
        Objects.requireNonNull(region, "region");
        if (region.isEmpty()) {
            throw new IllegalArgumentException("scroll region must not be empty");
        }
    }
}
