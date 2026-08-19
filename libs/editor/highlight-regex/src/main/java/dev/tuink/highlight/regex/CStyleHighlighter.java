package dev.tuink.highlight.regex;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.render.Color;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Applies quoted strings and C-style comments in source order, over ordinary regex rules.
 */
final class CStyleHighlighter extends RegexHighlighter implements ContextualLineHighlighter {
    private static final Style STRING_STYLE = Style.fg(Color.BRIGHT_GREEN);
    private static final Style COMMENT_STYLE = Style.fg(Color.BRIGHT_BLACK);
    private static final Set<String> REGEX_PREFIX_KEYWORDS = Set.of(
        "return", "throw", "case", "delete", "typeof", "void", "new", "yield", "await", "in", "instanceof", "of"
    );

    enum Syntax {
        STANDARD(false, false, true, false),
        JAVA(false, false, true, false),
        GROOVY(false, false, true, false),
        BACKTICK(true, false, true, false),
        GO(true, true, true, false),
        RUST(false, false, true, false),
        KOTLIN(false, false, true, false),
        PHP(false, false, true, true),
        CSS(false, false, false, false);

        private final boolean backtickStrings;
        private final boolean rawBackticks;
        private final boolean slashLineComments;
        private final boolean hashLineComments;

        Syntax(boolean backtickStrings, boolean rawBackticks, boolean slashLineComments, boolean hashLineComments) {
            this.backtickStrings = backtickStrings;
            this.rawBackticks = rawBackticks;
            this.slashLineComments = slashLineComments;
            this.hashLineComments = hashLineComments;
        }

        boolean tripleQuotes(char quote) {
            return quote == '"' && (this == JAVA || this == KOTLIN || this == GROOVY)
                || quote == '\'' && this == GROOVY;
        }

        boolean continuesAcrossLines(QuotedString string) {
            return string.triple()
                || string.quote() == '`' && backtickStrings
                || string.quote() == '"' && (this == RUST || this == PHP)
                || string.quote() == '\'' && this == PHP;
        }

        boolean backslashEscapes(QuotedString string) {
            return !(string.quote() == '`' && rawBackticks) && !(string.triple() && this == KOTLIN);
        }
    }

    private record QuotedString(char quote, boolean triple) {
        int delimiterLength() {
            return triple ? 3 : 1;
        }
    }

    private record ScanState(int blockDepth, QuotedString string) {
        private static final ScanState EMPTY = new ScanState(0, null);
    }

    private final Syntax syntax;
    private DocumentId cachedDocumentId;
    private long cachedRevision = Long.MIN_VALUE;
    private int cachedFirstLine = -1;
    private final List<ScanState> stateBeforeLine = new ArrayList<>();

    CStyleHighlighter(List<RegexRule> rules) {
        this(rules, Syntax.STANDARD);
    }

    CStyleHighlighter(List<RegexRule> rules, Syntax syntax) {
        super(rules);
        this.syntax = syntax;
    }

    @Override
    public List<StyleSpan> highlight(String line) {
        return highlight(line, ScanState.EMPTY);
    }

    @Override
    public List<StyleSpan> highlight(DocumentView document, int lineIndex, String line) {
        return highlight(document, lineIndex, line, 0);
    }

    // Markdown code fences start a fresh lexical context at their first content line.
    List<StyleSpan> highlight(DocumentView document, int lineIndex, String line, int firstLine) {
        if (document == null || line == null
            || firstLine < 0 || lineIndex < firstLine || lineIndex >= document.lineCount()) {
            return highlight(line);
        }
        ensureComputed(document, lineIndex, firstLine);
        return highlight(line, stateBeforeLine.get(lineIndex - firstLine));
    }

    private void ensureComputed(DocumentView document, int targetLine, int firstLine) {
        if (!Objects.equals(document.documentId(), cachedDocumentId)
            || document.revision() != cachedRevision
            || firstLine != cachedFirstLine) {
            cachedDocumentId = document.documentId();
            cachedRevision = document.revision();
            cachedFirstLine = firstLine;
            stateBeforeLine.clear();
            stateBeforeLine.add(ScanState.EMPTY);
        }
        while (stateBeforeLine.size() <= targetLine - firstLine) {
            int previousIndex = stateBeforeLine.size() - 1;
            String previousLine = document.line(firstLine + previousIndex);
            ScanState previousState = stateBeforeLine.get(previousIndex);
            stateBeforeLine.add(scan(previousLine, previousState, null));
        }
    }

    private List<StyleSpan> highlight(String line, ScanState state) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        Style[] styleAt = highlightStyles(line);
        scan(line, state, styleAt);
        return spans(styleAt);
    }

    private ScanState scan(String line, ScanState state, Style[] styleAt) {
        int blockDepth = state.blockDepth();
        QuotedString string = state.string();
        boolean expressionStart = true;
        int cursor = 0;
        while (cursor < line.length()) {
            if (blockDepth > 0) {
                if (line.startsWith("*/", cursor)) {
                    paint(styleAt, cursor, cursor + 2, COMMENT_STYLE);
                    blockDepth--;
                    cursor += 2;
                } else if ((syntax == Syntax.RUST || syntax == Syntax.KOTLIN) && line.startsWith("/*", cursor)) {
                    paint(styleAt, cursor, cursor + 2, COMMENT_STYLE);
                    blockDepth++;
                    cursor += 2;
                } else {
                    paint(styleAt, cursor, cursor + 1, COMMENT_STYLE);
                    cursor++;
                }
                continue;
            }
            if (string != null) {
                int end = quotedEnd(line, cursor, string);
                if (end < 0) {
                    paint(styleAt, cursor, line.length(), STRING_STYLE);
                    return syntax.continuesAcrossLines(string) ? new ScanState(0, string) : ScanState.EMPTY;
                }
                paint(styleAt, cursor, end, STRING_STYLE);
                cursor = end;
                string = null;
                expressionStart = false;
                continue;
            }
            char current = line.charAt(cursor);
            int regexEnd = current == '/' && syntax == Syntax.BACKTICK && expressionStart
                && !line.startsWith("//", cursor) && !line.startsWith("/*", cursor)
                ? regexLiteralEnd(line, cursor) : -1;
            if (current == '"' || current == '\'' || (current == '`' && syntax.backtickStrings)) {
                if (current == '\'' && syntax == Syntax.RUST) {
                    int end = rustCharacterEnd(line, cursor);
                    if (end < 0) {
                        // Rust lifetimes begin with a quote but are not character literals.
                        cursor++;
                    } else {
                        paint(styleAt, cursor, end, STRING_STYLE);
                        cursor = end;
                    }
                } else {
                    boolean triple = syntax.tripleQuotes(current)
                        && cursor + 2 < line.length()
                        && line.charAt(cursor + 1) == current && line.charAt(cursor + 2) == current;
                    string = new QuotedString(current, triple);
                    paint(styleAt, cursor, cursor + string.delimiterLength(), STRING_STYLE);
                    cursor += string.delimiterLength();
                }
                expressionStart = false;
            } else if ((syntax.slashLineComments && line.startsWith("//", cursor))
                || (syntax.hashLineComments && current == '#')) {
                paint(styleAt, cursor, line.length(), COMMENT_STYLE);
                return ScanState.EMPTY;
            } else if (line.startsWith("/*", cursor)) {
                paint(styleAt, cursor, cursor + 2, COMMENT_STYLE);
                blockDepth = 1;
                cursor += 2;
            } else if (regexEnd > cursor) {
                paint(styleAt, cursor, regexEnd, STRING_STYLE);
                cursor = regexEnd;
                expressionStart = false;
            } else if (syntax == Syntax.BACKTICK && Character.isJavaIdentifierStart(current)) {
                int start = cursor++;
                while (cursor < line.length() && Character.isJavaIdentifierPart(line.charAt(cursor))) {
                    cursor++;
                }
                expressionStart = REGEX_PREFIX_KEYWORDS.contains(line.substring(start, cursor));
            } else {
                if (syntax == Syntax.BACKTICK && !Character.isWhitespace(current)) {
                    if ((current == '+' || current == '-') && cursor + 1 < line.length()
                        && line.charAt(cursor + 1) == current) {
                        cursor += 2;
                        continue;
                    }
                    expressionStart = "=([{,:;!?&|+-*%^~<>/".indexOf(current) >= 0;
                }
                cursor++;
            }
        }
        if (blockDepth > 0) {
            return new ScanState(blockDepth, null);
        }
        return string != null && syntax.continuesAcrossLines(string) ? new ScanState(0, string) : ScanState.EMPTY;
    }

    private static void paint(Style[] styleAt, int start, int end, Style style) {
        if (styleAt != null) {
            Arrays.fill(styleAt, start, end, style);
        }
    }

    private int quotedEnd(String line, int start, QuotedString string) {
        boolean escaped = syntax.backslashEscapes(string);
        for (int cursor = start; cursor < line.length(); cursor++) {
            char current = line.charAt(cursor);
            if (escaped && current == '\\') {
                cursor++;
            } else if (current == string.quote()
                && (!string.triple() || cursor + 2 < line.length()
                    && line.charAt(cursor + 1) == current && line.charAt(cursor + 2) == current)) {
                return cursor + string.delimiterLength();
            }
        }
        return -1;
    }

    private static int regexLiteralEnd(String line, int start) {
        boolean characterClass = false;
        for (int cursor = start + 1; cursor < line.length(); cursor++) {
            char current = line.charAt(cursor);
            if (current == '\\') {
                cursor++;
            } else if (current == '[') {
                characterClass = true;
            } else if (current == ']') {
                characterClass = false;
            } else if (current == '/' && !characterClass) {
                cursor++;
                while (cursor < line.length() && Character.isLetter(line.charAt(cursor))) {
                    cursor++;
                }
                return cursor;
            }
        }
        return -1;
    }

    private static int rustCharacterEnd(String line, int start) {
        int cursor = start + 1;
        if (cursor >= line.length()) {
            return -1;
        }
        if (line.charAt(cursor) == '\\') {
            cursor++;
            if (cursor >= line.length()) {
                return -1;
            }
            char escape = line.charAt(cursor++);
            if (escape == 'u' && cursor < line.length() && line.charAt(cursor) == '{') {
                int close = line.indexOf('}', cursor + 1);
                if (close < 0) {
                    return -1;
                }
                cursor = close + 1;
            } else if (escape == 'x') {
                cursor += 2;
            }
        } else {
            cursor += Character.charCount(line.codePointAt(cursor));
        }
        return cursor < line.length() && line.charAt(cursor) == '\'' ? cursor + 1 : -1;
    }
}
