package dev.tuink.tui.ui;

import dev.tuink.tui.render.CellBuffer;
import dev.tuink.tui.render.CellText;
import dev.tuink.core.DisplayText;
import dev.tuink.tui.style.CellStyle;

/**
 * Reusable single-line text button for TUI overlays.
 */
public final class Button {
    private final int row;
    private final int col;
    private final String text;
    private final boolean framed;

    public Button(int row, int col, String text) {
        this(row, col, text, true);
    }

    private Button(int row, int col, String text, boolean framed) {
        this.row = row;
        this.col = col;
        this.text = text;
        this.framed = framed;
    }

    public static Button plain(int row, int col, String text) {
        return new Button(row, col, text, false);
    }

    public int row() {
        return row;
    }

    public int col() {
        return col;
    }

    public String text() {
        return text;
    }

    public int cursorCol() {
        String rendered = renderedText();
        int visual = 0;
        for (DisplayText.Grapheme grapheme : DisplayText.graphemes(rendered)) {
            int codePoint = grapheme.text().codePointAt(0);
            if (Character.isLetterOrDigit(codePoint)) {
                return col + visual;
            }
            visual += Math.max(1, grapheme.width());
        }
        return col + (framed ? 1 : 0);
    }

    public int width() {
        return CellText.width(renderedText());
    }

    public boolean hit(int hitRow, int hitCol) {
        return hitRow == row && hitCol >= col && hitCol < col + width();
    }

    public void draw(CellBuffer buffer, int cols, CellStyle style) {
        drawAt(buffer, row, cols, style);
    }

    public void drawAt(CellBuffer buffer, int drawRow, int cols, CellStyle style) {
        CellText.draw(buffer, drawRow, col, renderedText(), style, cols);
    }

    public String renderedText() {
        return framed ? "[" + text + "]" : text;
    }
}
