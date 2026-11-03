package dev.tuink.tui.keymap;

import static dev.tuink.tui.command.TuinkCommand.DOCUMENT_SAVE;
import static dev.tuink.tui.command.TuinkCommand.DOCUMENT_SAVE_AS;
import static dev.tuink.tui.command.TuinkCommand.EDIT_TOGGLE_LINE_COMMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuinkKeymapTest {
    private final Keymap<KeyStroke> keymap = TuinkKeymap.defaults();

    @Test
    void mapsPhysicalShortcutsToSemanticCommands() {
        assertEquals(DOCUMENT_SAVE.id(), command(KeyStroke.Key.S, KeyStroke.Modifier.CTRL));
        assertEquals(DOCUMENT_SAVE_AS.id(), command(KeyStroke.Key.K, KeyStroke.Modifier.CTRL));
        assertEquals(TuinkCommand.TAB_CLOSE.id(), command(KeyStroke.Key.W, KeyStroke.Modifier.CTRL));
        assertEquals(
            DOCUMENT_SAVE_AS.id(),
            command(KeyStroke.Key.S, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT)
        );
        assertEquals(
            DOCUMENT_SAVE_AS.id(),
            command(KeyStroke.Key.F4, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT)
        );
        assertEquals(
            EDIT_TOGGLE_LINE_COMMENT.id(),
            command(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL)
        );
    }

    @Test
    void functionAndMnemonicKeysShareMenuCommands() {
        List<KeyStroke.Key> functionKeys = List.of(
            KeyStroke.Key.F1, KeyStroke.Key.F2, KeyStroke.Key.F3,
            KeyStroke.Key.F4, KeyStroke.Key.F5, KeyStroke.Key.F6
        );
        List<KeyStroke.Key> mnemonicKeys = List.of(
            KeyStroke.Key.I, KeyStroke.Key.E, KeyStroke.Key.V,
            KeyStroke.Key.T, KeyStroke.Key.S, KeyStroke.Key.H
        );
        List<TuinkCommand> menus = List.of(
            TuinkCommand.MENU_FILE, TuinkCommand.MENU_EDIT, TuinkCommand.MENU_VIEW,
            TuinkCommand.MENU_TABS, TuinkCommand.MENU_SETTINGS, TuinkCommand.MENU_HELP
        );
        for (int index = 0; index < menus.size(); index++) {
            assertEquals(menus.get(index).id(), command(functionKeys.get(index)));
            assertEquals(menus.get(index).id(), command(mnemonicKeys.get(index), KeyStroke.Modifier.ALT));
        }
    }

    @Test
    void navigationModifiersRetainTheirDistinctCommands() {
        assertEquals(TuinkCommand.CURSOR_LEFT.id(), command(KeyStroke.Key.ARROW_LEFT));
        assertEquals(
            TuinkCommand.CURSOR_SELECT_LEFT.id(),
            command(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT)
        );
        assertEquals(
            TuinkCommand.CURSOR_WORD_LEFT.id(),
            command(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL)
        );
        assertEquals(TuinkCommand.CURSOR_WORD_LEFT.id(), command(KeyStroke.Key.B, KeyStroke.Modifier.ALT));
        assertEquals(TuinkCommand.CURSOR_WORD_RIGHT.id(), command(KeyStroke.Key.F, KeyStroke.Modifier.ALT));
        assertEquals(TuinkCommand.CURSOR_PAGE_DOWN.id(), command(KeyStroke.Key.PAGE_DOWN));
        assertEquals(TuinkCommand.TAB_NEXT.id(), command(KeyStroke.Key.PAGE_DOWN, KeyStroke.Modifier.CTRL));
        assertEquals(TuinkCommand.TAB_NEXT.id(), command(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL));
        assertEquals(TuinkCommand.TAB_NEXT.id(), command(KeyStroke.Key.TAB, KeyStroke.Modifier.SUPER));
        assertEquals(TuinkCommand.TAB_PREVIOUS.id(), command(KeyStroke.Key.TAB,
            KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT));
    }

    @Test
    void additionalModifiersNeverTriggerAnUnmodifiedOrPartialBinding() {
        assertFalse(keymap.resolve(KeyStroke.of(KeyStroke.Key.F1, KeyStroke.Modifier.SHIFT)).isPresent());
        assertFalse(keymap.resolve(KeyStroke.of(
            KeyStroke.Key.Q, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT
        )).isPresent());
        assertFalse(keymap.resolve(KeyStroke.of(
            KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT
        )).isPresent());
    }

    @Test
    void leavesUnassignedPhysicalKeysForFocusedComponents() {
        assertFalse(keymap.resolve(KeyStroke.of(KeyStroke.Key.INSERT)).isPresent());
        assertFalse(keymap.resolve(KeyStroke.of(KeyStroke.Key.F12)).isPresent());
    }

    private dev.tuink.editor.command.CommandId command(
        KeyStroke.Key key,
        KeyStroke.Modifier... modifiers
    ) {
        return keymap.resolve(KeyStroke.of(key, modifiers)).orElseThrow().id();
    }
}
