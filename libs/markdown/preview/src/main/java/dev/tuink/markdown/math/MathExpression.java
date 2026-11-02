package dev.tuink.markdown.math;

import java.util.List;
import java.util.Objects;

/** Immutable parsed notation plus the exact TeX needed by Copy and source navigation. */
public record MathExpression(String source, MathNode root, List<String> diagnostics) {
    public MathExpression {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(root, "root");
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean supported() { return diagnostics.isEmpty(); }
}
