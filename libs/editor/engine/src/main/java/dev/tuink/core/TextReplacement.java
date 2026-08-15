package dev.tuink.core;

import java.util.Objects;

/**
 * Core mutation value for one half-open UTF-16 text replacement.
 *
 * <p>This type is the narrow bridge used by {@code editor-session}; shared
 * frontend/plugin contracts remain owned by {@code editor-contract}.</p>
 */
public record TextReplacement(Position start, Position end, String replacement) {
    public TextReplacement {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(replacement, "replacement");
        if (compare(start, end) > 0) {
            throw new IllegalArgumentException("Replacement start must not follow its end");
        }
    }

    private static int compare(Position left, Position right) {
        int lineOrder = Integer.compare(left.line(), right.line());
        return lineOrder != 0 ? lineOrder : Integer.compare(left.col(), right.col());
    }
}
