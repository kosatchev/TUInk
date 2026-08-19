package dev.tuink.editor.document;

import java.util.List;
import java.util.Objects;

/** Immutable document content at one revision. */
public record DocumentSnapshot(DocumentId documentId, long revision, List<String> lines)
    implements DocumentView {
    public DocumentSnapshot {
        Objects.requireNonNull(documentId, "documentId");
        if (revision < 0) {
            throw new IllegalArgumentException("Document revision must be non-negative");
        }
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A document snapshot must contain at least one line");
        }
        for (String line : lines) {
            Utf16Text.requireWellFormed(line, "Snapshot line");
            if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Snapshot lines must not contain line separators");
            }
        }
        lines = List.copyOf(lines);
    }

    public int lineCount() {
        return lines.size();
    }

    public String line(int index) {
        return lines.get(index);
    }

    public boolean contains(TextPosition position) {
        return position != null
            && position.line() < lines.size()
            && position.utf16Offset() <= lines.get(position.line()).length();
    }

    public boolean contains(TextRange range) {
        return range != null && contains(range.start()) && contains(range.end());
    }

    /** True when the offset does not split a UTF-16 surrogate pair. */
    public boolean isCodePointBoundary(TextPosition position) {
        if (!contains(position)) {
            return false;
        }
        String line = lines.get(position.line());
        int offset = position.utf16Offset();
        return offset == 0
            || offset == line.length()
            || !Character.isHighSurrogate(line.charAt(offset - 1))
            || !Character.isLowSurrogate(line.charAt(offset));
    }
}
