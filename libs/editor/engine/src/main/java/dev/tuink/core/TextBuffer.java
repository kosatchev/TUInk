package dev.tuink.core;

/**
 * Replaceable line-based text storage abstraction.
 *
 * <p>Current storage uses {@link SimpleLineBuffer}. Other strategies can implement this interface.</p>
 */
public interface TextBuffer {
    int lineCount();

    String getLine(int line);

    void setLine(int line, String text);

    void insertLine(int index, String text);

    String removeLine(int index);

    void clear();
}
