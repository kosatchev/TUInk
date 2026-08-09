package dev.tuink.terminal.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
