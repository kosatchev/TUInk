package dev.tuink.core;

/**
 * Immutable viewport snapshot used by editor state transitions.
 */
public record ViewportState(
    int topLine,
    int leftColumn,
    int leftVisualColumn
) {
    public ViewportState {
        topLine = Math.max(0, topLine);
        leftColumn = Math.max(0, leftColumn);
        leftVisualColumn = Math.max(0, leftVisualColumn);
    }
}
