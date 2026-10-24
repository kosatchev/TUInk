package dev.tuink.highlight.regex;

import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.HighlightRequest;
import dev.tuink.editor.highlight.HighlightResult;
import dev.tuink.editor.highlight.LexicalScanner;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Applies language-specific strings and comments in source order, over ordinary regex rules.
 */
final class CStyleHighlighter extends RegexHighlighter implements ContextualSyntaxHighlighter {
    static final int MAX_CONTEXT_DEPTH = 1024;
    static final int MAX_CONTEXT_NODES_PER_SCAN = 16_384;
    private static final SemanticStyleKey STRING_STYLE = SemanticStyleKey.STRING;
    private static final SemanticStyleKey COMMENT_STYLE = SemanticStyleKey.COMMENT;
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

    private enum JavaScriptGoal {
        STATEMENT, OPERAND, VALUE, ASYNC_VALUE, BODY_KEY, PARAMETERS_END, ARROW_BODY, BLOCK_END;

        boolean permitsRegex() { return this == STATEMENT || this == OPERAND || this == ARROW_BODY; }
    }

    private enum DelimiterKind {
        GROUP(')', JavaScriptGoal.VALUE), CONTROL(')', JavaScriptGoal.STATEMENT),
        ARRAY(']', JavaScriptGoal.VALUE), OBJECT('}', JavaScriptGoal.VALUE),
        BLOCK('}', JavaScriptGoal.BLOCK_END), ARROW('}', JavaScriptGoal.BLOCK_END),
        FUNCTION('}', JavaScriptGoal.VALUE), CONDITIONAL(':', JavaScriptGoal.OPERAND),
        EXPRESSION_BODY('{', JavaScriptGoal.VALUE), DECLARATION_BODY('{', JavaScriptGoal.STATEMENT);

        private final char closing;
        private final JavaScriptGoal after;

        DelimiterKind(char closing, JavaScriptGoal after) {
            this.closing = closing;
            this.after = after;
        }

        boolean pendingBody() { return this == EXPRESSION_BODY || this == DECLARATION_BODY; }
    }

    private record Delimiter(DelimiterKind kind, Delimiter parent, int depth, int cachedHash) {
        private Delimiter(DelimiterKind kind, Delimiter parent) {
            this(kind, parent, parent == null ? 1 : parent.depth + 1,
                31 * (parent == null ? 1 : parent.cachedHash) + kind.hashCode());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Delimiter that) || depth != that.depth || cachedHash != that.cachedHash) {
                return false;
            }
            Delimiter left = this;
            Delimiter right = that;
            while (left != right) {
                if (left == null || right == null || left.kind != right.kind) {
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
            return "Delimiter[kind=" + kind + ", depth=" + depth + "]";
        }
    }

    private record JavaScriptContext(Delimiter delimiters, JavaScriptGoal goal,
                                     boolean controlKeyword, boolean propertyAccess) {
        private static final JavaScriptContext EMPTY =
            new JavaScriptContext(null, JavaScriptGoal.STATEMENT, false, false);
    }

    private record ScanState(
        int blockDepth,
        QuotedString string,
        int yamlFlowDepth,
        int yamlPlainIndent,
        JavaScriptContext javascript,
        boolean markupTag
    )
        implements LexicalScanner.State {
        private static final ScanState EMPTY = new ScanState(0, null, 0, -1, JavaScriptContext.EMPTY, false);
    }

    private final Syntax syntax;
    private final BoundedLineContext<ScanState> contexts = new BoundedLineContext<>(
        ScanState.EMPTY, CStyleHighlighter::stateWeight, this::scanContext);

    CStyleHighlighter(List<RegexRule> rules) {
        this(rules, Syntax.STANDARD);
    }

    CStyleHighlighter(List<RegexRule> rules, Syntax syntax) {
        super(rules);
        this.syntax = syntax;
    }

    @Override
    public List<TokenSpan> highlight(String line) {
        return highlight(line, legacyRequest(line));
    }

    @Override
    public List<TokenSpan> highlight(String line, HighlightRequest request) {
        HighlightWork work = new HighlightWork(request.maxWork());
        try {
            return highlight(line, ScanState.EMPTY, request, request.maxScanCodeUnits(), work);
        } catch (HighlightWork.Exhausted | ScanLimitExceeded ignored) {
            return List.of();
        }
    }

    @Override
    public List<TokenSpan> highlight(DocumentView document, int lineIndex, String line) {
        return highlight(document, lineIndex, line, legacyRequest(line));
    }

    @Override
    public List<TokenSpan> highlight(DocumentView document, int lineIndex, String line, HighlightRequest request) {
        return highlight(document, lineIndex, line, 0, request);
    }

    @Override
    public HighlightResult highlightResult(DocumentView document, int lineIndex, String line, HighlightRequest request) {
        return highlightResult(document, lineIndex, line, 0, request, false);
    }

    @Override
    public LexicalScanner lexicalScanner() {
        return this::scanLexically;
    }

    private LexicalScanner.Result scanLexically(
        String line,
        int maxCodeUnits,
        LexicalScanner.State previous
    ) {
        Objects.requireNonNull(line, "line");
        if (maxCodeUnits < 0) {
            throw new IllegalArgumentException("maxCodeUnits must be non-negative");
        }
        if (previous == LexicalScanner.State.UNKNOWN) {
            return new LexicalScanner.Result(0, previous, List.of(), List.of(), false);
        }
        int limit = Math.min(line.length(), maxCodeUnits);
        String prefix = limit == line.length() ? line : line.substring(0, limit);
        ScanOutput output = new ScanOutput(null, true);
        ScanState state = previous instanceof ScanState prior ? prior : ScanState.EMPTY;
        ScanResult result = scan(prefix, state, output, limit < line.length());
        boolean contextKnown = result.complete() && result.scannedCodeUnits() == line.length();
        if (contextKnown && output.openingQuote >= 0) {
            output.quote(output.openingQuote, -1);
        }
        return new LexicalScanner.Result(result.scannedCodeUnits(), result.endState(),
            output.excludedRanges(), output.quotes, contextKnown);
    }

    // Markdown code fences start a fresh lexical context at their first content line.
    List<TokenSpan> highlight(DocumentView document, int lineIndex, String line, int firstLine) {
        return highlight(document, lineIndex, line, firstLine, legacyRequest(line));
    }

    List<TokenSpan> highlight(DocumentView document, int lineIndex, String line, int firstLine,
        HighlightRequest request) {
        return highlightResult(document, lineIndex, line, firstLine, request, false).spans();
    }

    HighlightResult highlightResult(DocumentView document, int lineIndex, String line, int firstLine,
        HighlightRequest request, boolean enclosingContextAdvanced) {
        if (request.startUtf16() == request.endUtf16() || request.maxSpans() == 0) {
            return HighlightResult.completed(List.of());
        }
        if (request.maxWork() == 0 || request.maxScanCodeUnits() == 0) {
            return new HighlightResult(List.of(), enclosingContextAdvanced);
        }
        if (document == null || line == null
            || firstLine < 0 || lineIndex < firstLine || lineIndex >= document.lineCount()) {
            return HighlightResult.completed(highlight(line, request));
        }
        HighlightWork work = new HighlightWork(request.maxWork());
        var context = contexts.before(document, lineIndex, firstLine, request.maxScanCodeUnits(), work);
        boolean canRetry = !context.permanentlyUnavailable() && (context.advanced() || enclosingContextAdvanced);
        if (!context.complete()) {
            return new HighlightResult(List.of(), canRetry);
        }
        try {
            if (Math.min(line.length(), request.endUtf16()) > context.remainingCodeUnits()) {
                return new HighlightResult(List.of(), canRetry);
            }
            return HighlightResult.completed(
                highlight(line, context.state(), request, context.remainingCodeUnits(), work));
        } catch (HighlightWork.Exhausted ignored) {
            return new HighlightResult(List.of(), canRetry);
        } catch (ScanLimitExceeded ignored) {
            return HighlightResult.completed(List.of());
        }
    }

    private List<TokenSpan> highlight(String line, ScanState state, HighlightRequest request,
        int maxCodeUnits, HighlightWork work) {
        if (line == null || line.isEmpty() || request.startUtf16() == request.endUtf16()
            || request.maxSpans() == 0 || request.maxWork() == 0) {
            return List.of();
        }
        int limit = Math.min(request.scanEnd(line), maxCodeUnits);
        if (limit > 0 && limit < line.length() && Character.isHighSurrogate(line.charAt(limit - 1))
            && Character.isLowSurrogate(line.charAt(limit))) limit--;
        if (request.startUtf16() >= limit || Math.min(line.length(), request.endUtf16()) > limit) return List.of();
        boolean partial = limit < line.length();
        if (partial) work.spend(2L * limit); // Prefix copy and style storage, before either allocation.
        String prefix = partial ? line.substring(0, limit) : line;
        SemanticStyleKey[] styleAt;
        if (partial) {
            // Only the lexer can safely style an unfinished prefix. Ordinary
            // regex rules may rely on an actual EOL or a later delimiter.
            styleAt = new SemanticStyleKey[limit];
        } else {
            styleAt = highlightStyles(prefix, work);
        }
        work.spend(limit); // Lexical paint fills are disjoint and cover at most this prefix.
        ScanResult scanned = scan(prefix, state, new ScanOutput(styleAt, false), partial, work);
        if (!scanned.complete()) throw new ScanLimitExceeded(scanned.stop());
        return spans(styleAt, request, work);
    }

    private ScanState scanContext(String line, int lineIndex, ScanState before, HighlightWork work) {
        ScanResult result = scan(line, before, null, false, work);
        return result.complete() ? result.endState() : null;
    }

    private static int stateWeight(ScanState state) {
        long delimiters = state.javascript().delimiters() == null ? 0 : state.javascript().delimiters().depth();
        long ruby = state.string() == null || state.string().rubyFrame() == null ? 0 : state.string().rubyFrame().depth();
        return (int) Math.min(Integer.MAX_VALUE, 1 + delimiters + ruby);
    }

    private enum ScanStop { COMPLETE, WORK_LIMIT, STATE_LIMIT, OUTPUT_LIMIT }

    private record ScanResult(int scannedCodeUnits, ScanState endState, ScanStop stop) {
        boolean complete() { return stop == ScanStop.COMPLETE; }
    }

    private ScanResult scan(String line, ScanState state, ScanOutput output) {
        return scan(line, state, output, false);
    }

    private ScanResult scan(String line, ScanState state, ScanOutput output, boolean partialLine) {
        return scan(line, state, output, partialLine, null);
    }

    private ScanResult scan(String line, ScanState state, ScanOutput output, boolean partialLine, HighlightWork shared) {
        // Some linear lexer branches skip an identifier or indentation in one
        // step. Charge that traversal before reading or caching its final state.
        if (shared != null) shared.spend(line.length());
        ScanWork work = new ScanWork(line.length(), shared);
        try {
            ScanState after = scanLine(line, state, output, work, partialLine);
            if (output != null) output.finishRange();
            return new ScanResult(line.length(), after, ScanStop.COMPLETE);
        } catch (ScanLimitExceeded stopped) {
            // Retain only proven output, including when a quote's output budget
            // stopped a token after paint but before its closing delimiter.
            if (output != null) output.truncate(work.completedCodeUnits);
            return new ScanResult(work.completedCodeUnits, null, stopped.stop);
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
        JavaScriptGoal goal = state.javascript().goal();
        Delimiter delimiters = state.javascript().delimiters();
        boolean controlKeyword = state.javascript().controlKeyword();
        boolean propertyAccess = state.javascript().propertyAccess();
        boolean restrictedLineEnd = false;
        boolean jumpStatement = false;
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
                            delimiters, goal, controlKeyword, propertyAccess, markupTag) : ScanState.EMPTY;
                }
                paint(output, cursor, end, STRING_STYLE);
                if (output != null) {
                    output.closeQuote(end - string.delimiterLength());
                }
                cursor = end;
                string = null;
                goal = JavaScriptGoal.VALUE;
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
            int regexEnd = current == '/' && syntax == Syntax.BACKTICK && goal.permitsRegex()
                && !line.startsWith("//", cursor) && !line.startsWith("/*", cursor)
                ? regexLiteralEnd(line, cursor, work) : -1;
            QuotedString rawString = syntax == Syntax.RUST ? rustRawStringAt(line, cursor, work) : null;
            if (rawString != null) {
                string = rawString;
                int prefixLength = current == 'r' ? 1 : 2;
                int openingEnd = cursor + prefixLength + string.delimiterLength();
                paint(output, cursor, openingEnd, STRING_STYLE);
                cursor = openingEnd;
                goal = JavaScriptGoal.VALUE;
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
                goal = JavaScriptGoal.VALUE;
                controlKeyword = false;
                propertyAccess = false;
                restrictedLineEnd = false;
                jumpStatement = false;
            } else if (syntax.startsLineComment(line, cursor)) {
                paint(output, cursor, line.length(), COMMENT_STYLE);
                // A comment terminates a YAML plain scalar; a flow collection can continue.
                return scanState(0, null, yamlFlowDepth, -1,
                    delimiters, lineEndGoal(goal, restrictedLineEnd, partialLine),
                    controlKeyword, propertyAccess, markupTag);
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
                goal = JavaScriptGoal.VALUE;
                controlKeyword = false;
                propertyAccess = false;
                restrictedLineEnd = false;
                jumpStatement = false;
            } else if (syntax == Syntax.BACKTICK && Character.isJavaIdentifierStart(current)) {
                int start = cursor++;
                while (cursor < line.length() && Character.isJavaIdentifierPart(line.charAt(cursor))) {
                    cursor++;
                }
                String keyword = line.substring(start, cursor);
                boolean bodyKeyword = !propertyAccess && (keyword.equals("function") || keyword.equals("class"));
                if (bodyKeyword) {
                    // Retain the declaration/expression position through a name,
                    // parameter list or extends clause until its body opens.
                    DelimiterKind body = goal == JavaScriptGoal.OPERAND || goal == JavaScriptGoal.ASYNC_VALUE
                        || goal == JavaScriptGoal.ARROW_BODY ? DelimiterKind.EXPRESSION_BODY : DelimiterKind.DECLARATION_BODY;
                    work.newContextNode(delimiters == null ? 1 : delimiters.depth() + 1);
                    delimiters = new Delimiter(body, delimiters);
                }
                // Property names such as object.if must not start a control condition.
                controlKeyword = !propertyAccess && (CONTROL_CONDITION_KEYWORDS.contains(keyword)
                    || controlKeyword && keyword.equals("await"));
                jumpStatement = !propertyAccess
                    && (jumpStatement || keyword.equals("break") || keyword.equals("continue"));
                restrictedLineEnd = !propertyAccess
                    && (keyword.equals("return") || keyword.equals("debugger") || jumpStatement);
                goal = bodyKeyword ? JavaScriptGoal.BODY_KEY
                    : !propertyAccess && (keyword.equals("else") || keyword.equals("do") || keyword.equals("debugger"))
                    ? JavaScriptGoal.STATEMENT
                    : !propertyAccess && keyword.equals("async") && goal == JavaScriptGoal.OPERAND
                        ? JavaScriptGoal.ASYNC_VALUE
                    : !propertyAccess && REGEX_PREFIX_KEYWORDS.contains(keyword)
                        ? JavaScriptGoal.OPERAND : JavaScriptGoal.VALUE;
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
                    restrictedLineEnd = false;
                    jumpStatement = false;
                    if (delimiters != null && delimiters.kind().pendingBody()
                        && (current == ';' || goal == JavaScriptGoal.BODY_KEY && (current == ':' || current == '='))) {
                        // TS signatures can end without a body. These keywords
                        // can also be object keys or class field names.
                        delimiters = delimiters.parent();
                    }
                    if ((current == '+' || current == '-') && cursor + 1 < line.length()
                        && line.charAt(cursor + 1) == current) {
                        controlKeyword = false;
                        propertyAccess = false;
                        cursor += 2;
                        continue;
                    }
                    if (current == '=' && cursor + 1 < line.length() && line.charAt(cursor + 1) == '>') {
                        goal = JavaScriptGoal.ARROW_BODY;
                        controlKeyword = false;
                        propertyAccess = false;
                        cursor += 2;
                        continue;
                    }
                    if (current == '?' && cursor + 1 < line.length()
                        && (line.charAt(cursor + 1) == '?' || line.charAt(cursor + 1) == '.')) {
                        // Nullish coalescing and optional chaining are not ternaries.
                        goal = line.charAt(cursor + 1) == '?' ? JavaScriptGoal.OPERAND : JavaScriptGoal.VALUE;
                        propertyAccess = line.charAt(cursor + 1) == '.';
                        controlKeyword = false;
                        cursor += 2;
                        continue;
                    }
                    if (current == '(' || current == '[' || current == '{' || current == '?') {
                        DelimiterKind kind;
                        if (current == '{' && delimiters != null && delimiters.kind().pendingBody()) {
                            if (goal == JavaScriptGoal.OPERAND || goal == JavaScriptGoal.ARROW_BODY) {
                                // A TS return annotation can itself contain object
                                // and function types before the actual body.
                                kind = DelimiterKind.OBJECT;
                            } else {
                                kind = delimiters.kind() == DelimiterKind.EXPRESSION_BODY ? DelimiterKind.FUNCTION : DelimiterKind.BLOCK;
                                delimiters = delimiters.parent();
                            }
                        } else {
                            kind = openingDelimiter(current, goal, controlKeyword);
                        }
                        work.newContextNode(delimiters == null ? 1 : delimiters.depth() + 1);
                        delimiters = new Delimiter(kind, delimiters);
                        goal = kind == DelimiterKind.BLOCK || kind == DelimiterKind.ARROW || kind == DelimiterKind.FUNCTION
                            ? JavaScriptGoal.STATEMENT : JavaScriptGoal.OPERAND;
                    } else if (current == ')' || current == ']' || current == '}' || current == ':') {
                        if (delimiters != null && delimiters.kind().closing == current) {
                            goal = delimiters.kind().after;
                            delimiters = delimiters.parent();
                            if (current == ')' && delimiters != null && delimiters.kind().pendingBody()) {
                                goal = JavaScriptGoal.PARAMETERS_END;
                            }
                        } else if (current == ':') {
                            // Object values and parameter/type annotations expect an
                            // operand; labels and switch clauses start a statement.
                            goal = goal == JavaScriptGoal.PARAMETERS_END
                                || delimiters != null && (delimiters.kind() == DelimiterKind.OBJECT
                                || delimiters.kind() == DelimiterKind.GROUP || delimiters.kind() == DelimiterKind.ARRAY)
                                    ? JavaScriptGoal.OPERAND : JavaScriptGoal.STATEMENT;
                        } else {
                            goal = current == '}' ? JavaScriptGoal.STATEMENT : JavaScriptGoal.VALUE;
                        }
                    } else {
                        goal = current == ';' ? JavaScriptGoal.STATEMENT
                            : "=,:!?&|+-*%^~<>/".indexOf(current) >= 0 ? JavaScriptGoal.OPERAND : JavaScriptGoal.VALUE;
                    }
                    controlKeyword = false;
                    propertyAccess = current == '.';
                }
                cursor++;
            }
        }
        // A line terminator after bare return/break/continue ends the statement,
        // even when a block comment spans that terminator. Other values continue
        // across newlines: a following slash can still be division.
        goal = lineEndGoal(goal, restrictedLineEnd, partialLine);
        if (blockDepth > 0) {
            return scanState(blockDepth, null, 0, -1,
                delimiters, goal, controlKeyword, propertyAccess, markupTag);
        }
        if (string != null && syntax.continuesAcrossLines(string, line)) {
            return scanState(0, string, yamlFlowDepth, -1,
                delimiters, goal, controlKeyword, propertyAccess, markupTag);
        }
        return scanState(0, null, yamlFlowDepth, yamlPlainIndent,
            delimiters, goal, controlKeyword, propertyAccess, markupTag);
    }

    private static DelimiterKind openingDelimiter(char opening, JavaScriptGoal goal, boolean controlKeyword) {
        return switch (opening) {
            case '(' -> controlKeyword ? DelimiterKind.CONTROL : DelimiterKind.GROUP;
            case '[' -> DelimiterKind.ARRAY;
            case '?' -> DelimiterKind.CONDITIONAL;
            default -> goal == JavaScriptGoal.ARROW_BODY ? DelimiterKind.ARROW
                : goal == JavaScriptGoal.OPERAND ? DelimiterKind.OBJECT : DelimiterKind.BLOCK;
        };
    }

    private static JavaScriptGoal lineEndGoal(JavaScriptGoal goal, boolean restrictedStatement, boolean partialLine) {
        if (partialLine) return goal;
        if (restrictedStatement || goal == JavaScriptGoal.BLOCK_END) return JavaScriptGoal.STATEMENT;
        // async function requires no intervening line terminator. A standalone
        // async identifier remains a value, but no longer prefixes a body.
        return goal == JavaScriptGoal.ASYNC_VALUE ? JavaScriptGoal.VALUE : goal;
    }

    private ScanState scanState(
        int blockDepth, QuotedString string, int yamlFlowDepth, int yamlPlainIndent,
        Delimiter delimiters, JavaScriptGoal goal, boolean controlKeyword, boolean propertyAccess, boolean markupTag
    ) {
        JavaScriptContext javascript = syntax != Syntax.BACKTICK
            || delimiters == null && goal == JavaScriptGoal.STATEMENT && !controlKeyword && !propertyAccess
                ? JavaScriptContext.EMPTY : new JavaScriptContext(delimiters, goal, controlKeyword, propertyAccess);
        return blockDepth == 0 && string == null && yamlFlowDepth == 0 && yamlPlainIndent < 0
            && javascript == JavaScriptContext.EMPTY && !markupTag
                ? ScanState.EMPTY : new ScanState(blockDepth, string, yamlFlowDepth, yamlPlainIndent, javascript, markupTag);
    }

    private static void paint(ScanOutput output, int start, int end, SemanticStyleKey style) {
        if (output != null) {
            output.paint(start, end, style);
        }
    }

    private static final class ScanOutput {
        private final SemanticStyleKey[] styles;
        private final boolean lexical;
        private final List<LexicalScanner.Range> ranges = new ArrayList<>();
        private final List<LexicalScanner.Quote> quotes = new ArrayList<>();
        private int rangeStart = -1;
        private int rangeEnd;
        private int openingQuote = -1;

        private ScanOutput(SemanticStyleKey[] styles, boolean lexical) {
            this.styles = styles;
            this.lexical = lexical;
        }

        private void paint(int start, int end, SemanticStyleKey style) {
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
                if (ranges.size() == LexicalScanner.MAX_EXCLUDED_RANGES) {
                    throw new ScanLimitExceeded(ScanStop.OUTPUT_LIMIT);
                }
                rangeStart = start;
                rangeEnd = end;
            }
        }

        private void finishRange() {
            if (rangeStart >= 0) {
                ranges.add(new LexicalScanner.Range(rangeStart, rangeEnd));
                rangeStart = -1;
            }
        }

        private List<LexicalScanner.Range> excludedRanges() {
            finishRange();
            return ranges;
        }

        private void openQuote(int start) {
            if (lexical) {
                if (quotes.size() == LexicalScanner.MAX_QUOTES) {
                    throw new ScanLimitExceeded(ScanStop.OUTPUT_LIMIT);
                }
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
                if (quotes.size() == LexicalScanner.MAX_QUOTES) {
                    throw new ScanLimitExceeded(ScanStop.OUTPUT_LIMIT);
                }
                quotes.add(new LexicalScanner.Quote(start, end));
            }
        }

        private void truncate(int end) {
            while (!ranges.isEmpty() && ranges.get(ranges.size() - 1).start() >= end) ranges.remove(ranges.size() - 1);
            if (!ranges.isEmpty() && ranges.get(ranges.size() - 1).end() > end) {
                var last = ranges.remove(ranges.size() - 1);
                ranges.add(new LexicalScanner.Range(last.start(), end));
            }
            if (rangeStart >= end) rangeStart = -1;
            else rangeEnd = Math.min(rangeEnd, end);
            while (!quotes.isEmpty() && (quotes.get(quotes.size() - 1).start() >= end || quotes.get(quotes.size() - 1).end() >= end)) {
                quotes.remove(quotes.size() - 1);
            }
            openingQuote = -1;
            finishRange();
        }
    }

    private static final class ScanWork {
        private long remaining;
        private int completedCodeUnits;
        private RubyFrame rubyFrame;
        private int newContextNodes;
        private final HighlightWork shared;

        private ScanWork(int length, HighlightWork shared) {
            remaining = 256L + 8L * length;
            this.shared = shared;
        }

        private void at(int cursor) {
            completedCodeUnits = cursor;
            step();
        }

        private void step() {
            if (shared != null) shared.spend(1);
            if (--remaining < 0) {
                throw new ScanLimitExceeded();
            }
        }

        private void newContextNode(int depth) {
            if (depth > MAX_CONTEXT_DEPTH || newContextNodes == MAX_CONTEXT_NODES_PER_SCAN) {
                throw new ScanLimitExceeded(ScanStop.STATE_LIMIT);
            }
            newContextNodes++;
        }

        private RubyFrame rubyFrame(char quote, int braces, RubyFrame parent) {
            newContextNode(parent == null ? 1 : parent.depth() + 1);
            return new RubyFrame(quote, braces, parent);
        }
    }

    private static final class ScanLimitExceeded extends RuntimeException {
        private final ScanStop stop;

        private ScanLimitExceeded() {
            this(ScanStop.WORK_LIMIT);
        }

        private ScanLimitExceeded(ScanStop stop) {
            super(null, null, false, false);
            this.stop = stop;
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
            ? work.rubyFrame(string.quote(), 0, null) : string.rubyFrame();
        for (int cursor = start; cursor < line.length(); cursor++) {
            work.step();
            char current = line.charAt(cursor);
            if (current == '\\') {
                cursor++;
            } else if (frame.quote() != 0) {
                if (frame.quote() != '\'' && line.startsWith("#{", cursor)) {
                    frame = work.rubyFrame('\0', 1, frame);
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
                frame = work.rubyFrame(current, 0, frame);
            } else if (current == '{') {
                frame = work.rubyFrame('\0', frame.braces() + 1, frame.parent());
            } else if (current == '}') {
                frame = frame.braces() == 1 ? frame.parent()
                    : work.rubyFrame('\0', frame.braces() - 1, frame.parent());
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
