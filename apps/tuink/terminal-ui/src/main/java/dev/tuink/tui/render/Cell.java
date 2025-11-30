package dev.tuink.tui.render;

import dev.tuink.render.Style;

/**
 * Single rendered cell independent from concrete UI toolkit.
 */
public record Cell(String grapheme, Style style) {
    public static final Cell EMPTY = new Cell(" ", Style.DEFAULT);

    public Cell {
        if (grapheme == null || grapheme.isEmpty()) {
            throw new IllegalArgumentException("grapheme must not be empty");
        }
        if (style == null) {
            throw new IllegalArgumentException("style must not be null");
        }
    }
}
