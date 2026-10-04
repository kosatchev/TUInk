package dev.tuink.tui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.TextInput;
import org.junit.jupiter.api.Test;

class EditorInputTest {
    @Test
    void syntheticCommandsDoNotRequirePhysicalKeys() {
        EditorInput input = EditorInput.command(TuinkCommand.DOCUMENT_SAVE);

        assertEquals(TuinkCommand.DOCUMENT_SAVE, input.command());
        assertNull(input.event());
        assertThrows(NullPointerException.class, () -> EditorInput.command(null));
        assertThrows(IllegalArgumentException.class, () -> new EditorInput(null, null));
    }

    @Test
    void textKeepsSupplementaryAndMultipleScalars() {
        assertEquals(new TextInput("😀e\u0301"), EditorInput.text("😀e\u0301").event());
        assertThrows(IllegalArgumentException.class, () -> EditorInput.text("\uD83D"));
        assertThrows(IllegalArgumentException.class, () -> EditorInput.text(""));
    }
}
