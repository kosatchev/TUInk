package dev.tuiframe.widget;

import dev.tuiframe.geometry.Rect;
import java.util.Objects;

/** Immutable container context: child geometry, clipping and inherited widget roles. */
public record SurfaceContext(Rect bounds, Rect clip, Theme theme) {
    public SurfaceContext {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(clip, "clip");
        Objects.requireNonNull(theme, "theme");
        clip = bounds.intersection(clip);
    }

    public static SurfaceContext root(Rect bounds, Theme theme) { return new SurfaceContext(bounds, bounds, theme); }

    /** Allocates a child in parent-local coordinates, retaining the parent's style and clip. */
    public SurfaceContext child(Rect localBounds) {
        return new SurfaceContext(localBounds.translate(bounds.x(), bounds.y()), clip, theme);
    }

    /** Explicit child roles override the inherited surface; unknown roles keep their parent value. */
    public SurfaceContext withTheme(Theme overrides) {
        Objects.requireNonNull(overrides, "overrides");
        return new SurfaceContext(bounds, clip, role -> {
            var own = overrides.style(role);
            return own == null ? theme.style(role) : own;
        });
    }
}
