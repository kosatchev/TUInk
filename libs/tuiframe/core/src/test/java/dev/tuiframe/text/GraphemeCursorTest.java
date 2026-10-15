package dev.tuiframe.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class GraphemeCursorTest {
    @Test
    void streamingRangesAgreeWithExtendedGraphemeSegmentationAcrossAsciiTransitions() {
        List<String> atoms = List.of("a", "\t", "e\u0301", "\u0301", "界", "👩‍💻", "🇬🇧", "🇨",
            "\r\n", "\u0001", "\ud800", "\udc00", "1\ufe0f\u20e3", "क्ष");
        Random random = new Random(0xc105eL);
        for (int example = 0; example < 300; example++) {
            StringBuilder text = new StringBuilder();
            for (int atom = 0; atom < 12; atom++) text.append(atoms.get(random.nextInt(atoms.size())));
            String source = text.toString();
            Matcher expected = Pattern.compile("\\X").matcher(source);
            TerminalText.GraphemeCursor cursor = TerminalText.graphemeCursor(source);
            while (expected.find()) {
                assertTrue(cursor.advance());
                assertEquals(expected.start(), cursor.start(), source);
                assertEquals(expected.end(), cursor.end(), source);
                boolean tab = expected.group().equals("\t");
                assertEquals(tab, cursor.isTab());
                assertEquals(tab ? 0 : TerminalText.graphemeWidth(expected.group()), cursor.width());
            }
            assertFalse(cursor.advance());
            assertFalse(cursor.advance());
        }
        assertFalse(TerminalText.graphemeCursor(null).advance());
    }
}
