/*
 * Width ranges derived from org.jline.utils.WCWidth.
 * Copyright (c) 2002-2025, the original author(s).
 * Licensed under BSD-3-Clause; see THIRD_PARTY_NOTICES.md.
 */
package dev.tuiframe.text;

/**
 * JLine 3.30.6-derived width policy for a visible grapheme base.
 *
 * <p>The caller filters controls, malformed UTF-16, combining marks and the
 * grapheme formatting code points handled by {@link TerminalText}.</p>
 */
final class TerminalCellWidth {
    private TerminalCellWidth() {
    }

    static int visibleBaseWidth(int codePoint) {
        if (isZeroWidthBase(codePoint)) {
            return 0;
        }
        return isWide(codePoint) ? 2 : 1;
    }

    /**
     * Non-combining zero-width bases from JLine 3.30.6's fixed width table,
     * completed with Arabic letter mark, invisible plus and directional isolates.
     * Standalone zero-width clusters are displayed as visible placeholders by
     * TerminalText instead of being emitted with the terminal's zero advance.
     * Keep these ranges independent of the JDK's Unicode character categories;
     * new format characters do not change the terminal backend's width policy.
     */
    private static boolean isZeroWidthBase(int codePoint) {
        return codePoint >= 0x0600
            && (codePoint <= 0x0603
                || codePoint == 0x061C
                || codePoint >= 0x06DD && codePoint <= 0x06DE
                || codePoint == 0x070F
                || codePoint >= 0x1160 && codePoint <= 0x11FF
                || codePoint >= 0x200B && codePoint <= 0x200F
                || codePoint >= 0x202A && codePoint <= 0x202E
                || codePoint >= 0x2060 && codePoint <= 0x2064
                || codePoint >= 0x2066 && codePoint <= 0x206F
                || codePoint == 0xFEFF
                || codePoint >= 0xFFF9 && codePoint <= 0xFFFB
                || codePoint >= 0x1D173 && codePoint <= 0x1D17A
                || codePoint == 0xE0001
                || codePoint >= 0xE0020 && codePoint <= 0xE007F);
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
