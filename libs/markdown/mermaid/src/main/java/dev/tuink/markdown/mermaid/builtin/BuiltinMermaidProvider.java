package dev.tuink.markdown.mermaid.builtin;

import dev.tuink.markdown.mermaid.Diagram;
import dev.tuink.markdown.mermaid.MermaidProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A bounded Mermaid subset: flowchart/graph with box, rounded and decision nodes,
 * directed solid/dashed edges, pipe or infix labels and chains; sequenceDiagram
 * with participant/actor declarations, aliases and solid/dashed directed messages.
 * Unsupported syntax is an explicit local result rather than a partially drawn graph.
 */
public final class BuiltinMermaidProvider implements MermaidProvider {
    @Override
    public Diagram parse(String source, Limits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        if (source.length() > limits.maxSourceLength()) {
            return new Diagram.Unsupported(source, "Mermaid source exceeds the input budget");
        }
        try {
            List<Statement> statements = statements(source);
            if (statements.isEmpty()) throw new Syntax("Empty Mermaid block");
            Statement first = statements.get(0);
            String header = first.text().trim();
            if (header.equals("sequenceDiagram")) return sequence(source, statements, limits);
            if (header.startsWith("flowchart ") || header.startsWith("graph ")) {
                String[] words = header.split("\\s+");
                if (words.length != 2) throw new Syntax("Expected flowchart direction on line " + first.line());
                Diagram.Direction direction = switch (words[1]) {
                    case "LR" -> Diagram.Direction.LR;
                    case "RL" -> Diagram.Direction.RL;
                    case "TD", "TB" -> Diagram.Direction.TD;
                    case "BT" -> Diagram.Direction.BT;
                    default -> throw new Syntax("Unsupported flowchart direction " + words[1]);
                };
                return flowchart(source, direction, statements, limits);
            }
            throw new Syntax("Supported Mermaid types: flowchart and sequenceDiagram");
        } catch (Syntax invalid) {
            return new Diagram.Unsupported(source, invalid.getMessage());
        }
    }

    private static Diagram flowchart(String source, Diagram.Direction direction,
                                     List<Statement> statements, Limits limits) {
        Map<String, Diagram.Node> nodes = new LinkedHashMap<>();
        List<Diagram.Edge> edges = new ArrayList<>();
        for (int index = 1; index < statements.size(); index++) {
            checkCancellation();
            Statement statement = statements.get(index);
            Cursor cursor = new Cursor(statement.text(), statement.line(), limits);
            String previous = node(cursor, nodes);
            boolean connected = false;
            while (!cursor.end()) {
                boolean dashed;
                String label = "";
                if (cursor.take("-.->")) {
                    dashed = true;
                } else if (cursor.take("-->") || cursor.take("==>")) {
                    dashed = false;
                } else if (cursor.take("--")) {
                    dashed = false;
                    label = cursor.untilArrow();
                } else {
                    throw cursor.syntax("Unsupported flowchart statement");
                }
                if (cursor.take("|")) label = cursor.pipeLabel();
                String next = node(cursor, nodes);
                if (edges.size() >= limits.maxEdges()) throw cursor.syntax("Mermaid edge budget exceeded");
                edges.add(new Diagram.Edge(previous, next, cleanLabel(label, limits), dashed));
                previous = next;
                connected = true;
            }
            if (!connected && statement.text().contains("&")) {
                throw cursor.syntax("Combined node expressions are not supported");
            }
        }
        if (nodes.isEmpty()) throw new Syntax("Flowchart contains no nodes");
        return new Diagram.Flowchart(source, direction, new ArrayList<>(nodes.values()), edges);
    }

    private static String node(Cursor cursor, Map<String, Diagram.Node> nodes) {
        String id = cursor.identifier();
        Diagram.Shape shape = Diagram.Shape.BOX;
        String label = null;
        if (cursor.take("[")) {
            shape = Diagram.Shape.BOX;
            label = cursor.nodeLabel(']');
        } else if (cursor.take("{")) {
            shape = Diagram.Shape.DECISION;
            label = cursor.nodeLabel('}');
        } else if (cursor.take("(")) {
            shape = Diagram.Shape.ROUNDED;
            label = cursor.nodeLabel(')');
        }
        if (!nodes.containsKey(id) && nodes.size() >= cursor.limits.maxNodes()) {
            throw cursor.syntax("Mermaid node budget exceeded");
        }
        if (label != null) {
            nodes.put(id, new Diagram.Node(id, cleanLabel(label, cursor.limits), shape));
        } else {
            nodes.putIfAbsent(id, new Diagram.Node(id, id, shape));
        }
        return id;
    }

    private static Diagram sequence(String source, List<Statement> statements, Limits limits) {
        Map<String, Diagram.Participant> participants = new LinkedHashMap<>();
        List<Diagram.Message> messages = new ArrayList<>();
        for (int index = 1; index < statements.size(); index++) {
            checkCancellation();
            Statement statement = statements.get(index);
            Cursor cursor = new Cursor(statement.text(), statement.line(), limits);
            boolean actor = cursor.takeWord("actor");
            if (actor || cursor.takeWord("participant")) {
                String id = cursor.identifier();
                String label = id;
                if (cursor.takeWord("as")) label = cursor.rest();
                if (!cursor.end()) throw cursor.syntax("Unsupported participant declaration");
                participant(participants, id, cleanLabel(label, limits), actor, limits);
                continue;
            }
            String from = cursor.identifier();
            boolean dashed;
            if (cursor.take("-->>") || cursor.take("-->")) {
                dashed = true;
            } else if (cursor.take("->>") || cursor.take("->")) {
                dashed = false;
            } else {
                throw cursor.syntax("Unsupported sequence statement");
            }
            String to = cursor.identifier();
            if (!cursor.take(":")) throw cursor.syntax("Expected a message label after ':'");
            String label = cleanLabel(cursor.rest(), limits);
            participant(participants, from, from, false, limits);
            participant(participants, to, to, false, limits);
            if (messages.size() >= limits.maxEdges()) throw cursor.syntax("Mermaid message budget exceeded");
            messages.add(new Diagram.Message(from, to, label, dashed));
        }
        if (participants.isEmpty()) throw new Syntax("Sequence diagram contains no participants");
        return new Diagram.Sequence(source, new ArrayList<>(participants.values()), messages);
    }

    private static void participant(Map<String, Diagram.Participant> participants, String id,
                                    String label, boolean actor, Limits limits) {
        if (!participants.containsKey(id)) {
            if (participants.size() >= limits.maxNodes()) throw new Syntax("Mermaid participant budget exceeded");
            participants.put(id, new Diagram.Participant(id, label, actor));
        } else if (!label.equals(id) || actor) {
            participants.put(id, new Diagram.Participant(id, label, actor));
        }
    }

    private static String cleanLabel(String label, Limits limits) {
        String result = label.trim();
        if (result.length() >= 2 && result.charAt(0) == '"' && result.charAt(result.length() - 1) == '"') {
            result = result.substring(1, result.length() - 1);
        }
        result = result.replace("\\\"", "\"").replace("\\\\", "\\")
            .replace("<br/>", "\n").replace("<br />", "\n").replace("<br>", "\n");
        if (result.length() > limits.maxLabelLength()) throw new Syntax("Mermaid label budget exceeded");
        if (result.indexOf('<') >= 0 || result.indexOf('>') >= 0) {
            throw new Syntax("HTML labels are not supported in this Mermaid renderer");
        }
        if (result.codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\t')) {
            throw new Syntax("Control characters are not allowed in Mermaid labels");
        }
        return result;
    }

    private static List<Statement> statements(String source) {
        List<Statement> result = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        boolean sequence = false;
        int brackets = 0;
        int line = 1;
        int startLine = 1;
        for (int index = 0; index < source.length(); index++) {
            if ((index & 1023) == 0) checkCancellation();
            char ch = source.charAt(index);
            if (!quoted && ch == '%' && index + 1 < source.length() && source.charAt(index + 1) == '%'
                    && (!sequence || statement.toString().isBlank())) {
                while (index < source.length() && source.charAt(index) != '\n') index++;
                ch = '\n';
            }
            if (!quoted && (ch == '\n' || ch == '\r' || (ch == ';' && brackets == 0))) {
                String value = statement.toString().trim();
                if (!value.isEmpty()) {
                    result.add(new Statement(value, startLine));
                    if (result.size() == 1) sequence = value.equals("sequenceDiagram");
                }
                statement.setLength(0);
                if (ch == '\n') line++;
                startLine = line;
                escaped = false;
                continue;
            }
            statement.append(ch);
            if (sequence) continue;
            if (escaped) escaped = false;
            else if (ch == '\\') escaped = true;
            else if (ch == '"') quoted = !quoted;
            else if (!quoted && (ch == '[' || ch == '{' || ch == '(')) brackets++;
            else if (!quoted && (ch == ']' || ch == '}' || ch == ')')) brackets--;
            if (brackets < 0 || brackets > 32) throw new Syntax("Invalid Mermaid bracket nesting on line " + line);
        }
        if (quoted || brackets != 0) throw new Syntax("Unclosed Mermaid label or node shape");
        String value = statement.toString().trim();
        if (!value.isEmpty()) result.add(new Statement(value, startLine));
        return result;
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) throw new Syntax("Mermaid parsing cancelled");
    }

    private record Statement(String text, int line) {}
    private static final class Syntax extends RuntimeException {
        Syntax(String message) { super(message); }
    }

    private static final class Cursor {
        private final String text;
        private final int line;
        private final Limits limits;
        private int offset;

        Cursor(String text, int line, Limits limits) {
            this.text = text;
            this.line = line;
            this.limits = limits;
        }

        void whitespace() {
            while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++;
        }

        boolean end() { whitespace(); return offset == text.length(); }

        boolean take(String token) {
            whitespace();
            if (!text.startsWith(token, offset)) return false;
            offset += token.length();
            return true;
        }

        boolean takeWord(String word) {
            whitespace();
            if (!text.startsWith(word, offset)) return false;
            int end = offset + word.length();
            if (end < text.length() && !Character.isWhitespace(text.charAt(end))) return false;
            offset = end;
            return true;
        }

        String identifier() {
            whitespace();
            int start = offset;
            while (offset < text.length()) {
                int cp = text.codePointAt(offset);
                if (!Character.isLetterOrDigit(cp) && cp != '_') break;
                offset += Character.charCount(cp);
            }
            if (offset == start) throw syntax("Expected a Mermaid identifier");
            String result = text.substring(start, offset);
            if (result.length() > limits.maxLabelLength()) throw syntax("Mermaid identifier budget exceeded");
            return result;
        }

        String nodeLabel(char close) {
            int start = offset;
            boolean quoted = false;
            boolean escaped = false;
            while (offset < text.length()) {
                char ch = text.charAt(offset++);
                if (escaped) { escaped = false; continue; }
                if (ch == '\\') { escaped = true; continue; }
                if (ch == '"') { quoted = !quoted; continue; }
                if (!quoted && ch == close) return text.substring(start, offset - 1);
                if (!quoted && (ch == '[' || ch == '{' || ch == '(')) {
                    throw syntax("Nested node shapes are not supported");
                }
            }
            throw syntax("Unclosed node label");
        }

        String pipeLabel() {
            int start = offset;
            boolean quoted = false;
            boolean escaped = false;
            while (offset < text.length()) {
                char ch = text.charAt(offset++);
                if (escaped) { escaped = false; continue; }
                if (ch == '\\') { escaped = true; continue; }
                if (ch == '"') { quoted = !quoted; continue; }
                if (!quoted && ch == '|') return text.substring(start, offset - 1);
            }
            throw syntax("Unclosed edge label");
        }

        String untilArrow() {
            whitespace();
            int start = offset;
            while (offset < text.length() && !text.startsWith("-->", offset)) offset++;
            if (offset == text.length()) throw syntax("Expected '-->' after edge label");
            String label = text.substring(start, offset);
            offset += 3;
            return label;
        }

        String rest() {
            whitespace();
            String result = text.substring(offset);
            offset = text.length();
            return result;
        }

        Syntax syntax(String reason) { return new Syntax(reason + " on line " + line); }
    }
}
