package dev.tuink.markdown.math;

/** Converts a bounded TeX body into notation; never evaluates the expression. */
@FunctionalInterface
public interface MathProvider {
    MathExpression parse(String source);
}
