/*
 * Width ranges derived from org.jline.utils.WCWidth.
 * Copyright (c) 2002-2025, the original author(s).
 * Licensed under BSD-3-Clause; see THIRD_PARTY_NOTICES.md.
 */
package dev.tuink.terminal.text;

/**
 * JLine 3.30.6-compatible width policy for a visible grapheme base.
 *
 * <p>The caller filters controls, malformed UTF-16, combining marks and the
 * grapheme formatting code points handled by {@link TerminalText}.</p>
 */
final class TerminalCellWidth {
    private static final int SOFT_HYPHEN = 0x00AD;
    private static final int ARABIC_START_OF_RUB_EL_HIZB = 0x06DE;

    private TerminalCellWidth() {
    }

    static int visibleBaseWidth(int codePoint) {
        if (codePoint == ARABIC_START_OF_RUB_EL_HIZB
            || codePoint >= 0x1160 && codePoint <= 0x11FF) {
            return 0;
        }
        if (Character.getType(codePoint) == Character.FORMAT
            && codePoint != SOFT_HYPHEN
            && !isLegacyFormatGap(codePoint)) {
            return 0;
        }
        return isWide(codePoint) ? 2 : 1;
    }

    /**
     * Format characters added after the compatibility table used by JLine.
     * JLine 3.30.6 treats these as one cell wide.
     */
    private static boolean isLegacyFormatGap(int codePoint) {
        return codePoint >= 0x0604 && codePoint <= 0x0605
            || codePoint == 0x061C
            || codePoint == 0x08E2
            || codePoint == 0x180E
            || codePoint == 0x2064
            || codePoint >= 0x2066 && codePoint <= 0x2069
            || codePoint == 0x110BD
            || codePoint == 0x110CD
            || codePoint >= 0x13430 && codePoint <= 0x13438
            || codePoint >= 0x1BCA0 && codePoint <= 0x1BCA3;
    }

    private static boolean isWide(int codePoint) {
        return codePoint >= 0x1100
            && (codePoint <= 0x115F
                || codePoint == 0x2329
                || codePoint == 0x232A
                || codePoint >= 0x2E80 && codePoint <= 0xA4CF && codePoint != 0x303F
                || codePoint >= 0xAC00 && codePoint <= 0xD7A3
                || codePoint >= 0xF900 && codePoint <= 0xFAFF
                || codePoint >= 0xFE10 && codePoint <= 0xFE19
                || codePoint >= 0xFE30 && codePoint <= 0xFE6F
                || codePoint >= 0xFF00 && codePoint <= 0xFF60
                || codePoint >= 0xFFE0 && codePoint <= 0xFFE6
                || codePoint >= 0x1F000 && codePoint <= 0x1FEEE
                || codePoint >= 0x20000 && codePoint <= 0x2FFFD
                || codePoint >= 0x30000 && codePoint <= 0x3FFFD);
    }
}
