package dev.tuink.terminal.jline;

import dev.tuink.terminal.text.TerminalText;
import org.jline.utils.WCWidth;
import org.junit.jupiter.api.Test;

class TerminalTextWidthCompatibilityTest {
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int VARIATION_SELECTOR_16 = 0xFE0F;
    private static final int COMBINING_KEYCAP = 0x20E3;

    @Test
    void localWidthPolicyPreservesJLineBehaviorForEveryUnicodeCodePoint() {
        for (int codePoint = Character.MIN_CODE_POINT;
             codePoint <= Character.MAX_CODE_POINT;
             codePoint++) {
            String text = Character.toString(codePoint);
            int expected = expectedSingleCodePointWidth(codePoint);
            int actual = TerminalText.graphemeWidth(text);
            if (actual != expected) {
                throw new AssertionError(
                    "width mismatch at U+%04X: expected %d, got %d"
                        .formatted(codePoint, expected, actual)
                );
            }
        }
    }

    private static int expectedSingleCodePointWidth(int codePoint) {
        if (codePoint <= Character.MAX_VALUE
            && Character.isSurrogate((char) codePoint)) {
            return 1;
        }
        if (codePoint <= 0x1F || codePoint >= 0x7F && codePoint <= 0x9F) {
            return 1;
        }
        if (isGraphemeFormatting(codePoint) || isCombining(codePoint)) {
            return 0;
        }
        return Math.min(2, Math.max(0, WCWidth.wcwidth(codePoint)));
    }

    private static boolean isGraphemeFormatting(int codePoint) {
        return codePoint == VARIATION_SELECTOR_16
            || codePoint == COMBINING_KEYCAP
            || codePoint == ZERO_WIDTH_JOINER
            || codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    private static boolean isCombining(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK;
    }
}
