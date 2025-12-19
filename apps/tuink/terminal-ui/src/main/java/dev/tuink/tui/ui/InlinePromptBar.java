package dev.tuink.tui.ui;

import dev.tuink.render.Style;

/**
 * Reusable single-line interactive prompt bar displayed above status bar.
 */
public final class InlinePromptBar {
    private final String text;
    private final Style style;
    private final int cursorCol;

    private InlinePromptBar(String text, Style style, int cursorCol) {
        this.text = text;
        this.style = style;
        this.cursorCol = Math.max(1, cursorCol);
    }

    public static InlinePromptBar hidden() {
        return new InlinePromptBar(null, null, 1);
    }

    public static InlinePromptBar of(String text, Style style, int cursorCol) {
        if (text == null || text.isBlank()) {
            return hidden();
        }
        return new InlinePromptBar(text, style, cursorCol);
    }

    public boolean visible() {
        return text != null;
    }

    public int height() {
        return visible() ? 1 : 0;
    }

    public String text() {
        return text;
    }

    public Style style() {
        return style;
    }

    public int cursorCol() {
        return cursorCol;
    }
}

