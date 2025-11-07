package dev.tuink.core;

/**
 * Immutable cursor position in the text model.
 */
public record Position(int line, int col) {
    public Position {
        if (line < 0 || col < 0) {
            throw new IllegalArgumentException("line and col must be >= 0");
        }
    }
}
