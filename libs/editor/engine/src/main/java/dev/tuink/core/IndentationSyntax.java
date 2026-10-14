package dev.tuink.core;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/** Same-line comments excluded from the code prefix used by newline indentation. */
final class IndentationSyntax {
    private IndentationSyntax() {
    }

    static String codeBeforeCursor(String line, int column, Path path) {
        String prefix = line.substring(0, column);
        LanguageProfile language = LanguageProfiles.resolve(path);
        if (language == LanguageProfile.PLAIN_TEXT || LanguageProfiles.hasAmbiguousSlashSyntax(path)) {
            return prefix;
        }
        CommentSyntaxProfile comments = LanguageProfiles.resolveCommentSyntax(path);
        if (comments == CommentSyntaxProfile.GENERIC) {
            return prefix;
        }
        boolean slashRegex = LanguageProfiles.supportsSlashRegex(path);
        char[] code = prefix.toCharArray();
        Deque<Boolean> controlParentheses = new ArrayDeque<>();
        boolean closedControlParenthesis = false;
        char quote = 0;
        int quoteLength = 1;
        for (int index = 0; index < prefix.length(); index++) {
            char current = prefix.charAt(index);
            if (quote != 0) {
                if (current == '\\') {
                    index++;
                } else if (current == quote && prefix.startsWith(String.valueOf(quote).repeat(quoteLength), index)) {
                    quote = 0;
                    index += quoteLength - 1;
                }
                continue;
            }
            boolean separated = index == 0 || Character.isWhitespace(prefix.charAt(index - 1));
            if (comments.hashLine() && current == '#' && (language == LanguageProfile.PYTHON || separated)
                || comments.semicolonLine() && current == ';' && separated
                || comments.slashLine() && prefix.startsWith("//", index)
                || comments.dashDashLine() && prefix.startsWith("--", index)) {
                Arrays.fill(code, index, code.length, ' ');
                break;
            }
            String closing = comments.slashBlock() && prefix.startsWith("/*", index) ? "*/"
                : comments.htmlBlock() && prefix.startsWith("<!--", index) ? "-->" : null;
            if (closing != null) {
                int end = prefix.indexOf(closing, index + (closing.equals("*/") ? 2 : 4));
                int after = end < 0 ? prefix.length() : end + closing.length();
                Arrays.fill(code, index, after, ' ');
                index = after - 1;
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                quoteLength = (language == LanguageProfile.PYTHON || current == '"' && comments.slashLine())
                    && prefix.startsWith(String.valueOf(current).repeat(3), index) ? 3 : 1;
                index += quoteLength - 1;
            } else if (slashRegex && current == '(') {
                controlParentheses.push(controlBeforeParenthesis(code, index));
            } else if (slashRegex && current == ')') {
                closedControlParenthesis = !controlParentheses.isEmpty() && controlParentheses.pop();
            } else if (slashRegex && current == '/' && mayStartRegex(code, index, closedControlParenthesis)) {
                // A slash can start a literal or divide expressions. Preserve the
                // original prefix when a candidate literal cannot be completed.
                int end = regexEnd(prefix, index + 1);
                if (end < 0) {
                    return prefix;
                }
                index = end;
            }
        }
        return new String(code);
    }

    /** Blanks completed ordinary literals for structural lookups; null means uncertain context. */
    static String maskOrdinaryLiterals(String code) {
        char[] masked = code.toCharArray();
        for (int index = 0; index < code.length(); index++) {
            char quote = code.charAt(index);
            if (quote == '`' || quote == '\\') {
                return null;
            }
            if (quote != '\'' && quote != '"') {
                continue;
            }
            if (index + 2 < code.length() && code.charAt(index + 1) == quote && code.charAt(index + 2) == quote) {
                return null;
            }
            int start = index++;
            while (index < code.length() && code.charAt(index) != quote) {
                index += code.charAt(index) == '\\' ? 2 : 1;
            }
            if (index >= code.length()) {
                return null;
            }
            String literal = code.substring(start, index + 1);
            if (literal.contains("/*") || literal.contains("*/") || literal.contains("<!--") || literal.contains("-->")) {
                // Raw comment-boundary checks must not mistake a literal for a closing marker.
                return null;
            }
            Arrays.fill(masked, start, index + 1, ' ');
        }
        return new String(masked);
    }

    private static int regexEnd(String text, int start) {
        boolean characterClass = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\\') {
                index++;
            } else if (current == '[') {
                characterClass = true;
            } else if (current == ']') {
                characterClass = false;
            } else if (current == '/' && !characterClass) {
                return index;
            }
        }
        return -1;
    }

    private static boolean mayStartRegex(char[] code, int slash, boolean closedControlParenthesis) {
        int previous = previousNonWhitespace(code, slash);
        if (previous >= 0 && code[previous] == ')') {
            return closedControlParenthesis;
        }
        if (previous < 0 || "=([{,:;!&|?+-*%^~<>}".indexOf(code[previous]) >= 0) {
            return true;
        }
        int start = identifierStart(code, previous + 1);
        int beforeWord = previousNonWhitespace(code, start);
        if (beforeWord >= 0 && code[beforeWord] == '.') {
            return false;
        }
        return switch (new String(code, start, previous + 1 - start)) {
            case "return", "throw", "case", "delete", "typeof", "void", "new", "yield", "await",
                "in", "instanceof", "of", "else", "do" -> true;
            default -> false;
        };
    }

    private static boolean controlBeforeParenthesis(char[] code, int parenthesis) {
        int end = previousNonWhitespace(code, parenthesis) + 1;
        int start = identifierStart(code, end);
        String word = new String(code, start, end - start);
        if (word.equals("await")) {
            end = previousNonWhitespace(code, start) + 1;
            start = identifierStart(code, end);
            word = new String(code, start, end - start);
            if (!word.equals("for")) {
                return false;
            }
        }
        int beforeWord = previousNonWhitespace(code, start);
        if (beforeWord >= 0 && code[beforeWord] == '.') {
            return false;
        }
        return switch (word) {
            case "if", "while", "for", "with", "switch", "catch" -> true;
            default -> false;
        };
    }

    private static int previousNonWhitespace(char[] code, int before) {
        int previous = before - 1;
        while (previous >= 0 && Character.isWhitespace(code[previous])) {
            previous--;
        }
        return previous;
    }

    private static int identifierStart(char[] code, int end) {
        int start = end;
        while (start > 0 && (Character.isUnicodeIdentifierPart(code[start - 1]) || code[start - 1] == '$')) {
            start--;
        }
        return start;
    }
}
