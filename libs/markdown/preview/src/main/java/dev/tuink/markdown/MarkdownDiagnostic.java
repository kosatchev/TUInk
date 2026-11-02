package dev.tuink.markdown;

import dev.tuink.editor.document.TextRange;
import java.util.Objects;

/** A local, readable degradation while the rest of the document remains available. */
public record MarkdownDiagnostic(String message, TextRange source) {
    public MarkdownDiagnostic { Objects.requireNonNull(message); Objects.requireNonNull(source); }
}
