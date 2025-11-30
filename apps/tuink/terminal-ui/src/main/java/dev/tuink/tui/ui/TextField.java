package dev.tuink.tui.ui;

/**
 * Reusable single-line text input buffer for TUI prompts.
 */
public final class TextField {
    private final StringBuilder value = new StringBuilder();
    private int cursor;

    public void insert(char ch) {
        value.insert(cursor, ch);
        cursor++;
    }

    public void backspace() {
        if (cursor > 0) {
            value.deleteCharAt(cursor - 1);
            cursor--;
        }
    }

    public void deleteForward() {
        if (cursor < value.length()) {
            value.deleteCharAt(cursor);
        }
    }

    public void moveLeft() {
        if (cursor > 0) {
            cursor--;
        }
    }

    public void moveRight() {
        if (cursor < value.length()) {
            cursor++;
        }
    }

    public void moveHome() {
        cursor = 0;
    }

    public void moveEnd() {
        cursor = value.length();
    }

    public void clear() {
        value.setLength(0);
        cursor = 0;
    }

    public void set(String text) {
        value.setLength(0);
        if (text != null && !text.isEmpty()) {
            value.append(text);
        }
        cursor = value.length();
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    public int length() {
        return value.length();
    }

    public int cursor() {
        return cursor;
    }

    public String text() {
        return value.toString();
    }
}
