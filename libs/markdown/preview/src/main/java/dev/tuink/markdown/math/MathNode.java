package dev.tuink.markdown.math;

import java.util.List;
import java.util.Objects;

/** Presentation-independent notation; scripts and grouping retain their source meaning. */
public sealed interface MathNode {
    record Text(String value) implements MathNode {
        public Text { Objects.requireNonNull(value, "value"); }
    }
    record Row(List<MathNode> children) implements MathNode {
        public Row { children = List.copyOf(children); }
    }
    record Fraction(MathNode numerator, MathNode denominator) implements MathNode {
        public Fraction {
            Objects.requireNonNull(numerator, "numerator");
            Objects.requireNonNull(denominator, "denominator");
        }
    }
    record Radical(MathNode radicand, MathNode index) implements MathNode {
        public Radical { Objects.requireNonNull(radicand, "radicand"); }
    }
    record Scripts(MathNode base, MathNode subscript, MathNode superscript) implements MathNode {
        public Scripts { Objects.requireNonNull(base, "base"); }
    }
    record LargeOperator(String symbol, MathNode lower, MathNode upper) implements MathNode {
        public LargeOperator { Objects.requireNonNull(symbol, "symbol"); }
    }
    record Delimited(String left, MathNode content, String right) implements MathNode {
        public Delimited {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(right, "right");
        }
    }
    record Matrix(List<List<MathNode>> rows, String left, String right) implements MathNode {
        public Matrix {
            rows = rows.stream().map(List::copyOf).toList();
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            if (rows.isEmpty() || rows.get(0).isEmpty()) throw new IllegalArgumentException("Empty matrix");
            int columns = rows.get(0).size();
            if (rows.stream().anyMatch(row -> row.size() != columns))
                throw new IllegalArgumentException("Matrix rows have different widths");
        }
    }
}
