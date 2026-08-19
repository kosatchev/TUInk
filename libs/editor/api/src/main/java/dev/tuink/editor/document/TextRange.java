package dev.tuink.editor.document;

import java.util.Objects;

/** Half-open document range in UTF-16 line coordinates: {@code [start, end)}. */
public record TextRange(TextPosition start, TextPosition end) {
    public TextRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.compareTo(end) > 0) {
            throw new IllegalArgumentException("Text range start must not follow its end");
        }
    }

    public boolean isEmpty() {
        return start.equals(end);
    }
}
