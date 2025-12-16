package dev.tuink.tui.ui;

import dev.tuink.tui.render.CellBuffer;
import dev.tuink.render.Style;

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
        for (int i = 0; i < rendered.length(); i++) {
            char ch = rendered.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                return col + i;
            }
        }
        return col + (framed ? 1 : 0);
    }

    public int width() {
        return renderedText().length();
    }

    public boolean hit(int hitRow, int hitCol) {
        return hitRow == row && hitCol >= col && hitCol < col + renderedText().length();
    }

    public void draw(CellBuffer buffer, int cols, Style style) {
        String rendered = renderedText();
        for (int i = 0; i < rendered.length() && col + i < cols; i++) {
            buffer.set(row, col + i, String.valueOf(rendered.charAt(i)), style);
        }
    }

    private String renderedText() {
        return framed ? "[" + text + "]" : text;
    }
}
