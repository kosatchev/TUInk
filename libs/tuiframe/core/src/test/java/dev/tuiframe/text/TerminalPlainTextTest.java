package dev.tuiframe.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TerminalPlainTextTest {
    @Test
    void everyC0DelAndC1ControlIsVisibleInsteadOfBeingEmitted() {
        for (int codePoint = 0; codePoint <= 0x9F; codePoint++) {
            if (codePoint > 0x1F && codePoint < 0x7F) continue;
            String expected = switch (codePoint) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> String.format(Locale.ROOT, "\\u%04X", codePoint);
            };
            String actual = TerminalPlainText.singleLine("before" + (char) codePoint + "after");
            assertEquals("before" + expected + "after", actual, "control " + codePoint);
            assertTrue(actual.codePoints().noneMatch(Character::isISOControl));
        }
    }

    @Test
    void ansiAndOscPayloadsRemainVisibleTextWithoutInterpretingTheirGrammar() {
        String raw = "\u001B[31mred\u001B[0m\u001B]52;c;c2VjcmV0\u0007"
            + "\u009B2J\u009D0;title\u009C\u001B\\";
        assertEquals("\\u001B[31mred\\u001B[0m\\u001B]52;c;c2VjcmV0\\u0007"
            + "\\u009B2J\\u009D0;title\\u009C\\u001B\\", TerminalPlainText.singleLine(raw));
    }

    @Test
    void carriageReturnsAndLineSeparatorsCannotForgeDiagnosticRows() {
        String raw = "file\rFAKE\nrow\r\nnext\tvalue\u000Bmore\u000Cend\u2028line\u2029paragraph";
        assertEquals("file\\rFAKE\\nrow\\r\\nnext\\tvalue\\u000Bmore\\u000Cend"
            + "\\u2028line\\u2029paragraph", TerminalPlainText.singleLine(raw));
    }

    @Test
    void bidiControlsAreVisibleWithoutRemovingTheirSurroundingText() {
        for (int codePoint : new int[] {
            0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
            0x2066, 0x2067, 0x2068, 0x2069
        }) {
            assertEquals("name" + String.format(Locale.ROOT, "\\u%04X", codePoint) + "txt",
                TerminalPlainText.singleLine("name" + (char) codePoint + "txt"));
        }
    }

    @Test
    void validUnicodeIncludingJoinersMarksAndSupplementaryPairsIsPreserved() {
        String text = "Привет 中 😀 👩‍💻 é العَرَبِيَّة עברית \u200C\u200D\uFE0F\uDB40\uDD00";
        assertEquals(text, TerminalPlainText.singleLine(text));
        assertEquals("\\u001B" + text + "\\u0007", TerminalPlainText.singleLine("\u001B" + text + "\u0007"));
    }

    @Test
    void unpairedSurrogatesAreEscapedWhileAdjacentValidPairsSurvive() {
        assertEquals("\\uD800x\\uDC00", TerminalPlainText.singleLine("\uD800x\uDC00"));
        assertEquals("\\uD800😀\\uDC00", TerminalPlainText.singleLine("\uD800\uD83D\uDE00\uDC00"));
        assertEquals("😀\\uD800", TerminalPlainText.singleLine("😀\uD800"));
        assertEquals("\\uDC00😀", TerminalPlainText.singleLine("\uDC00😀"));
    }

    @Test
    void repeatedPresentationAndLiteralBackslashesRemainUnchanged() {
        for (String text : List.of("literal \\u001B \\n \\r \\t C:\\notes\\file.txt", "file:///name%1B.txt",
            "\u001B]0;title\u0007\r\n", "\u2028\u202E\uD800👩‍💻\uDC00", "")) {
            String once = TerminalPlainText.singleLine(text);
            assertEquals(once, TerminalPlainText.singleLine(once));
        }
        String literal = "literal \\u001B \\n \\r \\t C:\\notes\\file.txt";
        assertEquals(literal, TerminalPlainText.singleLine(literal));
        assertEquals("", TerminalPlainText.singleLine(null));
    }
}
