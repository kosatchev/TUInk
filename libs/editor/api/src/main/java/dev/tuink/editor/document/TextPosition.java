package dev.tuink.editor.document;

/**
 * A zero-based position in a document.
 *
 * <p>{@code utf16Offset} is an offset in the Java UTF-16 representation of a
 * line. It is not a grapheme index or a terminal-cell column.</p>
 */
public record TextPosition(int line, int utf16Offset) implements Comparable<TextPosition> {
    public TextPosition {
        if (line < 0 || utf16Offset < 0) {
            throw new IllegalArgumentException("Text position coordinates must be non-negative");
        }
    }

    @Override
    public int compareTo(TextPosition other) {
        int lineOrder = Integer.compare(line, other.line);
        return lineOrder != 0 ? lineOrder : Integer.compare(utf16Offset, other.utf16Offset);
    }
}
