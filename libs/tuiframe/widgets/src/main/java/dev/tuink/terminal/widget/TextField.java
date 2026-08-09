package dev.tuink.terminal.widget;

import dev.tuink.terminal.text.TerminalText;

/**
 * Reusable single-line text input buffer for TUI prompts.
 */
public final class TextField {
    private static final int TAB_WIDTH = 4;

    private final StringBuilder value = new StringBuilder();
    private int cursor;

    public void insert(char ch) {
        insert(String.valueOf(ch));
    }

    public void insert(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        cursor = TerminalText.floorBoundary(value.toString(), cursor);
        value.insert(cursor, text);
        cursor += text.length();
    }

    public void backspace() {
        if (cursor > 0) {
            String text = value.toString();
            TerminalText.Metrics metrics = TerminalText.metrics(text, TAB_WIDTH);
            int end = metrics.ceilBoundary(cursor);
            int start = metrics.previousBoundary(end);
            value.delete(start, end);
            cursor = start;
        }
    }

    public void deleteForward() {
        if (cursor < value.length()) {
            String text = value.toString();
            TerminalText.Metrics metrics = TerminalText.metrics(text, TAB_WIDTH);
            int start = metrics.floorBoundary(cursor);
            int end = metrics.nextBoundary(start);
            value.delete(start, end);
            cursor = start;
        }
    }

    public void moveLeft() {
        if (cursor > 0) {
            cursor = TerminalText.previousBoundary(value.toString(), cursor);
        }
    }

    public void moveRight() {
        if (cursor < value.length()) {
            cursor = TerminalText.nextBoundary(value.toString(), cursor);
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

    public int cursorCellColumn() {
        return TerminalText.visualColumnAt(value.toString(), cursor, TAB_WIDTH);
    }

    public int displayWidth() {
        return TerminalText.visualWidth(value.toString(), TAB_WIDTH);
    }

    public String text() {
        return value.toString();
    }
}
