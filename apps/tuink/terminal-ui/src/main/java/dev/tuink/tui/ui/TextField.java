package dev.tuink.tui.ui;

import dev.tuink.core.DisplayText;

/**
 * Reusable single-line text input buffer for TUI prompts.
 */
public final class TextField {
    private final StringBuilder value = new StringBuilder();
    private int cursor;

    public void insert(char ch) {
        insert(String.valueOf(ch));
    }

    public void insert(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        cursor = DisplayText.floorBoundary(value.toString(), cursor);
        value.insert(cursor, text);
        cursor += text.length();
    }

    public void backspace() {
        if (cursor > 0) {
            String text = value.toString();
            int end = DisplayText.ceilBoundary(text, cursor);
            int start = DisplayText.previousBoundary(text, end);
            value.delete(start, end);
            cursor = start;
        }
    }

    public void deleteForward() {
        if (cursor < value.length()) {
            String text = value.toString();
            int start = DisplayText.floorBoundary(text, cursor);
            int end = DisplayText.nextBoundary(text, start);
            value.delete(start, end);
            cursor = start;
        }
    }

    public void moveLeft() {
        if (cursor > 0) {
            cursor = DisplayText.previousBoundary(value.toString(), cursor);
        }
    }

    public void moveRight() {
        if (cursor < value.length()) {
            cursor = DisplayText.nextBoundary(value.toString(), cursor);
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
        return DisplayText.visualColumnAt(value.toString(), cursor, 4);
    }

    public int displayWidth() {
        return DisplayText.visualWidth(value.toString(), 4);
    }

    public String text() {
        return value.toString();
    }
}
