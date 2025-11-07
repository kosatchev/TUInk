package dev.tuink.render;

/**
 * Half-open style range [start, end).
 */
public record StyleSpan(int start, int end, Style style) {
    public StyleSpan {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid span range");
        }
        if (style == null) {
            throw new IllegalArgumentException("style must not be null");
        }
    }
}
