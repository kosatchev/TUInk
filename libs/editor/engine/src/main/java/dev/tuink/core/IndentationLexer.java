package dev.tuink.core;

import dev.tuink.editor.language.LanguageId;
import java.nio.file.Path;
import java.util.Arrays;

/** Small lexical guard for editing decisions, independent of presentation syntax rules. */
final class IndentationLexer {
    private static final int MAX_NESTING = 128;
    private static final int MAX_DELIMITER = 128;

    private IndentationLexer() {}

    enum Kind { CODE, BLOCK_COMMENT, HTML_COMMENT, QUOTE, TEMPLATE, YAML_BLOCK }

    record State(Kind kind, String close, boolean escapes, boolean doubled, boolean multiline,
                 int depth, int indent, Template template, Parenthesis parentheses,
                 boolean regexAllowed, boolean pendingControl, boolean certainPrefix) {
        static final State CODE = new State(Kind.CODE, "", false, false, false, 0, 0, null, null, true, false, true);

        int weight() {
            return 1 + close.length() + (template == null ? 0 : template.depth())
                + (parentheses == null ? 0 : parentheses.depth());
        }
    }

    record Template(int braces, Template parent, int depth) {
        Template(int braces, Template parent) { this(braces, parent, parent == null ? 1 : parent.depth() + 1); }
    }

    record Parenthesis(boolean control, Parenthesis parent, int depth) {
        Parenthesis(boolean control, Parenthesis parent) { this(control, parent, parent == null ? 1 : parent.depth() + 1); }
    }

    record Result(State afterLine, boolean codeAtCursor, String code) {}

    static boolean needsContext(LanguageId language) {
        return switch (language) {
            case UNKNOWN, PLAIN_TEXT, CSV, DIFF, GIT_COMMIT, MARKDOWN -> false;
            default -> true;
        };
    }

    /** The caller charges the bounded prefix length before requesting a code array. */
    static Result scan(String text, int end, Path file, State before, boolean produceCode) {
        LanguageId language = LanguageProfiles.language(file);
        CommentSyntaxProfile comments = LanguageProfiles.resolveCommentSyntax(file);
        Scan scan = new Scan(text, end, produceCode, before);
        if (scan.kind == Kind.YAML_BLOCK) {
            int first = leading(text, end);
            if (first == end) {
                scan.mask(0, end);
                return scan.result();
            }
            int required = scan.indent < 0 ? scan.depth + 1 : scan.indent;
            if (first < required) {
                scan.kind = Kind.CODE;
            } else {
                if (scan.indent < 0) scan.indent = first;
                scan.mask(0, end);
                return scan.result();
            }
        }
        boolean nestedComments = switch (language) {
            case RUST, KOTLIN, SWIFT, SCALA -> true;
            default -> false;
        };
        boolean javascript = language == LanguageId.JAVASCRIPT || language == LanguageId.TYPESCRIPT;
        boolean ambiguousSlash = LanguageProfiles.hasAmbiguousSlashSyntax(file);
        while (scan.index < end) {
            int index = scan.index;
            char current = text.charAt(index);
            if (scan.kind == Kind.BLOCK_COMMENT || scan.kind == Kind.HTML_COMMENT) {
                String opening = scan.kind == Kind.BLOCK_COMMENT ? "/*" : "<!--";
                if (starts(text, index, end, scan.close)) {
                    scan.mask(index, index + scan.close.length());
                    scan.index += scan.close.length();
                    if (--scan.depth == 0) scan.kind = Kind.CODE;
                } else if (nestedComments && scan.kind == Kind.BLOCK_COMMENT && starts(text, index, end, opening)) {
                    if (++scan.depth > MAX_NESTING) return null;
                    scan.mask(index, index + opening.length());
                    scan.index += opening.length();
                } else {
                    scan.mask(index, ++scan.index);
                }
                continue;
            }
            if (scan.kind == Kind.QUOTE) {
                if (scan.escapes && current == '\\') {
                    scan.continuedQuote = index + 1 == end;
                    scan.index = Math.min(end, index + 2);
                } else if (starts(text, index, end, scan.close)) {
                    scan.index += scan.close.length();
                    if (scan.doubled && starts(text, scan.index, end, scan.close)) {
                        scan.index += scan.close.length();
                    } else {
                        scan.kind = Kind.CODE;
                        scan.regexAllowed = false;
                    }
                } else {
                    scan.index++;
                }
                scan.mask(index, scan.index);
                continue;
            }
            if (scan.kind == Kind.TEMPLATE) {
                if (current == '\\') {
                    scan.index = Math.min(end, index + 2);
                } else if (current == '`') {
                    scan.template = scan.template.parent();
                    scan.kind = Kind.CODE;
                    scan.regexAllowed = false;
                    scan.index++;
                } else if (starts(text, index, end, "${")) {
                    scan.template = new Template(1, scan.template.parent());
                    scan.kind = Kind.CODE;
                    scan.regexAllowed = true;
                    scan.index += 2;
                } else {
                    scan.index++;
                }
                scan.mask(index, scan.index);
                continue;
            }
            boolean inTemplate = scan.template != null;
            if (inTemplate) scan.mask(index, index + 1);
            boolean separated = index == 0 || Character.isWhitespace(text.charAt(index - 1));
            if (comments.hashLine() && current == '#' && (language != LanguageId.YAML || separated)
                || comments.semicolonLine() && current == ';' && separated
                || comments.slashLine() && starts(text, index, end, "//")
                || comments.dashDashLine() && starts(text, index, end, "--")) {
                scan.mask(index, end);
                break;
            }
            if (comments.slashBlock() && starts(text, index, end, "/*")
                || comments.htmlBlock() && starts(text, index, end, "<!--")) {
                boolean slash = text.charAt(index) == '/';
                scan.kind = slash ? Kind.BLOCK_COMMENT : Kind.HTML_COMMENT;
                scan.close = slash ? "*/" : "-->";
                scan.depth = 1;
                scan.index += slash ? 2 : 4;
                scan.mask(index, scan.index);
                continue;
            }
            if (javascript && current == '`') {
                if (scan.template != null && scan.template.depth() >= MAX_NESTING) return null;
                scan.template = new Template(0, scan.template);
                scan.kind = Kind.TEMPLATE;
                scan.mask(index, ++scan.index);
                continue;
            }
            if (language == LanguageId.YAML && (current == '|' || current == '>')
                && yamlScalarStart(text, index)
                && yamlBlockTail(text, index + 1, end)) {
                scan.kind = Kind.YAML_BLOCK;
                scan.depth = yamlBlockParentIndent(text, index);
                scan.indent = -1;
                for (int at = index + 1; at < end && !Character.isWhitespace(text.charAt(at)); at++) {
                    if (text.charAt(at) >= '1' && text.charAt(at) <= '9') scan.indent = scan.depth + text.charAt(at) - '0';
                }
                scan.mask(index, end);
                break;
            }
            int rawDelimiterStart = language == LanguageId.CPP ? cppRawDelimiterStart(text, index, end) : -1;
            if (rawDelimiterStart >= 0) {
                int opening = rawDelimiterStart;
                while (opening < end && opening - rawDelimiterStart <= 16 && text.charAt(opening) != '('
                    && !Character.isWhitespace(text.charAt(opening)) && text.charAt(opening) != '\\'
                    && text.charAt(opening) != ')') opening++;
                if (opening < end && text.charAt(opening) == '(' && opening - rawDelimiterStart <= 16) {
                    scan.openQuote(")" + text.substring(rawDelimiterStart, opening) + "\"", false, false, true, opening + 1);
                    scan.mask(index, scan.index);
                    scan.literal(index);
                    continue;
                }
            }
            if (language == LanguageId.RUST && (current == 'r' || current == 'b' && starts(text, index, end, "br"))) {
                int quote = index + (current == 'b' ? 2 : 1);
                while (quote < end && text.charAt(quote) == '#' && quote - index <= 18) quote++;
                if (quote < end && text.charAt(quote) == '"' && quote - index <= 18) {
                    int hashes = quote - index - (current == 'b' ? 2 : 1);
                    scan.openQuote("\"" + "#".repeat(hashes), false, false, true, quote + 1);
                    scan.mask(index, scan.index);
                    scan.literal(index);
                    continue;
                }
            }
            if (language == LanguageId.CPP && Character.isDigit(current)) {
                scan.index++;
                while (scan.index < end) {
                    char digit = text.charAt(scan.index);
                    if (Character.isLetterOrDigit(digit) || digit == '_' || digit == '.') {
                        scan.index++;
                    } else if (digit == '\'' && scan.index + 1 < end
                        && Character.isLetterOrDigit(text.charAt(scan.index + 1))) {
                        scan.mask(scan.index, scan.index + 1);
                        scan.index++;
                    } else {
                        break;
                    }
                }
                scan.regexAllowed = false;
                scan.pendingControl = false;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`' && (language == LanguageId.GO || language == LanguageId.SHELL)) {
                if (language == LanguageId.YAML && !yamlScalarStart(text, index)) {
                    scan.index++;
                    continue;
                }
                if (current == '\'' && language == LanguageId.RUST) {
                    int lifetime = index + 1;
                    while (lifetime < end && Character.isUnicodeIdentifierPart(text.charAt(lifetime))) lifetime++;
                    if (lifetime > index + 1 && (lifetime == end || text.charAt(lifetime) != '\'')) {
                        scan.index = lifetime;
                        continue;
                    }
                }
                boolean triple = switch (language) {
                    case PYTHON, TOML, GROOVY, DART -> current != '`';
                    case JAVA, KOTLIN, SCALA, SWIFT, CSHARP -> current == '"';
                    default -> false;
                };
                String delimiter = String.valueOf(current);
                int length = triple && starts(text, index, end, delimiter.repeat(3)) ? 3 : 1;
                if (language == LanguageId.CSHARP && length == 3) {
                    while (index + length < end && text.charAt(index + length) == current) length++;
                    if (length > MAX_DELIMITER) return null;
                }
                boolean verbatim = language == LanguageId.CSHARP && current == '"' && index > 0
                    && (text.charAt(index - 1) == '@' || index > 1 && text.charAt(index - 1) == '$' && text.charAt(index - 2) == '@');
                boolean raw = language == LanguageId.GO && current == '`' || language == LanguageId.TOML && current == '\''
                    || (language == LanguageId.KOTLIN || language == LanguageId.SCALA || language == LanguageId.CSHARP) && length >= 3;
                boolean multiline = length >= 3 || verbatim || switch (language) {
                    case SQL, SHELL, RUBY, PERL, PHP, YAML, HTML, XML -> true;
                    case GO -> current == '`';
                    default -> false;
                };
                scan.openQuote(delimiter.repeat(length), !raw && !verbatim
                    && !((language == LanguageId.SHELL || language == LanguageId.YAML) && current == '\''),
                    verbatim || language == LanguageId.SQL || language == LanguageId.YAML && current == '\'', multiline, index + length);
                scan.mask(index, scan.index);
                scan.literal(index);
                continue;
            }
            if (ambiguousSlash && current == '/') {
                // Command arguments, division and slash literals need more
                // grammar in these languages. Do not invent a proven state.
                return null;
            }
            if (javascript && current == '/' && scan.regexAllowed) {
                int closing = regexEnd(text, index + 1, end);
                if (closing < 0) return null;
                scan.index = closing + 1;
                while (scan.index < end && Character.isLetter(text.charAt(scan.index))) scan.index++;
                scan.mask(index, scan.index);
                scan.literal(index);
                scan.regexAllowed = false;
                scan.pendingControl = false;
                continue;
            }
            if (Character.isUnicodeIdentifierStart(current) || current == '$') {
                int finish = index + 1;
                while (finish < end && (Character.isUnicodeIdentifierPart(text.charAt(finish)) || text.charAt(finish) == '$')) finish++;
                String word = text.substring(index, finish);
                boolean property = index > 0 && text.charAt(index - 1) == '.';
                scan.pendingControl = !property && (isControl(word) || word.equals("await") && scan.pendingControl);
                scan.regexAllowed = !property && switch (word) {
                    case "return", "throw", "case", "delete", "typeof", "void", "new", "yield", "await",
                        "in", "instanceof", "of", "else", "do" -> true;
                    default -> false;
                };
                scan.index = finish;
                if (inTemplate) scan.mask(index, finish);
                continue;
            }
            if (!Character.isWhitespace(current)) {
                // These tokens outside a recognized literal can carry syntax
                // this small lexer cannot prove (for example a continuation).
                if (current == '\\' || current == '`') scan.certainPrefix = false;
                if (current == '(') {
                    if (scan.parentheses != null && scan.parentheses.depth() >= MAX_NESTING) return null;
                    scan.parentheses = new Parenthesis(scan.pendingControl, scan.parentheses);
                    scan.regexAllowed = true;
                } else if (current == ')') {
                    scan.regexAllowed = scan.parentheses != null && scan.parentheses.control();
                    if (scan.parentheses != null) scan.parentheses = scan.parentheses.parent();
                } else if (inTemplate && current == '{') {
                    if (scan.template.braces() >= MAX_NESTING) return null;
                    scan.template = new Template(scan.template.braces() + 1, scan.template.parent());
                    scan.regexAllowed = true;
                } else if (inTemplate && current == '}') {
                    int braces = scan.template.braces() - 1;
                    scan.template = new Template(braces, scan.template.parent());
                    if (braces == 0) scan.kind = Kind.TEMPLATE;
                    scan.regexAllowed = true;
                } else {
                    scan.regexAllowed = !Character.isLetterOrDigit(current) && current != ']' && current != '.';
                }
                scan.pendingControl = false;
            }
            scan.index++;
        }
        return scan.result();
    }

    private static boolean starts(String text, int at, int end, String token) {
        return at + token.length() <= end && text.startsWith(token, at);
    }

    private static int cppRawDelimiterStart(String text, int at, int end) {
        if (at > 0 && (Character.isUnicodeIdentifierPart(text.codePointBefore(at)) || text.charAt(at - 1) == '$')) {
            return -1;
        }
        if (starts(text, at, end, "R\"")) return at + 2;
        if (starts(text, at, end, "u8R\"")) return at + 4;
        if (starts(text, at, end, "uR\"") || starts(text, at, end, "UR\"") || starts(text, at, end, "LR\"")) {
            return at + 3;
        }
        return -1;
    }

    private static int leading(String text, int end) {
        int first = 0;
        while (first < end && (text.charAt(first) == ' ' || text.charAt(first) == '\t')) first++;
        return first;
    }

    private static boolean yamlBlockTail(String text, int from, int end) {
        for (int index = from; index < end; index++) {
            char current = text.charAt(index);
            if (current == '#') return index > from && Character.isWhitespace(text.charAt(index - 1));
            if (!Character.isWhitespace(current) && current != '+' && current != '-' && (current < '1' || current > '9')) return false;
        }
        return true;
    }

    private static int yamlBlockParentIndent(String text, int scalar) {
        int parent = leading(text, scalar);
        int node = parent;
        // Compact collections add indentation after their indicators without
        // adding leading whitespace: "- - description: |2" owns its scalar at
        // the mapping key's column, while "- - |2" uses the inner dash's column.
        while (node + 1 < scalar) {
            char indicator = text.charAt(node);
            if ((indicator != '-' && indicator != '?' && indicator != ':')
                || !Character.isWhitespace(text.charAt(node + 1))) break;
            parent = node++;
            while (node < scalar && Character.isWhitespace(text.charAt(node))) node++;
        }
        int content = node;
        // Node properties do not turn a direct sequence scalar into a mapping.
        while (content < scalar && (text.charAt(content) == '&' || text.charAt(content) == '!')) {
            while (content < scalar && !Character.isWhitespace(text.charAt(content))) content++;
            while (content < scalar && Character.isWhitespace(text.charAt(content))) content++;
        }
        return content < scalar ? node : parent;
    }

    private static boolean yamlScalarStart(String text, int at) {
        while (true) {
            int previous = at - 1;
            while (previous >= 0 && Character.isWhitespace(text.charAt(previous))) previous--;
            if (previous < 0) return true;
            char before = text.charAt(previous);
            // Quote and block-scalar indicators inside a plain scalar are text.
            // A new scalar starts at a node boundary, including flow collections.
            if (before == ':') {
                return previous + 1 < at || previous > 0
                    && (text.charAt(previous - 1) == '\'' || text.charAt(previous - 1) == '"');
            }
            if (before == '[' || before == '{' || before == ',') return true;
            if ((before == '-' || before == '?') && previous + 1 < at) {
                at = previous;
            } else {
                if (previous + 1 == at) return false;
                int token = previous;
                while (token > 0 && !Character.isWhitespace(text.charAt(token - 1))) token--;
                if (text.charAt(token) == '!' || text.charAt(token) == '&') {
                    at = token;
                } else {
                    return false;
                }
            }
        }
    }

    private static boolean isControl(String word) {
        return switch (word) {
            case "if", "while", "for", "with", "switch", "catch" -> true;
            default -> false;
        };
    }

    private static int regexEnd(String text, int from, int end) {
        boolean characterClass = false;
        for (int index = from; index < end; index++) {
            char current = text.charAt(index);
            if (current == '\\') index++;
            else if (current == '[') characterClass = true;
            else if (current == ']') characterClass = false;
            else if (current == '/' && !characterClass) return index;
        }
        return -1;
    }

    private static final class Scan {
        private final char[] code;
        private Kind kind;
        private String close;
        private boolean escapes;
        private boolean doubled;
        private boolean multiline;
        private int depth;
        private int indent;
        private Template template;
        private Parenthesis parentheses;
        private boolean regexAllowed;
        private boolean pendingControl;
        private boolean continuedQuote;
        private boolean certainPrefix;
        private int index;

        private Scan(String text, int end, boolean produceCode, State before) {
            code = produceCode ? new char[end] : null;
            if (code != null) text.getChars(0, end, code, 0);
            kind = before.kind();
            close = before.close();
            escapes = before.escapes();
            doubled = before.doubled();
            multiline = before.multiline();
            depth = before.depth();
            indent = before.indent();
            template = before.template();
            parentheses = before.parentheses();
            regexAllowed = before.regexAllowed();
            pendingControl = before.pendingControl();
            certainPrefix = before.certainPrefix();
        }

        private void openQuote(String delimiter, boolean escape, boolean doubleDelimiter, boolean multipleLines, int afterOpening) {
            kind = Kind.QUOTE;
            close = delimiter;
            escapes = escape;
            doubled = doubleDelimiter;
            multiline = multipleLines;
            index = afterOpening;
        }

        private void mask(int from, int to) {
            if (code != null) Arrays.fill(code, from, to, ' ');
        }

        private void literal(int opening) {
            if (code != null && template == null) code[opening] = '0';
        }

        private Result result() {
            boolean atCode = kind == Kind.CODE && template == null;
            Kind after = kind == Kind.QUOTE && !multiline && !continuedQuote ? Kind.CODE : kind;
            // An ordinary string left open at a newline may require recovery
            // or continuation grammar. Keep useful masking/indent increases,
            // but preserve the conservative guard against deleting indentation.
            boolean certain = certainPrefix && !(kind == Kind.QUOTE && !multiline);
            State state = new State(after, close, escapes, doubled, multiline, depth, indent, template,
                parentheses, regexAllowed, pendingControl, certain);
            return new Result(state, atCode, code == null ? null : new String(code));
        }
    }
}
