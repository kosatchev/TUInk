package dev.tuink.terminal.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.text.TerminalText;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TextFieldUnicodeTest {
    @Test
    void cursorMovesAndDeletesByExtendedGraphemeCluster() {
        TextField field = new TextField();
        field.set("A👨‍👩‍👧‍👦e\u0301中B");

        field.moveLeft();
        assertEquals("A👨‍👩‍👧‍👦e\u0301中".length(), field.cursor());
        field.moveLeft();
        assertEquals("A👨‍👩‍👧‍👦e\u0301".length(), field.cursor());
        field.backspace();

        assertEquals("A👨‍👩‍👧‍👦中B", field.text());
        assertEquals("A👨‍👩‍👧‍👦".length(), field.cursor());
    }

    @Test
    void reportsTerminalCellCursorColumnInsteadOfUtf16Length() {
        TextField field = new TextField();
        field.set("A😀中e\u0301");

        assertEquals(6, field.cursorCellColumn());
        assertEquals(6, field.displayWidth());
        assertEquals(6, field.cursor());
    }

    @Test
    void supplementaryInputIsInsertedAtomically() {
        TextField field = new TextField();
        field.insert("😀");
        field.insert("👍🏽");
        field.moveLeft();
        field.deleteForward();

        assertEquals("😀", field.text());
        assertEquals(2, field.cursor());
    }

    @Test
    void editsRenormalizeCursorWhenSurroundingGraphemesRecluster() {
        TextField inserted = new TextField();
        inserted.set("\u0301x");
        inserted.moveHome();
        inserted.insert("a");

        assertEquals("a\u0301x", inserted.text());
        assertEquals("a\u0301".length(), inserted.cursor());
        assertTrue(TerminalText.isBoundary(inserted.text(), inserted.cursor()));

        TextField deleted = new TextField();
        deleted.set("🇦X🇧🇨");
        deleted.moveLeft();
        deleted.backspace();

        assertEquals("🇦🇧🇨", deleted.text());
        assertTrue(TerminalText.isBoundary(deleted.text(), deleted.cursor()));
    }

    @Test
    void randomizedLegacyEditsNeverLeaveCursorInsideAGrapheme() {
        TextField field = new TextField();
        List<String> inputs = List.of("a", "\u0301", "中", "😀", "🇦", "🇧", "\u200D");
        Random random = new Random(0x54_45_58_54L);

        for (int step = 0; step < 1_000; step++) {
            switch (random.nextInt(8)) {
                case 0, 1 -> field.insert(inputs.get(random.nextInt(inputs.size())));
                case 2 -> field.backspace();
                case 3 -> field.deleteForward();
                case 4 -> field.moveLeft();
                case 5 -> field.moveRight();
                case 6 -> field.moveHome();
                case 7 -> field.moveEnd();
                default -> throw new AssertionError();
            }
            assertTrue(field.cursor() >= 0 && field.cursor() <= field.length());
            assertTrue(
                TerminalText.isBoundary(field.text(), field.cursor()),
                "cursor split a grapheme at step " + step
            );
            assertTrue(field.cursorCellColumn() <= field.displayWidth());
            if (field.length() > 256) {
                field.clear();
            }
        }
    }
}
