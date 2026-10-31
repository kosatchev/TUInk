package dev.tuiframe.text;

/** Visible, single-line presentation of external text in terminal diagnostics and notifications. */
public final class TerminalPlainText {
    private static final String HEX = "0123456789ABCDEF";

    private TerminalPlainText() {}

    /**
     * Escapes terminal controls, line separators, bidi controls and unpaired UTF-16
     * surrogates. Other Unicode text and literal backslashes remain unchanged, so
     * presenting an already escaped value does not escape it again.
     */
    public static String singleLine(String text) {
        if (text == null) return "";
        StringBuilder escaped = null;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character) && index + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(index + 1))) {
                if (escaped != null) escaped.append(character).append(text.charAt(index + 1));
                index++;
                continue;
            }
            if (!needsEscape(character)) {
                if (escaped != null) escaped.append(character);
                continue;
            }
            if (escaped == null) {
                escaped = new StringBuilder(text.length());
                escaped.append(text, 0, index);
            }
            switch (character) {
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append("\\u")
                    .append(HEX.charAt((character >>> 12) & 15))
                    .append(HEX.charAt((character >>> 8) & 15))
                    .append(HEX.charAt((character >>> 4) & 15))
                    .append(HEX.charAt(character & 15));
            }
        }
        return escaped == null ? text : escaped.toString();
    }

    private static boolean needsEscape(char character) {
        return Character.isISOControl(character) || Character.isSurrogate(character)
            || character == '\u2028' || character == '\u2029'
            || character == '\u061C' || character == '\u200E' || character == '\u200F'
            || character >= '\u202A' && character <= '\u202E'
            || character >= '\u2066' && character <= '\u2069';
    }
}
