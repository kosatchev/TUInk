package dev.tuink.terminal.input;

import java.util.Objects;
import java.util.Set;

/** A zero-based terminal pointer event. */
public record PointerEvent(
    int row,
    int column,
    Kind kind,
    Button button,
    Set<KeyStroke.Modifier> modifiers
) implements TuiInputEvent {
    public PointerEvent {
        if (row < 0 || column < 0) {
            throw new IllegalArgumentException("Pointer coordinates must be non-negative");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(button, "button");
        modifiers = Set.copyOf(Objects.requireNonNull(modifiers, "modifiers"));
    }

    public enum Kind {
        PRESS,
        RELEASE,
        MOVE,
        DRAG,
        SCROLL_UP,
        SCROLL_DOWN,
        SCROLL_LEFT,
        SCROLL_RIGHT,
        UNKNOWN
    }

    public enum Button {
        LEFT,
        MIDDLE,
        RIGHT,
        OTHER,
        NONE
    }
}
