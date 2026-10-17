package dev.tuink.core;

import java.util.Arrays;

/** Conservative structural checks over the code prefixes supplied by the lexical context. */
final class IndentationSyntax {
    private IndentationSyntax() {
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

}
