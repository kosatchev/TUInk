package dev.tuink.tui.render;

import dev.tuink.core.DisplayText;
import dev.tuink.tui.style.CellStyle;

/**
 * One terminal cell. A width-two grapheme is followed by a continuation cell.
 */
public record Cell(String grapheme, CellStyle style, int width, boolean continuation) {
    public static final Cell EMPTY = new Cell(" ", CellStyle.DEFAULT);

    public Cell(String grapheme, CellStyle style) {
        this(grapheme, style, 0, false);
    }

    public Cell {
        if (style == null) {
            throw new IllegalArgumentException("style must not be null");
        }
        if (continuation) {
            grapheme = " ";
            width = 0;
        } else {
            grapheme = DisplayText.sanitizeGrapheme(grapheme);
            width = Math.max(1, DisplayText.graphemeWidth(grapheme));
            if (width > 2) {
                width = 2;
            }
        }
    }

    public static Cell continuation(CellStyle style) {
        return new Cell(" ", style, 0, true);
    }
}
