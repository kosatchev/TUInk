package dev.tuiframe.widget;

import java.util.Objects;

/**
 * One immutable target binding a stable ID to its typed placed handler.
 * Authored enabled/focusable flags are separate from current-frame geometry;
 * use {@link #interactive()} or {@link #focusEligible()} when admitting input.
 */
public record RoutedTarget<K, A>(
    K id,
    PlacedInteractiveWidget<A> placement,
    FocusSpace space,
    boolean focusable,
    boolean enabled,
    InteractionVisibility visibility
) {
    public RoutedTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(visibility, "visibility");
    }

    public RoutedTarget(K id, PlacedInteractiveWidget<A> placement, FocusSpace space,
        boolean focusable, boolean enabled) {
        this(id, placement, space, focusable, enabled, InteractionVisibility.FULL_BOUNDS);
    }

    public RoutedTarget(K id, PlacedInteractiveWidget<A> placement, FocusSpace space) {
        this(id, placement, space, true, true, InteractionVisibility.FULL_BOUNDS);
    }

    /** Whether normal input may reach this target in the current placement. */
    public boolean interactive() {
        return enabled && placement.interactionReady() && visibility.allows(placement.bounds(), space.parentClip());
    }

    /** Shared eligibility for focus reconciliation, explicit focus and keyboard traversal. */
    public boolean focusEligible() {
        return focusable && interactive();
    }

    FocusContext context(boolean focused) {
        return new FocusContext(focused && focusEligible(), interactive(), space);
    }
}
