package dev.tuink.terminal.widget;

import dev.tuink.terminal.geometry.Point;
import dev.tuink.terminal.input.PointerEvent;
import java.util.Objects;
import java.util.Optional;

/** Immutable focus and coordinate snapshot supplied to one placed widget. */
public record FocusContext(boolean focused, boolean enabled, FocusSpace space) {
    public FocusContext {
        Objects.requireNonNull(space, "space");
        if (focused && !enabled) {
            throw new IllegalArgumentException("a disabled widget cannot be focused");
        }
    }

    public WidgetState widgetState() {
        return new WidgetState(focused, enabled);
    }

    public Optional<Point> localPoint(PointerEvent event) {
        return space.localPoint(event);
    }
}
