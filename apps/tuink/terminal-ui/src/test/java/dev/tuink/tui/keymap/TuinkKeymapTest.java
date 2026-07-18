package dev.tuink.tui.keymap;

import static dev.tuink.tui.command.TuinkCommandIds.DOCUMENT_SAVE;
import static dev.tuink.tui.command.TuinkCommandIds.DOCUMENT_SAVE_AS;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_TOGGLE_LINE_COMMENT;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_FILE;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_HELP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuink.tui.input.KeyStroke;
import org.junit.jupiter.api.Test;

class TuinkKeymapTest {
    private final Keymap<KeyStroke> keymap = TuinkKeymap.defaults();

    @Test
    void mapsPhysicalShortcutsToSemanticCommands() {
        assertEquals(DOCUMENT_SAVE, command(KeyStroke.Key.S, KeyStroke.Modifier.CTRL));
        assertEquals(DOCUMENT_SAVE_AS, command(KeyStroke.Key.K, KeyStroke.Modifier.CTRL));
        assertEquals(
            DOCUMENT_SAVE_AS,
            command(KeyStroke.Key.S, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT)
        );
        assertEquals(
            EDIT_TOGGLE_LINE_COMMENT,
            command(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL)
        );
    }

    @Test
    void functionAndMnemonicKeysShareMenuCommands() {
        assertEquals(MENU_FILE, command(KeyStroke.Key.F1));
        assertEquals(MENU_FILE, command(KeyStroke.Key.I, KeyStroke.Modifier.ALT));
        assertEquals(MENU_HELP, command(KeyStroke.Key.F6));
        assertEquals(MENU_HELP, command(KeyStroke.Key.H, KeyStroke.Modifier.ALT));
    }

    @Test
    void leavesUnassignedPhysicalKeysForFocusedComponents() {
        assertFalse(keymap.resolve(KeyStroke.of(KeyStroke.Key.INSERT)).isPresent());
        assertFalse(keymap.resolve(KeyStroke.of(KeyStroke.Key.F12)).isPresent());
    }

    private dev.tuink.tui.command.CommandId command(
        KeyStroke.Key key,
        KeyStroke.Modifier... modifiers
    ) {
        return keymap.resolve(KeyStroke.of(key, modifiers)).orElseThrow().id();
    }
}
