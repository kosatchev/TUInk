package dev.tuiframe.widget;

import java.util.Objects;
import java.util.Optional;

/** Focus routing decision and context from one immutable scope snapshot. */
public record FocusRoute<K, A>(
    Optional<RoutedTarget<K, A>> target,
    Optional<FocusContext> context,
    boolean focusChanged,
    boolean navigationConsumed
) {
    public FocusRoute {
        target = Objects.requireNonNull(target, "target");
        context = Objects.requireNonNull(context, "context");
        if (target.isPresent() != context.isPresent()) {
            throw new IllegalArgumentException("target and context must be present together");
        }
    }
}
