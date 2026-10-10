package dev.tuink.highlight.regex;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.render.Color;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.LineLexicalScanner;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Applies language-specific strings and comments in source order, over ordinary regex rules.
 */
final class CStyleHighlighter extends RegexHighlighter implements ContextualLineHighlighter {
    private static final Style STRING_STYLE = Style.fg(Color.BRIGHT_GREEN);
    private static final Style COMMENT_STYLE = Style.fg(Color.BRIGHT_BLACK);
    private static final Set<String> REGEX_PREFIX_KEYWORDS = Set.of(
        "return", "throw", "case", "delete", "typeof", "void", "new", "yield", "await", "in", "instanceof", "of",
        "else", "do"
    );
    private static final Set<String> CONTROL_CONDITION_KEYWORDS = Set.of(
        "if", "while", "for", "with", "switch", "catch"
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
        CSS(false, false, false, false),
        PYTHON(false, false, false, true),
        YAML(false, false, false, true),
        SHELL(false, false, false, true),
        RUBY(true, false, false, true),
        PERL(false, false, false, true),
        TOML(false, false, false, true),
        ENV(false, false, false, true),
        INI(false, false, false, true),
        PROPERTIES(false, false, false, true),
        EDITORCONFIG(false, false, false, true),
        DOCKERFILE(false, false, false, true),
        MARKUP(false, false, false, false),
        SQL(false, false, false, false);

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
            return this == PYTHON || this == TOML
                || quote == '"' && (this == JAVA || this == KOTLIN || this == GROOVY)
                || quote == '\'' && this == GROOVY;
        }

        boolean continuesAcrossLines(QuotedString string, String line) {
            return string.raw()
                || string.triple()
                || this == YAML || this == SQL
                || this == SHELL || this == ENV || this == RUBY || this == MARKUP
                || this == PYTHON && endsWithUnescapedBackslash(line)
                || string.quote() == '`' && backtickStrings
                || string.quote() == '"' && (this == RUST || this == PHP)
                || string.quote() == '\'' && this == PHP;
        }

        boolean backslashEscapes(QuotedString string) {
            return !string.raw() && this != SQL && this != MARKUP && !(this == YAML && string.quote() == '\'')
                && !(string.quote() == '\'' && (this == SHELL || this == TOML || this == ENV || this == DOCKERFILE))
                && !(string.quote() == '`' && rawBackticks) && !(string.triple() && this == KOTLIN);
        }

        boolean doubledQuoteEscapes(QuotedString string) {
            return this == SQL || this == YAML && string.quote() == '\'';
        }

        boolean quotedStrings() {
            // Java properties and EditorConfig values do not have quoted-string syntax.
            return this != PROPERTIES && this != EDITORCONFIG;
        }

        boolean startsLineComment(String line, int cursor) {
            if (this == SQL) {
                return line.startsWith("--", cursor);
            }
            if (this == INI) {
                return (line.charAt(cursor) == '#' || line.charAt(cursor) == ';')
                    && (cursor == 0 || Character.isWhitespace(line.charAt(cursor - 1)));
            }
            if (this == DOCKERFILE || this == PROPERTIES || this == EDITORCONFIG) {
                char current = line.charAt(cursor);
                if (current != '#' && !(this == PROPERTIES && current == '!')
                    && !(this == EDITORCONFIG && current == ';')) {
                    return false;
                }
                for (int before = cursor - 1; before >= 0; before--) {
                    if (!Character.isWhitespace(line.charAt(before))) {
                        return false;
                    }
                }
                return true;
            }
            if (this == SHELL) {
                return line.charAt(cursor) == '#'
                    && (cursor == 0 || Character.isWhitespace(line.charAt(cursor - 1))
                        || ";|&()<>".indexOf(line.charAt(cursor - 1)) >= 0);
            }
            if (this == PERL && cursor > 0 && line.charAt(cursor - 1) == '$') {
                // $#array is Perl's last-array-index variable, not a comment.
                return false;
            }
            return slashLineComments && line.startsWith("//", cursor)
                || hashLineComments && line.charAt(cursor) == '#'
                    && (this != YAML || cursor == 0 || yamlSeparation(line.charAt(cursor - 1)));
        }

        boolean slashBlockComments() {
            return slashLineComments || this == CSS || this == SQL;
        }
    }

    private record RubyFrame(char quote, int braces, RubyFrame parent, int depth, int cachedHash) {
        private RubyFrame(char quote, int braces, RubyFrame parent) {
            this(quote, braces, parent, parent == null ? 1 : parent.depth + 1,
                31 * (31 * (parent == null ? 1 : parent.cachedHash) + quote) + braces);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RubyFrame that) || depth != that.depth || cachedHash != that.cachedHash) {
                return false;
            }
            RubyFrame left = this;
            RubyFrame right = that;
            while (left != right) {
                if (left == null || right == null || left.quote != right.quote || left.braces != right.braces) {
                    return false;
                }
                left = left.parent;
                right = right.parent;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return cachedHash;
        }

        @Override
        public String toString() {
            return "RubyFrame[quote=" + (int) quote + ", braces=" + braces + ", depth=" + depth + "]";
        }
    }

    private record QuotedString(char quote, boolean triple, int rawHashes, RubyFrame rubyFrame) {
        private QuotedString(char quote, boolean triple, int rawHashes) {
            this(quote, triple, rawHashes, null);
        }

        boolean raw() {
            return rawHashes >= 0;
        }

        int delimiterLength() {
            return raw() ? rawHashes + 1 : triple ? 3 : 1;
        }
    }

    private record Parenthesis(boolean controlCondition, Parenthesis parent, int depth, int cachedHash) {
        private Parenthesis(boolean controlCondition, Parenthesis parent) {
            this(controlCondition, parent, parent == null ? 1 : parent.depth + 1,
                31 * (parent == null ? 1 : parent.cachedHash) + Boolean.hashCode(controlCondition));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Parenthesis that) || depth != that.depth || cachedHash != that.cachedHash) {
                return false;
            }
            Parenthesis left = this;
            Parenthesis right = that;
            while (left != right) {
                if (left == null || right == null || left.controlCondition != right.controlCondition) {
                    return false;
                }
                left = left.parent;
                right = right.parent;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return cachedHash;
        }

        @Override
        public String toString() {
            return "Parenthesis[controlCondition=" + controlCondition + ", depth=" + depth + "]";
        }
    }

    private record JavaScriptContext(Parenthesis parentheses, boolean controlKeyword, boolean propertyAccess) {
        private static final JavaScriptContext EMPTY = new JavaScriptContext(null, false, false);
    }

    private record ScanState(
        int blockDepth,
        QuotedString string,
        int yamlFlowDepth,
        int yamlPlainIndent,
        JavaScriptContext javascript,
        boolean markupTag
    )
        implements LineLexicalScanner.State {
        private static final ScanState EMPTY = new ScanState(0, null, 0, -1, JavaScriptContext.EMPTY, false);
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

    @Override
    public LineLexicalScanner lexicalScanner() {
        return this::scanLexically;
    }

    private LineLexicalScanner.Result scanLexically(
        String line,
        int maxCodeUnits,
        LineLexicalScanner.State previous
    ) {
        Objects.requireNonNull(line, "line");
        if (maxCodeUnits < 0) {
            throw new IllegalArgumentException("maxCodeUnits must be non-negative");
        }
        int limit = Math.min(line.length(), maxCodeUnits);
        String prefix = limit == line.length() ? line : line.substring(0, limit);
        ScanOutput output = new ScanOutput(null, true);
        ScanState state = previous instanceof ScanState prior ? prior : ScanState.EMPTY;
        ScanResult result = scan(prefix, state, output, limit < line.length());
        if (result.scannedCodeUnits() == line.length() && output.openingQuote >= 0) {
            output.quotes.add(new LineLexicalScanner.Quote(output.openingQuote, -1));
        }
        return new LineLexicalScanner.Result(result.scannedCodeUnits(), result.endState(),
            output.excludedRanges(), output.quotes);
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
            || firstLine != cachedFirstLine) {
            cachedDocumentId = document.documentId();
            cachedRevision = document.revision();
            cachedFirstLine = firstLine;
            stateBeforeLine.clear();
            stateBeforeLine.add(ScanState.EMPTY);
        } else if (document.revision() != cachedRevision) {
            int firstChangedLine = Math.max(0, document.firstChangedLineSince(cachedRevision));
            // State immediately before the first changed line is still valid.
            int retainedStates = (int) Math.min(
                stateBeforeLine.size(),
                Math.max(0L, (long) firstChangedLine - firstLine) + 1L
            );
            stateBeforeLine.subList(retainedStates, stateBeforeLine.size()).clear();
            cachedRevision = document.revision();
        }
        while (stateBeforeLine.size() <= targetLine - firstLine) {
            int previousIndex = stateBeforeLine.size() - 1;
            String previousLine = document.line(firstLine + previousIndex);
            ScanState previousState = stateBeforeLine.get(previousIndex);
            stateBeforeLine.add(scan(previousLine, previousState, null).endState());
        }
    }

    private List<StyleSpan> highlight(String line, ScanState state) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        Style[] styleAt = highlightStyles(line);
        scan(line, state, new ScanOutput(styleAt, false));
        return spans(styleAt);
    }

    private record ScanResult(int scannedCodeUnits, ScanState endState) {
    }

    private ScanResult scan(String line, ScanState state, ScanOutput output) {
        return scan(line, state, output, false);
    }

    private ScanResult scan(String line, ScanState state, ScanOutput output, boolean partialLine) {
        ScanWork work = new ScanWork(line.length());
        try {
            return new ScanResult(line.length(), scanLine(line, state, output, work, partialLine));
        } catch (ScanLimitExceeded ignored) {
            // Malformed candidate literals must not repeatedly search an unbounded suffix.
            return new ScanResult(work.completedCodeUnits, ScanState.EMPTY);
        }
    }

    private ScanState scanLine(String line, ScanState state, ScanOutput output, ScanWork work, boolean partialLine) {
        int indentation = 0;
        if (syntax == Syntax.YAML) {
            while (indentation < line.length() && yamlSeparation(line.charAt(indentation))) {
                indentation++;
            }
            if (indentation == line.length()) {
                if (state.string() != null) {
                    paint(output, 0, line.length(), STRING_STYLE);
                }
                return state;
            }
        }
        int blockDepth = state.blockDepth();
        QuotedString string = state.string();
        boolean expressionStart = true;
        Parenthesis parentheses = state.javascript().parentheses();
        boolean controlKeyword = state.javascript().controlKeyword();
        boolean propertyAccess = state.javascript().propertyAccess();
        boolean markupTag = state.markupTag();
        boolean yamlPlainContinuation = syntax == Syntax.YAML && state.yamlPlainIndent() >= 0
            && (state.yamlFlowDepth() > 0 || indentation > state.yamlPlainIndent());
        boolean yamlNodeStart = !yamlPlainContinuation;
        int yamlPlainIndent = yamlPlainContinuation ? state.yamlPlainIndent() : -1;
        int yamlParentIndent = indentation;
        int yamlNodeColumn = -1;
        boolean yamlQuotedNode = false;
        int yamlFlowDepth = state.yamlFlowDepth();
        int cursor = 0;
        while (cursor < line.length()) {
            work.at(cursor);
            if (blockDepth > 0) {
                String closing = syntax == Syntax.MARKUP ? "-->" : "*/";
                if (line.startsWith(closing, cursor)) {
                    paint(output, cursor, cursor + closing.length(), COMMENT_STYLE);
                    blockDepth--;
                    cursor += closing.length();
                } else if ((syntax == Syntax.RUST || syntax == Syntax.KOTLIN) && line.startsWith("/*", cursor)) {
                    paint(output, cursor, cursor + 2, COMMENT_STYLE);
                    blockDepth++;
                    cursor += 2;
                } else {
                    paint(output, cursor, cursor + 1, COMMENT_STYLE);
                    cursor++;
                }
                continue;
            }
            if (string != null) {
                int end = quotedEnd(line, cursor, string, work);
                if (end < 0) {
                    paint(output, cursor, line.length(), STRING_STYLE);
                    if (syntax == Syntax.RUBY) {
                        string = new QuotedString(string.quote(), false, -1, work.rubyFrame);
                    }
                    return syntax.continuesAcrossLines(string, line)
                        ? scanState(0, string, yamlFlowDepth, -1,
                            parentheses, controlKeyword, propertyAccess, markupTag) : ScanState.EMPTY;
                }
                paint(output, cursor, end, STRING_STYLE);
                if (output != null) {
                    output.closeQuote(end - string.delimiterLength());
                }
                cursor = end;
                string = null;
                expressionStart = false;
                controlKeyword = false;
                propertyAccess = false;
                yamlNodeStart = false;
                yamlQuotedNode = true;
                continue;
            }
            char current = line.charAt(cursor);
            if (syntax == Syntax.MARKUP) {
                if (current == '<' && !line.startsWith("<!--", cursor) && cursor + 1 < line.length()
                    && (Character.isUnicodeIdentifierStart(line.charAt(cursor + 1))
                        || "/!?:".indexOf(line.charAt(cursor + 1)) >= 0)) {
                    markupTag = true;
                } else if (current == '>') {
                    markupTag = false;
                }
            }
            if (syntax == Syntax.SHELL && current == '\\') {
                // Outside quotes a shell backslash protects the next character too.
                cursor = Math.min(line.length(), cursor + 2);
                continue;
            }
            if (syntax == Syntax.PERL && (current == '\'' || current == '"')
                && (cursor > 0 && line.charAt(cursor - 1) == '$'
                    || !partialLine && quotedEnd(line, cursor + 1, new QuotedString(current, false, -1), work) < 0)) {
                // $'/$" are punctuation variables. Keep Perl's existing line-based
                // complete-string contract, so Foo'Bar does not open a string either.
                // The lookahead shares the line's work budget, including failed matches.
                cursor++;
                continue;
            }
            if (syntax == Syntax.YAML && yamlNodeStart && (current == '!' || current == '&')) {
                cursor = yamlPropertyEnd(line, cursor, work);
                continue;
            }
            int regexEnd = current == '/' && syntax == Syntax.BACKTICK && expressionStart
                && !line.startsWith("//", cursor) && !line.startsWith("/*", cursor)
                ? regexLiteralEnd(line, cursor, work) : -1;
            QuotedString rawString = syntax == Syntax.RUST ? rustRawStringAt(line, cursor, work) : null;
            if (rawString != null) {
                string = rawString;
                int prefixLength = current == 'r' ? 1 : 2;
                int openingEnd = cursor + prefixLength + string.delimiterLength();
                paint(output, cursor, openingEnd, STRING_STYLE);
                cursor = openingEnd;
                expressionStart = false;
            } else if (syntax.quotedStrings()
                && (current == '"' || current == '\'' || (current == '`' && syntax.backtickStrings))
                && (syntax != Syntax.YAML || yamlNodeStart)
                && (syntax != Syntax.MARKUP || markupTag)) {
                yamlNodeColumn = cursor;
                yamlPlainIndent = -1;
                if (current == '\'' && syntax == Syntax.RUST) {
                    int end = rustCharacterEnd(line, cursor, work);
                    if (end < 0) {
                        // Rust lifetimes begin with a quote but are not character literals.
                        cursor++;
                    } else {
                        paint(output, cursor, end, STRING_STYLE);
                        if (output != null) {
                            output.quote(cursor, end - 1);
                        }
                        cursor = end;
                    }
                } else {
                    boolean triple = syntax.tripleQuotes(current)
                        && cursor + 2 < line.length()
                        && line.charAt(cursor + 1) == current && line.charAt(cursor + 2) == current;
                    string = new QuotedString(current, triple, -1);
                    if (output != null && !triple && current != '`') {
                        output.openQuote(cursor);
                    }
                    paint(output, cursor, cursor + string.delimiterLength(), STRING_STYLE);
                    cursor += string.delimiterLength();
                }
                expressionStart = false;
                controlKeyword = false;
                propertyAccess = false;
            } else if (syntax.startsLineComment(line, cursor)) {
                paint(output, cursor, line.length(), COMMENT_STYLE);
                // A comment terminates a YAML plain scalar; a flow collection can continue.
                return scanState(0, null, yamlFlowDepth, -1,
                    parentheses, controlKeyword, propertyAccess, markupTag);
            } else if (syntax == Syntax.MARKUP && line.startsWith("<!--", cursor)) {
                paint(output, cursor, cursor + 4, COMMENT_STYLE);
                blockDepth = 1;
                cursor += 4;
            } else if (syntax.slashBlockComments() && line.startsWith("/*", cursor)) {
                paint(output, cursor, cursor + 2, COMMENT_STYLE);
                blockDepth = 1;
                cursor += 2;
            } else if (regexEnd > cursor) {
                paint(output, cursor, regexEnd, STRING_STYLE);
                cursor = regexEnd;
                expressionStart = false;
                controlKeyword = false;
                propertyAccess = false;
            } else if (syntax == Syntax.BACKTICK && Character.isJavaIdentifierStart(current)) {
                int start = cursor++;
                while (cursor < line.length() && Character.isJavaIdentifierPart(line.charAt(cursor))) {
                    cursor++;
                }
                String keyword = line.substring(start, cursor);
                // A control condition's closing ')' starts a statement; a call's ')' does not.
                // Keep a persistent stack so nested and multiline conditions stay linear.
                controlKeyword = !propertyAccess && (CONTROL_CONDITION_KEYWORDS.contains(keyword)
                    || controlKeyword && keyword.equals("await"));
                expressionStart = !propertyAccess && REGEX_PREFIX_KEYWORDS.contains(keyword);
                propertyAccess = false;
            } else {
                if (syntax == Syntax.YAML && !yamlSeparation(current)) {
                    boolean separatedNext = cursor + 1 == line.length()
                        || yamlSeparation(line.charAt(cursor + 1));
                    if (yamlNodeStart && (current == '[' || current == '{')) {
                        yamlFlowDepth++;
                        yamlPlainIndent = -1;
                        yamlNodeColumn = -1;
                    } else if (yamlFlowDepth > 0 && current == ',') {
                        yamlNodeStart = true;
                        yamlPlainIndent = -1;
                        yamlNodeColumn = -1;
                    } else if (yamlFlowDepth > 0 && (current == ']' || current == '}')) {
                        yamlFlowDepth--;
                        yamlNodeStart = false;
                        yamlPlainIndent = -1;
                        yamlNodeColumn = -1;
                    } else if (current == ':' && (separatedNext || yamlQuotedNode
                        || yamlFlowDepth > 0 && cursor + 1 < line.length()
                            && ",[]{}".indexOf(line.charAt(cursor + 1)) >= 0)) {
                        yamlNodeStart = true;
                        if (yamlNodeColumn >= 0) {
                            yamlParentIndent = yamlNodeColumn;
                        }
                        yamlPlainIndent = -1;
                        yamlNodeColumn = -1;
                    } else if (!(yamlNodeStart && (current == '-' || current == '?') && separatedNext)) {
                        if (yamlNodeStart) {
                            yamlPlainIndent = yamlParentIndent;
                            yamlNodeColumn = cursor;
                        }
                        yamlNodeStart = false;
                    }
                    yamlQuotedNode = false;
                }
                if (syntax == Syntax.BACKTICK && !Character.isWhitespace(current)) {
                    if ((current == '+' || current == '-') && cursor + 1 < line.length()
                        && line.charAt(cursor + 1) == current) {
                        controlKeyword = false;
                        propertyAccess = false;
                        cursor += 2;
                        continue;
                    }
                    if (current == '(') {
                        parentheses = new Parenthesis(controlKeyword, parentheses);
                        expressionStart = true;
                    } else if (current == ')') {
                        expressionStart = parentheses != null && parentheses.controlCondition();
                        parentheses = parentheses == null ? null : parentheses.parent();
                    } else {
                        expressionStart = "=([{,:;!?&|+-*%^~<>/".indexOf(current) >= 0;
                    }
                    controlKeyword = false;
                    propertyAccess = current == '.';
                }
                cursor++;
            }
        }
        if (blockDepth > 0) {
            return scanState(blockDepth, null, 0, -1,
                parentheses, controlKeyword, propertyAccess, markupTag);
        }
        if (string != null && syntax.continuesAcrossLines(string, line)) {
            return scanState(0, string, yamlFlowDepth, -1,
                parentheses, controlKeyword, propertyAccess, markupTag);
        }
        return scanState(0, null, yamlFlowDepth, yamlPlainIndent,
            parentheses, controlKeyword, propertyAccess, markupTag);
    }

    private static ScanState scanState(
        int blockDepth, QuotedString string, int yamlFlowDepth, int yamlPlainIndent,
        Parenthesis parentheses, boolean controlKeyword, boolean propertyAccess, boolean markupTag
    ) {
        JavaScriptContext javascript = parentheses == null && !controlKeyword && !propertyAccess
            ? JavaScriptContext.EMPTY : new JavaScriptContext(parentheses, controlKeyword, propertyAccess);
        return blockDepth == 0 && string == null && yamlFlowDepth == 0 && yamlPlainIndent < 0
            && javascript == JavaScriptContext.EMPTY && !markupTag
                ? ScanState.EMPTY : new ScanState(blockDepth, string, yamlFlowDepth, yamlPlainIndent, javascript, markupTag);
    }

    private static void paint(ScanOutput output, int start, int end, Style style) {
        if (output != null) {
            output.paint(start, end, style);
        }
    }

    private static final class ScanOutput {
        private final Style[] styles;
        private final boolean lexical;
        private final List<LineLexicalScanner.Range> ranges = new ArrayList<>();
        private final List<LineLexicalScanner.Quote> quotes = new ArrayList<>();
        private int rangeStart = -1;
        private int rangeEnd;
        private int openingQuote = -1;

        private ScanOutput(Style[] styles, boolean lexical) {
            this.styles = styles;
            this.lexical = lexical;
        }

        private void paint(int start, int end, Style style) {
            if (styles != null) {
                Arrays.fill(styles, start, end, style);
            }
            if (!lexical || start == end) {
                return;
            }
            if (rangeStart >= 0 && start <= rangeEnd) {
                rangeEnd = Math.max(rangeEnd, end);
            } else {
                finishRange();
                rangeStart = start;
                rangeEnd = end;
            }
        }

        private void finishRange() {
            if (rangeStart >= 0) {
                ranges.add(new LineLexicalScanner.Range(rangeStart, rangeEnd));
                rangeStart = -1;
            }
        }

        private List<LineLexicalScanner.Range> excludedRanges() {
            finishRange();
            return ranges;
        }

        private void openQuote(int start) {
            if (lexical) {
                openingQuote = start;
            }
        }

        private void closeQuote(int end) {
            if (lexical && openingQuote >= 0) {
                quote(openingQuote, end);
                openingQuote = -1;
            }
        }

        private void quote(int start, int end) {
            if (lexical) {
                quotes.add(new LineLexicalScanner.Quote(start, end));
            }
        }
    }

    private static final class ScanWork {
        private long remaining;
        private int completedCodeUnits;
        private RubyFrame rubyFrame;

        private ScanWork(int length) {
            remaining = 256L + 8L * length;
        }

        private void at(int cursor) {
            completedCodeUnits = cursor;
            step();
        }

        private void step() {
            if (--remaining < 0) {
                throw new ScanLimitExceeded();
            }
        }
    }

    private static final class ScanLimitExceeded extends RuntimeException {
        private ScanLimitExceeded() {
            super(null, null, false, false);
        }
    }

    private static int indexOf(String line, char needle, int start, ScanWork work) {
        for (int cursor = start; cursor < line.length(); cursor++) {
            work.step();
            if (line.charAt(cursor) == needle) {
                return cursor;
            }
        }
        return -1;
    }

    private int quotedEnd(String line, int start, QuotedString string, ScanWork work) {
        if (syntax == Syntax.RUBY) {
            return rubyQuotedEnd(line, start, string, work);
        }
        boolean escaped = syntax.backslashEscapes(string);
        for (int cursor = start; cursor < line.length(); cursor++) {
            work.step();
            char current = line.charAt(cursor);
            if (escaped && current == '\\') {
                cursor++;
            } else if (current == string.quote() && syntax.doubledQuoteEscapes(string)
                && cursor + 1 < line.length() && line.charAt(cursor + 1) == current) {
                cursor++;
            } else if (current == string.quote()
                && (string.raw() ? rustRawDelimiterAt(line, cursor, string.rawHashes(), work)
                    : !string.triple() || cursor + 2 < line.length()
                        && line.charAt(cursor + 1) == current && line.charAt(cursor + 2) == current)) {
                return cursor + string.delimiterLength();
            }
        }
        return -1;
    }

    private static int rubyQuotedEnd(String line, int start, QuotedString string, ScanWork work) {
        RubyFrame frame = string.rubyFrame() == null
            ? new RubyFrame(string.quote(), 0, null) : string.rubyFrame();
        for (int cursor = start; cursor < line.length(); cursor++) {
            work.step();
            char current = line.charAt(cursor);
            if (current == '\\') {
                cursor++;
            } else if (frame.quote() != 0) {
                if (frame.quote() != '\'' && line.startsWith("#{", cursor)) {
                    frame = new RubyFrame('\0', 1, frame);
                    cursor++;
                } else if (current == frame.quote()) {
                    frame = frame.parent();
                    if (frame == null) {
                        work.rubyFrame = null;
                        return cursor + 1;
                    }
                }
            } else if (current == '#') {
                // A comment in an interpolated expression lasts until the next source line.
                break;
            } else if (current == '\'' || current == '"' || current == '`') {
                frame = new RubyFrame(current, 0, frame);
            } else if (current == '{') {
                frame = new RubyFrame('\0', frame.braces() + 1, frame.parent());
            } else if (current == '}') {
                frame = frame.braces() == 1 ? frame.parent()
                    : new RubyFrame('\0', frame.braces() - 1, frame.parent());
            }
        }
        work.rubyFrame = frame;
        return -1;
    }

    private static QuotedString rustRawStringAt(String line, int start, ScanWork work) {
        if (start > 0 && Character.isUnicodeIdentifierPart(line.codePointBefore(start))) {
            return null;
        }
        int cursor;
        if (line.charAt(start) == 'r') {
            cursor = start + 1;
        } else if (line.startsWith("br", start) || line.startsWith("cr", start)) {
            cursor = start + 2;
        } else {
            return null;
        }
        int hashes = 0;
        while (cursor < line.length() && line.charAt(cursor) == '#') {
            work.step();
            if (++hashes > 255) {
                return null;
            }
            cursor++;
        }
        return cursor < line.length() && line.charAt(cursor) == '"'
            ? new QuotedString('"', false, hashes) : null;
    }

    private static boolean rustRawDelimiterAt(String line, int quote, int hashes, ScanWork work) {
        if (line.length() - quote <= hashes) {
            return false;
        }
        for (int offset = 1; offset <= hashes; offset++) {
            work.step();
            if (line.charAt(quote + offset) != '#') {
                return false;
            }
        }
        return true;
    }

    private static boolean endsWithUnescapedBackslash(String line) {
        int count = 0;
        for (int cursor = line.length() - 1; cursor >= 0 && line.charAt(cursor) == '\\'; cursor--) {
            count++;
        }
        return count % 2 != 0;
    }

    private static boolean yamlSeparation(char character) {
        return character == ' ' || character == '\t';
    }

    private static int yamlPropertyEnd(String line, int start, ScanWork work) {
        if (line.startsWith("!<", start)) {
            int closing = indexOf(line, '>', start + 2, work);
            return closing < 0 ? line.length() : closing + 1;
        }
        int cursor = start + 1;
        while (cursor < line.length() && !yamlSeparation(line.charAt(cursor))
            && ",[]{}".indexOf(line.charAt(cursor)) < 0) {
            work.step();
            cursor++;
        }
        return cursor;
    }

    private static int regexLiteralEnd(String line, int start, ScanWork work) {
        boolean characterClass = false;
        for (int cursor = start + 1; cursor < line.length(); cursor++) {
            work.step();
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

    private static int rustCharacterEnd(String line, int start, ScanWork work) {
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
                int close = indexOf(line, '}', cursor + 1, work);
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
