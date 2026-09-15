package dev.tuiframe.cell;

import dev.tuiframe.text.TerminalText;
import dev.tuiframe.style.CellStyle;

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
            grapheme = TerminalText.sanitizeGrapheme(grapheme);
            if (!TerminalText.isSingleGrapheme(grapheme)) {
                throw new IllegalArgumentException("cell payload must contain exactly one grapheme");
            }
            width = Math.max(1, TerminalText.graphemeWidth(grapheme));
            if (width > 2) {
                width = 2;
            }
        }
    }

    public static Cell continuation(CellStyle style) {
        return new Cell(" ", style, 0, true);
    }
}
