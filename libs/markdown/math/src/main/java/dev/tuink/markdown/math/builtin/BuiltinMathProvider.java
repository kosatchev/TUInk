package dev.tuink.markdown.math.builtin;

import dev.tuink.markdown.math.MathExpression;
import dev.tuink.markdown.math.MathNode;
import dev.tuink.markdown.math.MathProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded, dependency-free parser of the documented mathematical TeX subset. */
public final class BuiltinMathProvider implements MathProvider {
    public record Limits(int inputCodeUnits, int nesting, int nodes, int matrixRows, int matrixColumns) {
        public static final Limits DEFAULT = new Limits(32_768, 48, 8_192, 32, 32);
        public Limits {
            if (inputCodeUnits < 1 || nesting < 1 || nodes < 1 || matrixRows < 1 || matrixColumns < 1)
                throw new IllegalArgumentException("Math limits must be positive");
        }
    }

    private final Limits limits;
    public BuiltinMathProvider() { this(Limits.DEFAULT); }
    public BuiltinMathProvider(Limits limits) { this.limits = Objects.requireNonNull(limits, "limits"); }

    @Override public MathExpression parse(String source) {
        Objects.requireNonNull(source, "source");
        if (source.length() > limits.inputCodeUnits()) return failure(source, "Formula exceeds input budget");
        try {
            Reader reader = new Reader(source, limits);
            MathNode root = reader.sequence('\0', false, 0);
            reader.whitespace();
            if (reader.at != source.length()) throw reader.error("Unexpected delimiter");
            return new MathExpression(source, root, List.of());
        } catch (Malformed failure) {
            return failure(source, failure.getMessage());
        }
    }

    private static MathExpression failure(String source, String diagnostic) {
        return new MathExpression(source, new MathNode.Text(source), List.of(diagnostic));
    }

    private static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Malformed(String message) { super(message, null, false, false); }
    }

    private static final Map<String, String> SYMBOLS = Map.ofEntries(
        Map.entry("alpha", "α"), Map.entry("beta", "β"), Map.entry("gamma", "γ"),
        Map.entry("delta", "δ"), Map.entry("epsilon", "ε"), Map.entry("varepsilon", "ϵ"),
        Map.entry("zeta", "ζ"), Map.entry("eta", "η"), Map.entry("theta", "θ"),
        Map.entry("vartheta", "ϑ"), Map.entry("iota", "ι"), Map.entry("kappa", "κ"),
        Map.entry("lambda", "λ"), Map.entry("mu", "μ"), Map.entry("nu", "ν"),
        Map.entry("xi", "ξ"), Map.entry("pi", "π"), Map.entry("varpi", "ϖ"),
        Map.entry("rho", "ρ"), Map.entry("varrho", "ϱ"), Map.entry("sigma", "σ"),
        Map.entry("varsigma", "ς"), Map.entry("tau", "τ"), Map.entry("upsilon", "υ"),
        Map.entry("phi", "ϕ"), Map.entry("varphi", "φ"), Map.entry("chi", "χ"),
        Map.entry("psi", "ψ"), Map.entry("omega", "ω"), Map.entry("Gamma", "Γ"),
        Map.entry("Delta", "Δ"), Map.entry("Theta", "Θ"), Map.entry("Lambda", "Λ"),
        Map.entry("Xi", "Ξ"), Map.entry("Pi", "Π"), Map.entry("Sigma", "Σ"),
        Map.entry("Upsilon", "Υ"), Map.entry("Phi", "Φ"), Map.entry("Psi", "Ψ"),
        Map.entry("Omega", "Ω"), Map.entry("partial", "∂"), Map.entry("nabla", "∇"),
        Map.entry("infty", "∞"), Map.entry("ell", "ℓ"), Map.entry("hbar", "ℏ"),
        Map.entry("Re", "ℜ"), Map.entry("Im", "ℑ"), Map.entry("emptyset", "∅"),
        Map.entry("forall", "∀"), Map.entry("exists", "∃"), Map.entry("neg", "¬"),
        Map.entry("pm", " ± "), Map.entry("mp", " ∓ "), Map.entry("times", " × "),
        Map.entry("cdot", " · "), Map.entry("div", " ÷ "), Map.entry("le", " ≤ "),
        Map.entry("leq", " ≤ "), Map.entry("ge", " ≥ "), Map.entry("geq", " ≥ "),
        Map.entry("ne", " ≠ "), Map.entry("neq", " ≠ "), Map.entry("approx", " ≈ "),
        Map.entry("sim", " ∼ "), Map.entry("equiv", " ≡ "), Map.entry("propto", " ∝ "),
        Map.entry("to", " → "), Map.entry("rightarrow", " → "), Map.entry("leftarrow", " ← "),
        Map.entry("leftrightarrow", " ↔ "), Map.entry("Rightarrow", " ⇒ "),
        Map.entry("Leftarrow", " ⇐ "), Map.entry("Leftrightarrow", " ⇔ "),
        Map.entry("in", " ∈ "), Map.entry("notin", " ∉ "), Map.entry("subset", " ⊂ "),
        Map.entry("subseteq", " ⊆ "), Map.entry("supset", " ⊃ "), Map.entry("supseteq", " ⊇ "),
        Map.entry("cup", " ∪ "), Map.entry("cap", " ∩ "), Map.entry("land", " ∧ "),
        Map.entry("lor", " ∨ "), Map.entry("ldots", "…"), Map.entry("dots", "…"),
        Map.entry("cdots", "⋯"), Map.entry("vdots", "⋮"), Map.entry("ddots", "⋱"),
        Map.entry("langle", "⟨"), Map.entry("rangle", "⟩"),
        Map.entry("lbrace", "{"), Map.entry("rbrace", "}"), Map.entry("vert", "|"),
        Map.entry("Vert", "‖"), Map.entry("mid", " | "), Map.entry("degree", "°")
    );
    private static final Map<String, String> LARGE = Map.ofEntries(
        Map.entry("int", "∫"), Map.entry("iint", "∬"), Map.entry("iiint", "∭"),
        Map.entry("oint", "∮"), Map.entry("sum", "∑"), Map.entry("prod", "∏"),
        Map.entry("bigcup", "⋃"), Map.entry("bigcap", "⋂"), Map.entry("lim", "lim"),
        Map.entry("limsup", "lim sup"), Map.entry("liminf", "lim inf")
    );
    private static final Set<String> FUNCTIONS = Set.of("sin", "cos", "tan", "cot", "sec", "csc",
        "sinh", "cosh", "tanh", "arcsin", "arccos", "arctan", "log", "ln", "exp", "det", "max", "min", "gcd");

    private static final class Reader {
        private final String source;
        private final Limits limits;
        private int at;
        private int nodes;
        Reader(String source, Limits limits) { this.source = source; this.limits = limits; }

        private Malformed error(String message) { return new Malformed(message + " at UTF-16 offset " + at); }
        private void budget(int depth) {
            if (Thread.currentThread().isInterrupted()) throw error("Formula parsing cancelled");
            if (depth > limits.nesting()) throw error("Formula nesting exceeds budget");
        }
        private <T extends MathNode> T node(T value) {
            if (++nodes > limits.nodes()) throw error("Formula node count exceeds budget");
            return value;
        }
        private void whitespace() {
            while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++;
        }
        private boolean starts(String text) { return source.startsWith(text, at); }

        private MathNode sequence(char stop, boolean matrix, int depth) {
            budget(depth);
            ArrayList<MathNode> result = new ArrayList<>();
            while (true) {
                whitespace();
                if (at >= source.length()) break;
                char c = source.charAt(at);
                if (c == stop || (matrix && (c == '&' || starts("\\\\") || starts("\\end")))) break;
                if (c == '}' || c == ']' || c == ')') throw error("Unmatched closing delimiter");
                if (c == '^' || c == '_') throw error("Script has no base");
                boolean unary = result.isEmpty() || isOperator(result.get(result.size() - 1));
                MathNode base = atom(depth + 1, unary);
                MathNode sub = null;
                MathNode sup = null;
                while (true) {
                    whitespace();
                    if (starts("\\limits") && commandBoundary(at + 7)) { at += 7; continue; }
                    if (starts("\\nolimits") && commandBoundary(at + 9)) { at += 9; continue; }
                    if (at >= source.length()) break;
                    c = source.charAt(at);
                    if (c != '^' && c != '_') break;
                    at++;
                    MathNode value = argument(depth + 1);
                    if (c == '^') {
                        if (sup != null) throw error("Duplicate superscript");
                        sup = value;
                    } else {
                        if (sub != null) throw error("Duplicate subscript");
                        sub = value;
                    }
                }
                if (sub != null || sup != null) {
                    if (base instanceof MathNode.LargeOperator large)
                        base = node(new MathNode.LargeOperator(large.symbol(), sub, sup));
                    else base = node(new MathNode.Scripts(base, sub, sup));
                }
                result.add(base);
            }
            if (stop != '\0' && (at == source.length() || source.charAt(at) != stop))
                throw error("Unclosed group; expected " + stop);
            return result.size() == 1 ? result.get(0) : node(new MathNode.Row(result));
        }

        private boolean commandBoundary(int offset) {
            return offset >= source.length() || !Character.isLetter(source.charAt(offset));
        }
        private boolean isOperator(MathNode value) {
            return value instanceof MathNode.Text t && (t.value().endsWith(" ") || t.value().equals("−"));
        }

        private MathNode argument(int depth) {
            budget(depth);
            whitespace();
            if (at >= source.length()) throw error("Missing argument");
            return atom(depth + 1, true);
        }

        private MathNode atom(int depth, boolean unary) {
            budget(depth);
            whitespace();
            if (at >= source.length()) throw error("Missing expression");
            char c = source.charAt(at++);
            if (c == '{') {
                MathNode value = sequence('}', false, depth + 1);
                at++;
                return value;
            }
            if (c == '(' || c == '[') {
                char close = c == '(' ? ')' : ']';
                MathNode value = sequence(close, false, depth + 1);
                at++;
                return node(new MathNode.Delimited(String.valueOf(c), value, String.valueOf(close)));
            }
            if (c == '\\') return command(depth + 1);
            if (c == '}' || c == ']' || c == ')' || c == '^' || c == '_' || c == '&' || c == '$')
                throw error("Unexpected mathematical delimiter " + c);
            if (Character.isISOControl(c)) throw error("Control character in formula");
            if (c == '+') return node(new MathNode.Text(unary ? "+" : " + "));
            if (c == '-') return node(new MathNode.Text(unary ? "−" : " − "));
            if (c == '=' || c == '<' || c == '>') return node(new MathNode.Text(" " + c + " "));
            if (c == ',') return node(new MathNode.Text(", "));
            if (Character.isHighSurrogate(c)) {
                if (at >= source.length() || !Character.isLowSurrogate(source.charAt(at)))
                    throw error("Invalid UTF-16 in formula");
                return node(new MathNode.Text(source.substring(at - 1, ++at)));
            }
            if (Character.isLowSurrogate(c)) throw error("Invalid UTF-16 in formula");
            return node(new MathNode.Text(String.valueOf(c)));
        }

        private String commandName() {
            if (at >= source.length()) throw error("Trailing backslash");
            int start = at;
            if (Character.isLetter(source.charAt(at))) {
                while (at < source.length() && Character.isLetter(source.charAt(at))) at++;
            } else at++;
            return source.substring(start, at);
        }

        private MathNode command(int depth) {
            String name = commandName();
            if (name.equals("frac") || name.equals("dfrac") || name.equals("tfrac") || name.equals("cfrac"))
                return node(new MathNode.Fraction(argument(depth + 1), argument(depth + 1)));
            if (name.equals("sqrt")) {
                whitespace();
                MathNode index = null;
                if (at < source.length() && source.charAt(at) == '[') {
                    at++;
                    index = sequence(']', false, depth + 1);
                    at++;
                }
                return node(new MathNode.Radical(argument(depth + 1), index));
            }
            if (name.equals("begin")) return matrix(depth + 1);
            if (name.equals("text") || name.equals("operatorname"))
                return node(new MathNode.Text(rawGroup().replace("\\_", "_").replace("\\$", "$")));
            if (name.equals("mathrm") || name.equals("mathbf") || name.equals("mathit") || name.equals("mathsf"))
                return argument(depth + 1);
            if (name.equals("left")) return leftRight(depth + 1);
            if (name.equals("right")) throw error("Unmatched \\right");
            if (name.equals(",") || name.equals(";") || name.equals(":") || name.equals(" "))
                return node(new MathNode.Text(" "));
            if (name.equals("quad")) return node(new MathNode.Text("  "));
            if (name.equals("qquad")) return node(new MathNode.Text("    "));
            if (name.equals("!")) return node(new MathNode.Text(""));
            if (Set.of("{", "}", "$", "%", "#", "&", "_", "|").contains(name))
                return node(new MathNode.Text(name));
            if (LARGE.containsKey(name)) return node(new MathNode.LargeOperator(LARGE.get(name), null, null));
            if (FUNCTIONS.contains(name)) return node(new MathNode.Text(name + " "));
            if (SYMBOLS.containsKey(name)) return node(new MathNode.Text(SYMBOLS.get(name)));
            throw error("Unsupported TeX command \\" + name);
        }

        private String rawGroup() {
            whitespace();
            if (at >= source.length() || source.charAt(at++) != '{') throw error("Expected group");
            int start = at;
            int depth = 1;
            while (at < source.length()) {
                char c = source.charAt(at++);
                if (c == '\\' && at < source.length()) { at++; continue; }
                if (c == '{' && ++depth > limits.nesting()) throw error("Group nesting exceeds budget");
                if (c == '}' && --depth == 0) return source.substring(start, at - 1).replace('\n', ' ').replace('\r', ' ');
            }
            throw error("Unclosed group");
        }

        private MathNode leftRight(int depth) {
            String left = delimiter();
            int start = at;
            int nested = 0;
            int end = -1;
            while (at < source.length()) {
                if (starts("\\left") && commandBoundary(at + 5)) { nested++; at += 5; }
                else if (starts("\\right") && commandBoundary(at + 6)) {
                    if (nested-- == 0) { end = at; break; }
                    at += 6;
                } else at++;
            }
            if (end < 0) throw error("Missing \\right");
            String body = source.substring(start, end);
            at += 6;
            String right = delimiter();
            Reader child = new Reader(body, limits);
            child.nodes = nodes;
            MathNode content = child.sequence('\0', false, depth + 1);
            nodes = child.nodes;
            return node(new MathNode.Delimited(left, content, right));
        }

        private String delimiter() {
            whitespace();
            if (at >= source.length()) throw error("Missing delimiter");
            char c = source.charAt(at++);
            if (c == '.') return "";
            if (c != '\\') return String.valueOf(c);
            String name = commandName();
            if (name.equals("{") || name.equals("}")) return name;
            if (SYMBOLS.containsKey(name)) return SYMBOLS.get(name).strip();
            throw error("Unsupported delimiter");
        }

        private MathNode matrix(int depth) {
            budget(depth);
            String environment = rawGroup();
            String left;
            String right;
            switch (environment) {
                case "matrix" -> { left = ""; right = ""; }
                case "bmatrix" -> { left = "["; right = "]"; }
                case "pmatrix" -> { left = "("; right = ")"; }
                case "Bmatrix" -> { left = "{"; right = "}"; }
                case "vmatrix" -> { left = "|"; right = "|"; }
                case "Vmatrix" -> { left = "‖"; right = "‖"; }
                default -> throw error("Unsupported matrix environment " + environment);
            }
            List<List<MathNode>> rows = new ArrayList<>();
            List<MathNode> cells = new ArrayList<>();
            while (true) {
                whitespace();
                if (starts("\\end")) {
                    if (!cells.isEmpty()) throw error("Missing matrix cell");
                    at += 4;
                    if (!rawGroup().equals(environment)) throw error("Mismatched matrix environment");
                    break;
                }
                cells.add(sequence('\0', true, depth + 1));
                if (cells.size() > limits.matrixColumns()) throw error("Matrix column budget exceeded");
                whitespace();
                if (at < source.length() && source.charAt(at) == '&') { at++; continue; }
                rows.add(List.copyOf(cells));
                cells.clear();
                if (rows.size() > limits.matrixRows()) throw error("Matrix row budget exceeded");
                if (starts("\\\\")) { at += 2; continue; }
                if (!starts("\\end")) throw error("Missing matrix row separator or \\end");
            }
            if (rows.isEmpty()) throw error("Empty matrix");
            int columns = rows.get(0).size();
            if (rows.stream().anyMatch(row -> row.size() != columns)) throw error("Unequal matrix row widths");
            return node(new MathNode.Matrix(rows, left, right));
        }
    }
}
