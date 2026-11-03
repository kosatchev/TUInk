package dev.tuink.render.markdown;

import dev.tuink.markdown.math.MathExpression;
import dev.tuink.markdown.math.MathNode;
import dev.tuiframe.text.TerminalText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Two-dimensional notation in terminal cells, independent of any terminal image protocol. */
public final class MathLayout {
    private MathLayout() {}

    public record Limits(int width, int height, long allocatedCells, int nesting) {
        public static final Limits DEFAULT = new Limits(4096, 512, 2_000_000, 64);
        public Limits {
            if (width < 1 || height < 1 || allocatedCells < 1 || nesting < 1)
                throw new IllegalArgumentException("Math layout limits must be positive");
        }
    }

    /** Every line occupies width terminal cells; baseline is a zero-based row. */
    public record Box(List<String> lines, int width, int height, int baseline) {
        public Box {
            lines = List.copyOf(lines);
            if (width < 0 || height < 1 || lines.size() != height || baseline < 0 || baseline >= height)
                throw new IllegalArgumentException("Invalid mathematical box");
        }
        public int ascent() { return baseline; }
        public int descent() { return height - baseline - 1; }
    }

    public static List<String> render(MathExpression expression) { return layout(expression).lines(); }
    public static Box layout(MathExpression expression) { return layout(expression, Limits.DEFAULT); }
    public static Box layout(MathExpression expression, Limits limits) {
        Objects.requireNonNull(expression, "expression");
        Guard guard = new Guard(Objects.requireNonNull(limits, "limits"));
        if (!expression.supported()) {
            if (expression.source().length() > limits.allocatedCells())
                throw new IllegalArgumentException("Formula fallback exceeds text budget");
            List<String> lines = List.of(expression.source().split("\\R", -1));
            return guard.literal(lines);
        }
        return guard.node(expression.root(), 0);
    }

    private static final class Guard {
        private final Limits limits;
        private long allocated;
        Guard(Limits limits) { this.limits = limits; }

        private void reserve(int width, int height) {
            if (width < 0 || height < 1 || width > limits.width() || height > limits.height())
                throw new IllegalArgumentException("Formula exceeds terminal layout dimensions");
            long next = allocated + (long) width * height;
            if (next > limits.allocatedCells()) throw new IllegalArgumentException("Formula exceeds layout cell budget");
            if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("Formula layout cancelled");
            allocated = next;
        }

        private Box box(List<String> lines, int width, int baseline) {
            reserve(width, lines.size());
            return new Box(lines, width, lines.size(), baseline);
        }

        private Box literal(List<String> input) {
            ArrayList<String> safe = new ArrayList<>();
            int width = 0;
            for (String line : input) {
                String value = sanitize(line);
                safe.add(value);
                width = Math.max(width, cells(value));
            }
            reserve(width, safe.size());
            ArrayList<String> padded = new ArrayList<>(safe.size());
            for (String line : safe) padded.add(pad(line, width));
            return new Box(padded, width, padded.size(), 0);
        }

        private Box text(String value) {
            if (value.length() > limits.allocatedCells())
                throw new IllegalArgumentException("Formula text exceeds layout budget");
            String safe = sanitize(value);
            return box(List.of(safe), cells(safe), 0);
        }

        private Box node(MathNode value, int depth) {
            if (depth > limits.nesting()) throw new IllegalArgumentException("Formula layout nesting exceeds budget");
            if (value instanceof MathNode.Text t) return text(t.value());
            if (value instanceof MathNode.Row row) {
                ArrayList<Box> children = new ArrayList<>(row.children().size());
                MathNode previous = null;
                for (MathNode child : row.children()) {
                    if (previous != null && (previous instanceof MathNode.LargeOperator
                        || child instanceof MathNode.Fraction || child instanceof MathNode.Matrix)
                        && !(previous instanceof MathNode.Text text && text.value().endsWith(" ")))
                        children.add(text(" "));
                    children.add(node(child, depth + 1));
                    previous = child;
                }
                return horizontal(children);
            }
            if (value instanceof MathNode.Fraction fraction)
                return fraction(node(fraction.numerator(), depth + 1), node(fraction.denominator(), depth + 1));
            if (value instanceof MathNode.Radical radical)
                return radical(node(radical.radicand(), depth + 1),
                    radical.index() == null ? null : node(radical.index(), depth + 1));
            if (value instanceof MathNode.Scripts scripts)
                return scripts(node(scripts.base(), depth + 1),
                    scripts.subscript() == null ? null : node(scripts.subscript(), depth + 1),
                    scripts.superscript() == null ? null : node(scripts.superscript(), depth + 1));
            if (value instanceof MathNode.LargeOperator operator)
                return operator(operator.symbol(), operator.lower() == null ? null : node(operator.lower(), depth + 1),
                    operator.upper() == null ? null : node(operator.upper(), depth + 1));
            if (value instanceof MathNode.Delimited delimited)
                return delimit(node(delimited.content(), depth + 1), delimited.left(), delimited.right());
            if (value instanceof MathNode.Matrix matrix) return matrix(matrix, depth + 1);
            throw new IllegalArgumentException("Unknown math notation");
        }

        private Box horizontal(List<Box> boxes) {
            if (boxes.isEmpty()) return text("");
            int baseline = 0;
            int descent = 0;
            long width = 0;
            for (Box child : boxes) {
                baseline = Math.max(baseline, child.baseline());
                descent = Math.max(descent, child.descent());
                width += child.width();
            }
            int height = baseline + descent + 1;
            if (width > limits.width()) throw new IllegalArgumentException("Formula row exceeds width budget");
            reserve((int) width, height);
            ArrayList<String> result = new ArrayList<>(height);
            for (int y = 0; y < height; y++) {
                StringBuilder line = new StringBuilder((int) width);
                for (Box child : boxes) {
                    int row = y - baseline + child.baseline();
                    line.append(row < 0 || row >= child.height() ? " ".repeat(child.width()) : child.lines().get(row));
                }
                result.add(line.toString());
            }
            return new Box(result, (int) width, height, baseline);
        }

        private Box fraction(Box numerator, Box denominator) {
            int width = Math.max(numerator.width(), denominator.width()) + 2;
            int height = numerator.height() + denominator.height() + 1;
            reserve(width, height);
            ArrayList<String> lines = new ArrayList<>(height);
            for (String line : numerator.lines()) lines.add(center(line, width));
            lines.add("─".repeat(width));
            for (String line : denominator.lines()) lines.add(center(line, width));
            return new Box(lines, width, height, numerator.height());
        }

        private Box scripts(Box base, Box subscript, Box superscript) {
            int above = superscript == null ? 0 : Math.max(0, superscript.height() - base.baseline());
            int baseline = base.baseline() + above;
            int scriptWidth = Math.max(subscript == null ? 0 : subscript.width(), superscript == null ? 0 : superscript.width());
            int width = base.width() + scriptWidth;
            int height = Math.max(base.height() + above, subscript == null ? 0 : baseline + 1 + subscript.height());
            reserve(width, height);
            ArrayList<String> lines = new ArrayList<>(height);
            for (int y = 0; y < height; y++) {
                int baseRow = y - above;
                String left = baseRow >= 0 && baseRow < base.height() ? base.lines().get(baseRow) : " ".repeat(base.width());
                String right = "";
                if (superscript != null) {
                    int row = y - (baseline - superscript.height());
                    if (row >= 0 && row < superscript.height()) right = superscript.lines().get(row);
                }
                if (subscript != null) {
                    int row = y - baseline - 1;
                    if (row >= 0 && row < subscript.height()) right = subscript.lines().get(row);
                }
                lines.add(left + pad(right, scriptWidth));
            }
            return new Box(lines, width, height, baseline);
        }

        private Box radical(Box content, Box index) {
            int left = Math.max(content.height() + 1, index == null ? 0 : index.width() + 1);
            int extra = index == null ? 0 : Math.max(0, index.height() - 1);
            int width = left + content.width();
            int height = extra + content.height() + 1;
            reserve(width, height);
            ArrayList<String> lines = new ArrayList<>(height);
            for (int i = 0; i < extra; i++) lines.add(" ".repeat(width));
            lines.add(" ".repeat(left) + "─".repeat(content.width()));
            for (int y = 0; y < content.height(); y++) {
                String prefix = y == content.height() - 1 ? "╲╱" + " ".repeat(left - 2)
                    : " ".repeat(left - y - 1) + "╱" + " ".repeat(y);
                lines.add(prefix + content.lines().get(y));
            }
            if (index != null) for (int y = 0; y < index.height(); y++)
                lines.set(y, index.lines().get(y) + lines.get(y).substring(index.width()));
            return new Box(lines, width, height, extra + content.baseline() + 1);
        }

        private Box operator(String symbol, Box lower, Box upper) {
            Box sign;
            if (symbol.equals("∫")) sign = box(List.of("⌠", "⎮", "⌡"), 1, 1);
            else if (symbol.equals("∑")) sign = box(List.of("⎲", "⎳"), 1, 1);
            else if (symbol.equals("∏")) sign = box(List.of("┌─┐", "│ │"), 3, 1);
            else sign = text(symbol);
            int top = upper == null ? 0 : upper.height();
            int width = Math.max(sign.width(), Math.max(upper == null ? 0 : upper.width(), lower == null ? 0 : lower.width()));
            int height = top + sign.height() + (lower == null ? 0 : lower.height());
            reserve(width, height);
            ArrayList<String> lines = new ArrayList<>(height);
            if (upper != null) for (String line : upper.lines()) lines.add(center(line, width));
            for (String line : sign.lines()) lines.add(center(line, width));
            if (lower != null) for (String line : lower.lines()) lines.add(center(line, width));
            return new Box(lines, width, height, top + sign.baseline());
        }

        private Box delimit(Box content, String left, String right) {
            int width = content.width() + cells(left) + cells(right);
            reserve(width, content.height());
            ArrayList<String> lines = new ArrayList<>(content.height());
            for (int y = 0; y < content.height(); y++)
                lines.add(delimiter(left, y, content.height()) + content.lines().get(y) + delimiter(right, y, content.height()));
            return new Box(lines, width, content.height(), content.baseline());
        }

        private Box matrix(MathNode.Matrix matrix, int depth) {
            int columns = matrix.rows().get(0).size();
            int[] widths = new int[columns];
            List<List<Box>> rows = new ArrayList<>();
            for (List<MathNode> row : matrix.rows()) {
                List<Box> parsed = new ArrayList<>();
                for (int column = 0; column < columns; column++) {
                    Box cell = node(row.get(column), depth + 1);
                    parsed.add(cell);
                    widths[column] = Math.max(widths[column], cell.width());
                }
                rows.add(parsed);
            }
            List<String> lines = new ArrayList<>();
            int width = 2 + Math.max(0, columns - 1) * 2;
            for (int value : widths) width += value;
            int height = 0;
            for (List<Box> row : rows) {
                int ascent = row.stream().mapToInt(Box::baseline).max().orElse(0);
                int descent = row.stream().mapToInt(Box::descent).max().orElse(0);
                height += ascent + descent + 1;
            }
            reserve(width, height);
            for (List<Box> row : rows) {
                int ascent = row.stream().mapToInt(Box::baseline).max().orElse(0);
                int descent = row.stream().mapToInt(Box::descent).max().orElse(0);
                for (int y = 0; y <= ascent + descent; y++) {
                    StringBuilder line = new StringBuilder(" ");
                    for (int column = 0; column < columns; column++) {
                        if (column > 0) line.append("  ");
                        Box cell = row.get(column);
                        int cellRow = y - ascent + cell.baseline();
                        line.append(center(cellRow >= 0 && cellRow < cell.height() ? cell.lines().get(cellRow) : "", widths[column]));
                    }
                    lines.add(line.append(' ').toString());
                }
            }
            return delimit(new Box(lines, width, height, (height - 1) / 2), matrix.left(), matrix.right());
        }
    }

    private static String delimiter(String token, int row, int height) {
        if (height == 1 || token.isEmpty()) return token;
        int part = row == 0 ? 0 : row == height - 1 ? 2 : 1;
        return switch (token) {
            case "[" -> new String[]{"⎡", "⎢", "⎣"}[part];
            case "]" -> new String[]{"⎤", "⎥", "⎦"}[part];
            case "(" -> new String[]{"⎛", "⎜", "⎝"}[part];
            case ")" -> new String[]{"⎞", "⎟", "⎠"}[part];
            case "{" -> row == height / 2 ? "⎨" : part == 0 ? "⎧" : part == 2 ? "⎩" : "⎪";
            case "}" -> row == height / 2 ? "⎬" : part == 0 ? "⎫" : part == 2 ? "⎭" : "⎪";
            case "|" -> "│";
            case "‖" -> "║";
            default -> row == height / 2 ? token : " ".repeat(cells(token));
        };
    }

    private static int cells(String value) { return TerminalText.visualWidth(value, 4); }
    private static String pad(String value, int width) { return value + " ".repeat(Math.max(0, width - cells(value))); }
    private static String center(String value, int width) {
        int space = Math.max(0, width - cells(value));
        return " ".repeat(space / 2) + value + " ".repeat(space - space / 2);
    }
    private static String sanitize(String value) {
        StringBuilder result = new StringBuilder(value.length());
        TerminalText.GraphemeCursor cursor = TerminalText.graphemeCursor(value);
        while (cursor.advance()) {
            if (cursor.isTab() || value.charAt(cursor.start()) == '\n' || value.charAt(cursor.start()) == '\r') result.append(' ');
            else result.append(TerminalText.sanitizeGrapheme(value, cursor.start(), cursor.end()));
        }
        return result.toString();
    }
}
