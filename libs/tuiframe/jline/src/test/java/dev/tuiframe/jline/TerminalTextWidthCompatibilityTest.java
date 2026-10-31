package dev.tuiframe.jline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuiframe.text.TerminalText;
import org.jline.utils.WCWidth;
import org.junit.jupiter.api.Test;

class TerminalTextWidthCompatibilityTest {
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int VARIATION_SELECTOR_16 = 0xFE0F;
    private static final int COMBINING_KEYCAP = 0x20E3;

    @Test
    void localWidthPolicyMatchesJLineWithExplicitInvisibleControlCorrections() {
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

    @Test
    void updatedBaseRangesPreserveEmojiPresentationRules() {
        // These visible bases were missing from the previous backend's wide table.
        for (String text : new String[]{"⌚", "⏩", "♈", "\uD83E\uDEE9"}) {
            assertEquals(2, TerminalText.graphemeWidth(text), text);
        }
        // The old broad supplementary range also included one-cell symbols.
        for (String text : new String[]{"\u06DE", "\uD83C\uDC00", "♥", "♥\uFE0E", "a\uFE0F", "a\u200D"}) {
            assertEquals(1, TerminalText.graphemeWidth(text), text);
        }
        for (String text : new String[]{"♥\uFE0F", "©\uFE0F", "1\uFE0F\u20E3", "👩\u200D💻", "🇺🇳", "☝🏽"}) {
            assertEquals(2, TerminalText.graphemeWidth(text), text);
        }
    }

    private static int expectedSingleCodePointWidth(int codePoint) {
        // Keep these controls explicitly zero-width across upstream table revisions.
        // JLine 3.30.17 already includes all six in its zero-width table.
        // They become one-cell replacement glyphs when sanitized for display.
        if (codePoint == 0x061C || codePoint == 0x2064
            || codePoint >= 0x2066 && codePoint <= 0x2069) {
            return 0;
        }
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
