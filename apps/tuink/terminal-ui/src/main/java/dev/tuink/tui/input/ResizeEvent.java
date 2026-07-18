package dev.tuink.tui.input;

/** A terminal resize measured in rows and columns. */
public record ResizeEvent(int rows, int columns) implements TuiInputEvent {
    public ResizeEvent {
        if (rows <= 0 || columns <= 0) {
            throw new IllegalArgumentException("Terminal size must be positive");
        }
    }
}
