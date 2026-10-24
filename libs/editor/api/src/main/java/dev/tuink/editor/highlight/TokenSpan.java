package dev.tuink.editor.highlight;

import java.util.Objects;

/** Semantic classification of a half-open UTF-16 range within one logical line. */
public record TokenSpan(int start, int end, SemanticStyleKey styleKey) {
    public TokenSpan {
        if (start < 0 || end < start) throw new IllegalArgumentException("Invalid token range");
        Objects.requireNonNull(styleKey, "styleKey");
    }
}
