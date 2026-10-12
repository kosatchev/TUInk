package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.input.KeyStroke;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldClipboardTest {
    @Test
    void copyTakesOnlyTheSelectedUnicodeTextWithoutMovingItsCaret() {
        TextField field = new TextField();
        String value = "left 👩‍💻 e\u0301 中 right";
        field.set(value);
        field.moveHome();
        field.moveRight();
        field.navigate(KeyStroke.Key.ARROW_RIGHT, false, true);
        field.navigate(KeyStroke.Key.ARROW_RIGHT, false, true);
        int caret = field.cursor();
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("old value");

        assertTrue(FieldClipboard.handle(TuinkCommand.EDIT_COPY, field, clipboard));

        assertEquals("ef", clipboard.text());
        assertEquals(value, field.text());
        assertEquals(caret, field.cursor());
    }

    @Test
    void selectAllThenCutCopiesTheWholeValueAndClearsTheField() {
        TextField field = new TextField();
        String value = "👩‍💻 e\u0301 中";
        field.set(value);
        field.moveHome();
        field.moveRight();
        field.selectAll();
        EditorClipboard clipboard = new EditorClipboard();

        assertTrue(FieldClipboard.handle(TuinkCommand.EDIT_CUT, field, clipboard));

        assertEquals(value, clipboard.text());
        assertTrue(field.isEmpty());
        assertEquals(0, field.cursor());
    }

    @Test
    void copyAndCutWithoutASelectionPreserveTheValueAndClipboard() {
        TextField field = new TextField();
        field.set("unchanged 😀");
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("retained");
        for (TuinkCommand command : List.of(TuinkCommand.EDIT_COPY, TuinkCommand.EDIT_CUT)) {
            assertTrue(FieldClipboard.handle(command, field, clipboard));
            assertEquals("retained", clipboard.text());
            assertEquals("unchanged 😀", field.text());
        }
    }

    @Test
    void cutAndPasteReplaceOnlyTheSelection() {
        TextField field = new TextField();
        field.set("left 👩‍💻 right");
        field.moveHome();
        field.navigate(KeyStroke.Key.ARROW_RIGHT, true, false);
        field.navigate(KeyStroke.Key.ARROW_RIGHT, false, true);
        EditorClipboard clipboard = new EditorClipboard();
        assertTrue(FieldClipboard.handle(TuinkCommand.EDIT_CUT, field, clipboard));
        assertEquals("👩‍💻", clipboard.text());
        assertEquals("left  right", field.text());
        field.selectAll();
        assertTrue(FieldClipboard.handle(TuinkCommand.EDIT_PASTE, field, clipboard));
        assertEquals("👩‍💻", field.text());
        assertFalse(field.hasSelection());
    }

    @Test
    void pasteNormalizesMultilineClipboardTextAtTheUnicodeCaretWithoutChangingTheClipboard() {
        TextField field = new TextField();
        field.set("A👩‍💻B");
        field.moveHome();
        field.moveRight();
        EditorClipboard clipboard = new EditorClipboard();
        String source = "first\r\n\tsecond\u0000 🦀\n";
        clipboard.setText(source);

        assertTrue(FieldClipboard.handle(TuinkCommand.EDIT_PASTE, field, clipboard));

        assertEquals("Afirst second 🦀👩‍💻B", field.text());
        assertEquals("Afirst second 🦀".length(), field.cursor());
        assertEquals(source, clipboard.text());
        field.moveLeft();
        assertEquals("Afirst second ".length(), field.cursor(), "the pasted emoji stays one caret step");
    }

    @Test
    void emptyCopyAndCutPreserveTheExistingClipboard() {
        TextField field = new TextField();
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("retained 中🦀");

        for (TuinkCommand command : List.of(TuinkCommand.EDIT_COPY, TuinkCommand.EDIT_CUT)) {
            assertTrue(FieldClipboard.handle(command, field, clipboard));
            assertEquals("retained 中🦀", clipboard.text());
            assertEquals("", field.text());
            assertEquals(0, field.cursor());
        }
    }

    @Test
    void emptyOrControlOnlyPasteIsConsumedWithoutMovingTheCaret() {
        TextField field = new TextField();
        field.set("A🦀B");
        field.moveLeft();
        int caret = field.cursor();
        EditorClipboard clipboard = new EditorClipboard();
        for (String source : List.of("", "\r\n\t\u0000")) {
            clipboard.setText(source);
            assertTrue(FieldClipboard.handle(TuinkCommand.EDIT_PASTE, field, clipboard));
            assertEquals("A🦀B", field.text());
            assertEquals(caret, field.cursor());
            assertEquals(source, clipboard.text());
        }
    }

    @Test
    void unrelatedCommandsNeverChangeFieldCaretOrClipboard() {
        TextField field = new TextField();
        field.set("keep 👩‍💻");
        field.moveLeft();
        int caret = field.cursor();
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("retained");
        for (TuinkCommand command : TuinkCommand.values()) {
            if (command == TuinkCommand.EDIT_COPY || command == TuinkCommand.EDIT_CUT || command == TuinkCommand.EDIT_PASTE) {
                continue;
            }
            assertFalse(FieldClipboard.handle(command, field, clipboard), command.toString());
            assertEquals("keep 👩‍💻", field.text());
            assertEquals(caret, field.cursor());
            assertEquals("retained", clipboard.text());
        }
        assertFalse(FieldClipboard.handle(null, field, clipboard));
    }
}
