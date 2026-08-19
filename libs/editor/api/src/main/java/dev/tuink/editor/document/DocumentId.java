package dev.tuink.editor.document;

/** Stable identity of an open document, independent of its current file path. */
public record DocumentId(String value) {
    public DocumentId {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException("Document ID must be non-blank and at most 512 characters");
        }
    }
}
