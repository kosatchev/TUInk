package dev.tuink.editor.document;

import java.util.Objects;

/**
 * One replacement of a half-open range with well-formed UTF-16 Java text.
 * CRLF and CR are normalized to the editor contract's internal LF form.
 */
public record TextEdit(TextRange range, String replacement) {
    public TextEdit {
        Objects.requireNonNull(range, "range");
        replacement = Utf16Text.requireWellFormed(replacement, "Replacement text")
            .replace("\r\n", "\n")
            .replace('\r', '\n');
    }
}
