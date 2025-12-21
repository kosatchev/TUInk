package dev.tuink.tui.ui;

import dev.tuink.render.Style;

/**
 * Reusable interactive prompt bar displayed above status bar.
 */
public final class InlinePromptBar {
    private final String text;
    private final Style style;
    private final int cursorRow;
    private final int cursorCol;

    private InlinePromptBar(String text, Style style, int cursorRow, int cursorCol) {
        this.text = text;
        this.style = style;
        this.cursorRow = Math.max(1, cursorRow);
        this.cursorCol = Math.max(1, cursorCol);
    }

    public static InlinePromptBar hidden() {
        return new InlinePromptBar(null, null, 1, 1);
    }

    public static InlinePromptBar of(String text, Style style, int cursorCol) {
        return of(text, style, 1, cursorCol);
    }

    public static InlinePromptBar of(String text, Style style, int cursorRow, int cursorCol) {
        if (text == null || text.isBlank()) {
            return hidden();
        }
        return new InlinePromptBar(text, style, cursorRow, cursorCol);
    }

    public boolean visible() {
        return text != null;
    }

    public int height() {
        if (!visible()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    public String text() {
        return text;
    }

    public Style style() {
        return style;
    }

    public int cursorRow() {
        return cursorRow;
    }

    public int cursorCol() {
        return cursorCol;
    }
}
