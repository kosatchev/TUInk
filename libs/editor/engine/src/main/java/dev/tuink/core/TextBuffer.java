package dev.tuink.core;

import java.util.List;
import java.util.Objects;

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

    /** Immutable ranged text; custom strategies may opt into block sharing. */
    default LineText lineText(int line) { return LineText.of(getLine(line)); }

    void setLine(int line, String text);

    default void setLineText(int line, LineText text) { setLine(line, text.toString()); }

    default void insertLineText(int index, LineText text) { insertLine(index, text.toString()); }

    /** Inserts immutable line blocks together so a retained suffix moves once. */
    default void insertLineTexts(int index, List<LineText> text) {
        for (int offset = 0; offset < text.size(); offset++) insertLineText(index + offset, text.get(offset));
    }

    void insertLine(int index, String text);

    /** Inserts a block in list order; storage strategies may avoid repeated suffix shifts. */
    default void insertLines(int index, List<String> text) {
        Objects.requireNonNull(text, "text");
        Objects.checkFromToIndex(index, index, lineCount());
        for (int line = 0; line < text.size(); line++) {
            insertLine(index + line, text.get(line));
        }
    }

    String removeLine(int index);

    /** Removes the half-open line range, preserving the order of all remaining lines. */
    default void removeLines(int fromIndex, int toIndex) {
        Objects.checkFromToIndex(fromIndex, toIndex, lineCount());
        for (int line = toIndex - 1; line >= fromIndex; line--) {
            removeLine(line);
        }
    }

    void clear();
}
