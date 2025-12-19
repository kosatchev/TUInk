package dev.tuink.tui.ui;

import dev.tuink.render.Style;

/**
 * Reusable single-line transient notification overlay.
 */
public final class NotificationBar {
    private final String text;
    private final Style style;

    private NotificationBar(String text, Style style) {
        this.text = text;
        this.style = style;
    }

    public static NotificationBar hidden() {
        return new NotificationBar(null, null);
    }

    public static NotificationBar of(String text, Style style) {
        if (text == null || text.isBlank()) {
            return hidden();
        }
        return new NotificationBar(text, style);
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
}

