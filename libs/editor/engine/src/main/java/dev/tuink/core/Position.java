package dev.tuink.core;

/**
 * Immutable position in the text model. {@code col} is a raw UTF-16 offset;
 * interactive cursor operations snap it to grapheme boundaries, while search,
 * undo and edit ranges may intentionally keep exact offsets.
 */
public record Position(int line, int col) {
    public Position {
        if (line < 0 || col < 0) {
            throw new IllegalArgumentException("line and col must be >= 0");
        }
    }
}
