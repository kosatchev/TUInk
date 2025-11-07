package dev.tuink.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple list-based text storage.
 */
public class SimpleLineBuffer implements TextBuffer {
    private final List<String> lines = new ArrayList<>();

    public SimpleLineBuffer() {
        lines.add("");
    }

    @Override
    public int lineCount() {
        return lines.size();
    }

    @Override
    public String getLine(int line) {
        return lines.get(line);
    }

    @Override
    public void setLine(int line, String text) {
        lines.set(line, text);
    }

    @Override
    public void insertLine(int index, String text) {
        lines.add(index, text);
    }

    @Override
    public String removeLine(int index) {
        return lines.remove(index);
    }

    @Override
    public void clear() {
        lines.clear();
        lines.add("");
    }
}
