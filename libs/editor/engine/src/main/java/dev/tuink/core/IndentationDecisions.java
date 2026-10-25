package dev.tuink.core;

import dev.tuink.unicode.GraphemeText;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.IntFunction;

/** Computes indentation choices without changing document text, view state or history. */
final class IndentationDecisions {
    private static final int ASSIST_LOOKBACK_LINES = 256;
    private static final int ASSIST_LOOKBACK_CHARS = 16_384;
    private final int lineCount;
    private final IntFunction<? extends CharSequence> lines;
    private final String file;
    private final int tabWidth;
    private final TabInsertMode tabInsertMode;
    private final IndentationContext syntax;

    IndentationDecisions(int lineCount, IntFunction<? extends CharSequence> lines, String file, int tabWidth, TabInsertMode tabInsertMode) {
        this(lineCount, lines, file, tabWidth, tabInsertMode, initialContext(lineCount, lines, file));
    }

    IndentationDecisions(int lineCount, IntFunction<? extends CharSequence> lines, String file, int tabWidth,
                         TabInsertMode tabInsertMode, IndentationContext syntax) {
        this.lineCount = lineCount;
        this.lines = lines;
        this.file = file;
        this.tabWidth = tabWidth;
        this.tabInsertMode = tabInsertMode;
        this.syntax = syntax;
    }

    private static IndentationContext initialContext(int count, IntFunction<? extends CharSequence> lines, String file) {
        IndentationContext context = new IndentationContext(lines);
        context.synchronize(0, 0, 0, count, file);
        return context;
    }

    String newLineIndent(Position cursor) {
        CharSequence line = lines.apply(cursor.line());
        int col = Math.max(0, Math.min(cursor.col(), line.length()));
        int prefixLimit = Math.min(col, IndentationContext.MAX_SCAN_CODE_UNITS);
        int leading = 0;
        while (leading < prefixLimit && Character.isWhitespace(line.charAt(leading))) {
            leading++;
        }
        if (leading == prefixLimit && col > prefixLimit) {
            return "";
        }
        int keep = Math.min(leading, col);
        String baseIndent = keep <= 0 ? "" : line.subSequence(0, keep).toString();
        String code = syntax.codeBeforeCursor(cursor.line(), col);
        if (code == null) {
            return baseIndent;
        }
        if (shouldDedentFromClosing(cursor.line(), line, col, baseIndent)) {
            baseIndent = removeUnit(baseIndent, cursor.line());
        }
        if (shouldIncreaseIndent(code)) {
            return baseIndent + unit(cursor.line(), baseIndent);
        }
        return baseIndent;
    }

    private boolean shouldDedentFromClosing(int lineIndex, CharSequence line, int col, String currentIndent) {
        if (line.length() == 0 || currentIndent.isEmpty() || !LanguageProfiles.resolve(file).bracketIndentEnabled()) {
            return false;
        }
        int safeCol = Math.max(0, Math.min(col, line.length()));
        if (safeCol <= leadingIndentLength(line, safeCol)) {
            return false;
        }
        String before = syntax.codeBeforeCursor(lineIndex, safeCol);
        if (before == null || !startsWithStandaloneClosing(before)) {
            return false;
        }
        Deque<Character> pending = new ArrayDeque<>();
        for (int index = before.length() - 1; index >= 0; index--) {
            if (isClosingBracket(before.charAt(index))) {
                pending.push(before.charAt(index));
            }
        }
        int remainingChars = ASSIST_LOOKBACK_CHARS;
        int firstLine = Math.max(0, lineIndex - ASSIST_LOOKBACK_LINES);
        for (int previous = lineIndex - 1; previous >= firstLine; previous--) {
            CharSequence text = lines.apply(previous);
            remainingChars -= text.length();
            if (remainingChars < 0) {
                return false;
            }
            String code = syntax.codeBeforeCursor(previous, text.length());
            // The context must prove both the lexical boundary and the
            // structural match before removing user-supplied whitespace.
            code = code == null ? null : IndentationSyntax.maskOrdinaryLiterals(code);
            if (code == null || code.indexOf('/') >= 0) {
                return false;
            }
            for (int index = code.length() - 1; index >= 0; index--) {
                char ch = code.charAt(index);
                if (isClosingBracket(ch)) {
                    pending.push(ch);
                } else if (ch == '(' || ch == '[' || ch == '{') {
                    if (pending.isEmpty() || !isMatchingPair(ch, pending.pop())) {
                        return false;
                    }
                    if (pending.isEmpty()) {
                        String openerIndent = text.subSequence(0, leadingIndentLength(text)).toString();
                        return currentIndent.length() > openerIndent.length() && currentIndent.startsWith(openerIndent)
                            && syntax.hasCertainContextBefore(previous, index);
                    }
                }
            }
        }
        return false;
    }

    private boolean shouldIncreaseIndent(String code) {
        LanguageProfile profile = LanguageProfiles.resolve(file);
        String before = code.stripTrailing();
        if (before.isEmpty()) {
            return false;
        }
        char last = before.charAt(before.length() - 1);
        if (profile.bracketIndentEnabled() && (last == '{' || last == '[' || last == '(')) {
            return true;
        }
        if (last != ':') {
            return false;
        }
        return switch (profile.colonIndentMode()) {
            case PYTHON -> isPythonBlockIntro(before);
            case YAML -> isYamlBlockIntro(before);
            default -> false;
        };
    }

    private boolean isPythonBlockIntro(String before) {
        if (!before.endsWith(":") || before.endsWith("::")) {
            return false;
        }
        String head = before.substring(0, before.length() - 1).strip();
        if (head.isEmpty() || head.endsWith(";")) {
            return false;
        }
        boolean async = head.startsWith("async") && head.length() > 5
            && Character.isWhitespace(head.charAt(5));
        if (async) {
            head = head.substring(5).stripLeading();
        }
        int end = 0;
        while (end < head.length() && Character.isUnicodeIdentifierPart(head.charAt(end))) {
            end++;
        }
        String keyword = head.substring(0, end);
        if (async && !keyword.equals("def") && !keyword.equals("for") && !keyword.equals("with")) {
            return false;
        }
        boolean hasExpression = end < head.length()
            && (Character.isWhitespace(head.charAt(end)) || head.charAt(end) == '(');
        return switch (keyword) {
            case "if", "elif", "for", "while", "def", "class", "with", "match", "case" -> hasExpression;
            case "except" -> end == head.length() || hasExpression || head.charAt(end) == '*';
            case "else", "try", "finally" -> end == head.length();
            default -> false;
        };
    }

    private boolean isYamlBlockIntro(String before) {
        if (!before.endsWith(":")) {
            return false;
        }
        String head = before.substring(0, before.length() - 1).stripTrailing();
        if (head.isEmpty() || head.startsWith("#")) {
            return false;
        }
        // Allow list-style mapping items: "- key:"
        if (head.startsWith("-")) {
            head = head.substring(1).stripLeading();
            if (head.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWithStandaloneClosing(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int first = firstNonWhitespaceColumn(text, new AssistScanBudget());
        if (first >= text.length()) {
            return false;
        }
        if (!isClosingBracket(text.charAt(first))) {
            return false;
        }
        for (int i = first + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (isClosingBracket(ch) || ch == ';' || ch == ',') {
                continue;
            }
            if (startsCommentAt(text, i)) {
                return true;
            }
            return false;
        }
        return true;
    }

    private static boolean startsCommentAt(String text, int at) {
        return text.startsWith("//", at)
            || text.startsWith("#", at)
            || text.startsWith("/*", at);
    }

    private static boolean isClosingBracket(char ch) {
        return ch == '}' || ch == ']' || ch == ')';
    }

    String unit(int lineIndex, String currentIndent) {
        return switch (tabInsertMode) {
            case ALWAYS_TAB -> "\t";
            case ALWAYS_SPACES_4 -> "    ";
            case ALWAYS_SPACES_2 -> "  ";
            case AUTO -> autoIndentUnit(lineIndex, currentIndent);
        };
    }

    private String autoIndentUnit(int lineIndex, String currentIndent) {
        if (currentIndent.length() > IndentationContext.MAX_SCAN_CODE_UNITS) {
            currentIndent = "";
        }
        int tabs = 0;
        int spaces = 0;
        for (int i = 0; i < currentIndent.length(); i++) {
            char ch = currentIndent.charAt(i);
            if (ch == '\t') {
                tabs++;
            } else if (ch == ' ') {
                spaces++;
            }
        }
        if (tabs > 0 && spaces == 0) {
            return "\t";
        }
        if (tabs > 0 && spaces > 0) {
            return currentIndent.charAt(currentIndent.length() - 1) == '\t'
                ? "\t"
                : " ".repeat(detectIndentSize(lineIndex, currentIndent));
        }
        if (preferTabsByContext(lineIndex)) {
            return "\t";
        }
        return " ".repeat(detectIndentSize(lineIndex, currentIndent));
    }

    private int detectIndentSize(int lineIndex, String currentIndent) {
        int fallback = tabWidth <= 2 ? 2 : 4;
        int score2 = 0;
        int score4 = 0;
        int observed = 0;
        int prevIndent = -1;
        int start = Math.max(0, lineIndex - 256);
        int end = Math.min(lineCount - 1, lineIndex);
        AssistScanBudget budget = new AssistScanBudget();
        for (int i = start; i <= end; i++) {
            CharSequence text = lines.apply(i);
            int leadingSpaces = leadingSpacesOnly(text, budget);
            if (budget.exhausted()) break;
            if (leadingSpaces < 0 || leadingSpaces >= text.length()) {
                continue;
            }
            if (leadingSpaces > 0) {
                observed++;
                if (leadingSpaces % 2 == 0) {
                    score2++;
                }
                if (leadingSpaces % 4 == 0) {
                    score4++;
                }
            }
            if (prevIndent >= 0) {
                int delta = leadingSpaces - prevIndent;
                if (delta > 0) {
                    observed++;
                    if (delta % 2 == 0) {
                        score2++;
                    }
                    if (delta % 4 == 0) {
                        score4++;
                    }
                }
            }
            prevIndent = leadingSpaces;
        }
        if (observed > 0) {
            if (score4 > 0 && score4 * 2 >= observed) {
                return 4;
            }
            if (score2 > 0) {
                return 2;
            }
        }
        int trailingSpaces = trailingSpaces(currentIndent);
        if (trailingSpaces >= 4 && trailingSpaces % 4 == 0) {
            return 4;
        }
        if (trailingSpaces >= 2 && trailingSpaces % 2 == 0) {
            return 2;
        }
        int docFallback = detectIndentFromDocumentStart();
        if (docFallback > 0) {
            return docFallback;
        }
        return fallback;
    }

    private int detectIndentFromDocumentStart() {
        int score2 = 0;
        int score4 = 0;
        int observed = 0;
        int limit = Math.min(lineCount, 256);
        AssistScanBudget budget = new AssistScanBudget();
        for (int i = 0; i < limit; i++) {
            CharSequence text = lines.apply(i);
            int leadingSpaces = leadingSpacesOnly(text, budget);
            if (budget.exhausted()) break;
            if (leadingSpaces <= 0 || leadingSpaces >= text.length()) {
                continue;
            }
            observed++;
            if (leadingSpaces % 2 == 0) {
                score2++;
            }
            if (leadingSpaces % 4 == 0) {
                score4++;
            }
        }
        if (observed == 0) {
            return -1;
        }
        if (score4 > 0 && score4 * 2 >= observed) {
            return 4;
        }
        if (score2 > 0) {
            return 2;
        }
        return -1;
    }

    private boolean preferTabsByContext(int lineIndex) {
        int tabIndented = 0;
        int spaceIndented = 0;
        int start = Math.max(0, lineIndex - 256);
        int end = Math.min(lineCount - 1, lineIndex + 256);
        AssistScanBudget budget = new AssistScanBudget();
        for (int i = start; i <= end; i++) {
            CharSequence text = lines.apply(i);
            if (text.length() == 0 || (text.charAt(0) != '\t' && text.charAt(0) != ' ')) {
                continue;
            }
            int first = firstNonWhitespaceColumn(text, budget);
            if (budget.exhausted()) break;
            if (first <= 0 || first >= text.length()) {
                continue;
            }
            char indentLead = text.charAt(0);
            if (indentLead == '\t') {
                tabIndented++;
            } else if (indentLead == ' ') {
                spaceIndented++;
            }
        }
        return tabIndented > spaceIndented && tabIndented > 0;
    }

    private static int leadingSpacesOnly(CharSequence line, AssistScanBudget budget) {
        int spaces = 0;
        while (spaces < line.length()) {
            if (!budget.readUnit()) return -1;
            char ch = line.charAt(spaces);
            if (ch == ' ') {
                spaces++;
                continue;
            }
            if (ch == '\t') {
                return -1;
            }
            break;
        }
        return spaces;
    }

    private static int trailingSpaces(String text) {
        int spaces = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            if (text.charAt(i) != ' ') {
                break;
            }
            spaces++;
        }
        return spaces;
    }

    String removeUnit(String indent, int lineIndex) {
        if (indent.isEmpty()) {
            return indent;
        }
        if (tabInsertMode == TabInsertMode.ALWAYS_TAB || indent.charAt(indent.length() - 1) == '\t') {
            return indent.substring(0, indent.length() - 1);
        }
        int spacesInUnit = switch (tabInsertMode) {
            case ALWAYS_SPACES_4 -> 4;
            case ALWAYS_SPACES_2 -> 2;
            default -> detectIndentSize(lineIndex, indent);
        };
        return removeSpacesIndentUnit(indent, spacesInUnit);
    }

    private static String removeSpacesIndentUnit(String indent, int spacesInUnit) {
        if (indent.isEmpty()) {
            return indent;
        }
        int end = indent.length();
        char last = indent.charAt(end - 1);
        if (last == '\t') {
            return indent.substring(0, end - 1);
        }
        int spacesToDrop = 0;
        while (end - spacesToDrop - 1 >= 0
            && indent.charAt(end - spacesToDrop - 1) == ' '
            && spacesToDrop < spacesInUnit) {
            spacesToDrop++;
        }
        if (spacesToDrop > 0) {
            return indent.substring(0, end - spacesToDrop);
        }
        return indent.substring(0, end - 1);
    }

    /** Number of leading UTF-16 characters to remove after the triggering input has been inserted. */
    int keywordOutdent(Position cursor) {
        LanguageProfile profile = LanguageProfiles.resolve(file);
        if (!profile.keywordDedentEnabled()) {
            return 0;
        }
        int lineIndex = cursor.line();
        CharSequence line = lines.apply(lineIndex);
        int cursorCol = Math.max(0, Math.min(cursor.col(), line.length()));
        if (cursorCol > IndentationContext.MAX_SCAN_CODE_UNITS) {
            return 0;
        }
        int leading = leadingIndentLength(line, cursorCol);
        if (leading <= 0 || cursorCol <= leading) {
            return 0;
        }
        String currentIndent = line.subSequence(0, leading).toString();
        String code = syntax.codeBeforeCursor(lineIndex, cursorCol);
        if (code == null || !startsWithDedentKeyword(code, leading, cursorCol, profile)) {
            return 0;
        }
        String reduced = removeUnit(currentIndent, lineIndex);
        int drop = currentIndent.length() - reduced.length();
        String keyword = leadingKeyword(code.substring(leading));
        if (drop <= 0 || !(profile == LanguageProfile.PYTHON
            ? shouldDedentPythonClause(lineIndex, keyword, currentIndent, reduced)
            : shouldDedentCodeClause(lineIndex, keyword, currentIndent, reduced))) {
            return 0;
        }
        return drop;
    }

    private boolean shouldDedentPythonClause(int lineIndex, String keyword, String currentIndent, String reduced) {
        int firstLine = Math.max(0, lineIndex - ASSIST_LOOKBACK_LINES);
        int remainingChars = ASSIST_LOOKBACK_CHARS;
        for (int previous = lineIndex - 1; previous >= firstLine; previous--) {
            CharSequence text = lines.apply(previous);
            remainingChars -= text.length();
            if (remainingChars < 0) {
                return false;
            }
            String code = syntax.codeBeforeCursor(previous, text.length());
            if (code == null) {
                return false;
            }
            code = code.stripTrailing();
            if (code.isBlank()) {
                continue;
            }
            if (code == null || IndentationSyntax.maskOrdinaryLiterals(code) == null) {
                return false;
            }
            int leading = leadingIndentLength(text);
            if (leading > currentIndent.length()) {
                continue;
            }
            String indent = text.subSequence(0, leading).toString();
            if (!currentIndent.startsWith(indent) || leading < reduced.length()) {
                return false;
            }
            String header = code.substring(leading);
            String owner = leadingKeyword(header);
            if (owner.equals("async")) {
                header = header.substring(owner.length()).stripLeading();
                owner = leadingKeyword(header);
            }
            if (owner.equals("def") || owner.equals("class")) {
                return false;
            }
            int colon = pythonClauseColon(header);
            if (colon < 0 || !isPythonBlockIntro(header.substring(0, colon + 1))) {
                if (leading < currentIndent.length() || switch (owner) {
                    case "if", "elif", "else", "for", "while", "with", "try", "except", "finally" -> true;
                    default -> false;
                }) {
                    return false;
                }
                continue;
            }
            boolean compatible = switch (keyword) {
                case "elif" -> owner.equals("if") || owner.equals("elif");
                case "else" -> owner.equals("if") || owner.equals("elif") || owner.equals("for")
                    || owner.equals("while") || owner.equals("try") || owner.equals("except");
                case "except", "finally" -> owner.equals("try") || owner.equals("except");
                case "case" -> owner.equals("case") && !header.substring(owner.length()).stripLeading().startsWith("=");
                default -> false;
            };
            if (compatible) {
                // A clause aligned with its owner or an existing sibling needs no correction.
                return leading == reduced.length()
                    && syntax.hasCertainContextBefore(previous, leading);
            }
            if (!keyword.equals("finally") || !owner.equals("else") || leading != reduced.length()) {
                return false;
            }
        }
        return false;
    }

    private static int pythonClauseColon(String header) {
        char quote = 0;
        Deque<Character> brackets = new ArrayDeque<>();
        for (int index = 0; index < header.length(); index++) {
            char ch = header.charAt(index);
            if (quote != 0) {
                if (ch == '\\') {
                    index++;
                } else if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
            } else if (ch == '(' || ch == '[' || ch == '{') {
                brackets.push(ch);
            } else if (isClosingBracket(ch)) {
                if (brackets.isEmpty() || !isMatchingPair(brackets.pop(), ch)) {
                    return -1;
                }
            } else if (ch == ':' && brackets.isEmpty()) {
                return index;
            }
        }
        return -1;
    }

    private boolean shouldDedentCodeClause(int lineIndex, String keyword, String currentIndent, String reduced) {
        int firstLine = Math.max(0, lineIndex - ASSIST_LOOKBACK_LINES);
        int remainingChars = ASSIST_LOOKBACK_CHARS;
        int closedBraces = 0;
        boolean label = keyword.equals("case") || keyword.equals("default");
        for (int previous = lineIndex - 1; previous >= firstLine; previous--) {
            CharSequence text = lines.apply(previous);
            remainingChars -= text.length();
            if (remainingChars < 0) {
                return false;
            }
            String code = syntax.codeBeforeCursor(previous, text.length());
            if (code == null) {
                return false;
            }
            code = code.stripTrailing();
            if (code.isBlank()) {
                continue;
            }
            if (!label) {
                return text.subSequence(0, leadingIndentLength(text)).toString().equals(currentIndent)
                    && code.stripLeading().startsWith("}") && startsWithStandaloneClosing(code)
                    && shouldDedentFromClosing(previous, text, text.length(), currentIndent);
            }
            code = code == null ? null : IndentationSyntax.maskOrdinaryLiterals(code);
            if (code == null || code.indexOf('/') >= 0) {
                return false;
            }
            for (int index = code.length() - 1; index >= 0; index--) {
                if (code.charAt(index) == '}') {
                    closedBraces++;
                } else if (code.charAt(index) == '{') {
                    if (closedBraces == 0) {
                        return false;
                    }
                    closedBraces--;
                }
            }
            int leading = leadingIndentLength(text);
            if (closedBraces > 0 || leading > currentIndent.length()) {
                continue;
            }
            String indent = text.subSequence(0, leading).toString();
            if (!currentIndent.startsWith(indent) || leading < reduced.length()) {
                return false;
            }
            String owner = leadingKeyword(code.substring(leading));
            if (owner.equals("case") || owner.equals("default")) {
                return leading == reduced.length()
                    && syntax.hasCertainContextBefore(previous, leading);
            }
        }
        return false;
    }

    private static String leadingKeyword(String text) {
        int end = 0;
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end);
    }

    private boolean startsWithDedentKeyword(String line, int leading, int cursorCol, LanguageProfile profile) {
        int tokenEnd = leading;
        while (tokenEnd < line.length() && isWordChar(line.charAt(tokenEnd))) {
            tokenEnd++;
        }
        // A token still being typed may grow into an identifier such as "elsewhere".
        if (tokenEnd <= leading || cursorCol <= tokenEnd) {
            return false;
        }
        String keyword = line.substring(leading, tokenEnd);
        if (!profile.dedentKeywords().contains(keyword)) {
            return false;
        }
        if (profile == LanguageProfile.PYTHON && keyword.equals("case")) {
            String before = line.substring(leading, cursorCol).stripTrailing();
            return !before.substring(keyword.length()).stripLeading().startsWith("=") && isPythonBlockIntro(before);
        }
        for (int i = tokenEnd; i < cursorCol; i++) {
            char ch = line.charAt(i);
            if (!Character.isWhitespace(ch)) {
                return i == cursorCol - 1 && (ch == ':' || ch == '('
                    || (profile == LanguageProfile.GENERIC_CODE && ch == '{')
                    || (profile == LanguageProfile.PYTHON && keyword.equals("except") && ch == '*'));
            }
        }
        return true;
    }

    private static int leadingIndentLength(CharSequence line) {
        return leadingIndentLength(line, line.length());
    }

    private static int leadingIndentLength(CharSequence line, int end) {
        int i = 0;
        while (i < end) {
            char ch = line.charAt(i);
            if (ch != ' ' && ch != '\t') {
                break;
            }
            i++;
        }
        return i;
    }

    private int firstNonWhitespaceColumn(CharSequence line, AssistScanBudget budget) {
        for (int offset = 0; offset < line.length();) {
            if (!budget.readUnit()) return 0;
            int codePoint = Character.codePointAt(line, offset);
            int next = offset + Character.charCount(codePoint);
            if (next > offset + 1 && !budget.readUnit()) return 0;
            if (!Character.isWhitespace(codePoint)) {
                if (offset == 0) {
                    return 0;
                }
                // A combining mark can make the preceding space part of a
                // non-whitespace grapheme. Its boundary needs only this prefix;
                // segmenting the rest of a neighbouring line can be very costly.
                return GraphemeText.of(line.subSequence(0, next)).floorBoundary(offset);
            }
            offset = next;
        }
        return 0;
    }

    /** Shared across neighbouring lines, so long whitespace prefixes cannot multiply work. */
    private static final class AssistScanBudget {
        private int remaining = ASSIST_LOOKBACK_CHARS;
        private boolean exhausted;

        private boolean readUnit() {
            if (remaining == 0) {
                exhausted = true;
                return false;
            }
            remaining--;
            return true;
        }

        private boolean exhausted() {
            return exhausted;
        }
    }

    private static boolean isMatchingPair(char left, char right) {
        return (left == '(' && right == ')')
            || (left == '[' && right == ']')
            || (left == '{' && right == '}')
            || (left == '"' && right == '"')
            || (left == '\'' && right == '\'');
    }

    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }
}
