package dev.tuink.core;

/**
 * Replaceable line-based text storage abstraction.
 *
 * <p>Current storage uses {@link SimpleLineBuffer}. Other strategies can implement this interface.</p>
 */
public interface TextBuffer {
    /**
     * Creates an independent empty buffer using the same storage strategy.
     *
     * <p>The returned buffer must contain one empty logical line. This lets
     * {@link TextModel} take ownership without retaining an externally mutable alias.</p>
     */
    TextBuffer createEmptySibling();

    int lineCount();

    String getLine(int line);

    void setLine(int line, String text);

    void insertLine(int index, String text);

    String removeLine(int index);

    void clear();
}
