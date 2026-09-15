package dev.tuiframe.widget;

import java.util.Objects;

/** One immutable target binding a stable ID to its typed placed handler. */
public record RoutedTarget<K, A>(
    K id,
    PlacedInteractiveWidget<A> placement,
    FocusSpace space,
    boolean focusable,
    boolean enabled
) {
    public RoutedTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(space, "space");
    }

    public RoutedTarget(K id, PlacedInteractiveWidget<A> placement, FocusSpace space) {
        this(id, placement, space, true, true);
    }

    FocusContext context(boolean focused) {
        return new FocusContext(focused, enabled, space);
    }
}
