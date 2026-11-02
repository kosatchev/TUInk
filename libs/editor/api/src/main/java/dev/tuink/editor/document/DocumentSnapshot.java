package dev.tuink.editor.document;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable document content at one revision, sharing unchanged text blocks. */
public final class DocumentSnapshot implements DocumentView {
    private final DocumentId documentId;
    private final long revision;
    private final List<ImmutableText> textLines;
    private final List<String> strings;

    /** Copies and validates imported strings. */
    public DocumentSnapshot(DocumentId documentId, long revision, List<String> lines) {
        this(importLines(lines), documentId, revision);
    }

    /**
     * Shares immutable values without reading or materializing complete lines.
     * Unknown CharSequence implementations must first be copied by ImmutableText.of.
     */
    public static DocumentSnapshot fromImmutableLines(DocumentId documentId, long revision,
                                                       List<ImmutableText> lines) {
        return new DocumentSnapshot(List.copyOf(lines), documentId, revision);
    }

    private DocumentSnapshot(List<ImmutableText> lines, DocumentId documentId, long revision) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        if (revision < 0) throw new IllegalArgumentException("Document revision must be non-negative");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A document snapshot must contain at least one line");
        }
        for (ImmutableText line : lines) {
            if (!line.isWellFormedUtf16()) {
                throw new IllegalArgumentException("Snapshot line contains an unpaired UTF-16 surrogate");
            }
            if (line.containsLineSeparator()) {
                throw new IllegalArgumentException("Snapshot lines must not contain line separators");
            }
        }
        this.revision = revision;
        this.textLines = lines;
        this.strings = Collections.unmodifiableList(new AbstractList<>() {
            @Override public String get(int index) { return line(index); }
            @Override public int size() { return lineCount(); }
        });
    }

    private static List<ImmutableText> importLines(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        List<ImmutableText> imported = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null) throw new IllegalArgumentException("Snapshot line must not be null");
            imported.add(ImmutableText.of(line));
        }
        return List.copyOf(imported);
    }

    @Override public DocumentId documentId() { return documentId; }
    @Override public long revision() { return revision; }
    @Override public int lineCount() { return textLines.size(); }
    @Override public String line(int index) { return textLines.get(index).toString(); }
    @Override public ImmutableText lineText(int index) { return textLines.get(index); }

    /** Compatibility string view; each accessed line is materialized on demand. */
    public List<String> lines() { return strings; }

    /** Immutable logical lines, sharing the snapshot's persistent text blocks without materialization. */
    public List<ImmutableText> immutableLines() { return textLines; }

    public boolean contains(TextPosition position) {
        return position != null && position.line() < lineCount()
            && position.utf16Offset() <= lineText(position.line()).length();
    }

    public boolean contains(TextRange range) {
        return range != null && contains(range.start()) && contains(range.end());
    }

    /** True when the offset does not split a UTF-16 surrogate pair. */
    public boolean isCodePointBoundary(TextPosition position) {
        if (!contains(position)) return false;
        ImmutableText line = lineText(position.line());
        int offset = position.utf16Offset();
        return offset == 0 || offset == line.length()
            || !Character.isHighSurrogate(line.charAt(offset - 1))
            || !Character.isLowSurrogate(line.charAt(offset));
    }

    @Override public boolean equals(Object other) {
        return other instanceof DocumentSnapshot snapshot && revision == snapshot.revision
            && documentId.equals(snapshot.documentId) && textLines.equals(snapshot.textLines);
    }

    @Override public int hashCode() { return Objects.hash(documentId, revision, textLines); }
    @Override public String toString() {
        return "DocumentSnapshot[documentId=" + documentId + ", revision=" + revision + ", lines=" + strings + "]";
    }
}
