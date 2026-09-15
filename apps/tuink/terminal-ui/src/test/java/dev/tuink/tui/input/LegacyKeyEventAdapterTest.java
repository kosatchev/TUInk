package dev.tuink.tui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class LegacyKeyEventAdapterTest {
    @Test
    void mapsControlAndAltChordsAtTheCompatibilityBoundary() {
        assertAction(KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL), KeyEvent.Action.CTRL_S);
        assertAction(
            KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT),
            KeyEvent.Action.CTRL_SHIFT_S
        );
        assertAction(
            KeyStroke.of(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL),
            KeyEvent.Action.TOGGLE_LINE_COMMENT
        );
        assertAction(KeyStroke.of(KeyStroke.Key.B, KeyStroke.Modifier.ALT), KeyEvent.Action.WORD_LEFT);
        assertAction(KeyStroke.of(KeyStroke.Key.I, KeyStroke.Modifier.ALT), KeyEvent.Action.F10);
        assertAction(KeyStroke.of(KeyStroke.Key.H, KeyStroke.Modifier.ALT), KeyEvent.Action.OPEN_HELP_MENU);
    }

    @Test
    void mapsPhysicalFunctionKeysUsingTheOldTerminalBindings() {
        List<KeyEvent.Action> expected = List.of(
            KeyEvent.Action.F10,
            KeyEvent.Action.CTRL_E,
            KeyEvent.Action.OPEN_VIEW_MENU,
            KeyEvent.Action.CTRL_T,
            KeyEvent.Action.F2,
            KeyEvent.Action.OPEN_HELP_MENU
        );

        for (int i = 0; i < expected.size(); i++) {
            KeyStroke.Key key = KeyStroke.Key.valueOf("F" + (i + 1));
            assertAction(KeyStroke.of(key), expected.get(i));
        }
    }

    @Test
    void mapsNavigationModifiersAndLegacyTabNavigation() {
        assertAction(KeyStroke.of(KeyStroke.Key.ARROW_LEFT), KeyEvent.Action.ARROW_LEFT);
        assertAction(
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT),
            KeyEvent.Action.SHIFT_ARROW_LEFT
        );
        assertAction(
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL),
            KeyEvent.Action.WORD_LEFT
        );
        assertAction(
            KeyStroke.of(KeyStroke.Key.PAGE_UP, KeyStroke.Modifier.CTRL),
            KeyEvent.Action.TAB_PREVIOUS
        );
        assertAction(
            KeyStroke.of(KeyStroke.Key.PAGE_DOWN, KeyStroke.Modifier.CTRL),
            KeyEvent.Action.TAB_NEXT
        );
        assertAction(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL),
            KeyEvent.Action.TAB_NEXT
        );
    }

    @Test
    void adaptsOnlySingleScalarText() {
        KeyEvent.CharInput scalar = assertInstanceOf(
            KeyEvent.CharInput.class,
            LegacyKeyEventAdapter.toLegacy(new TextInput("😀")).orElseThrow()
        );

        assertEquals("😀", scalar.text());
        assertTrue(LegacyKeyEventAdapter.toLegacy(new TextInput("ab")).isEmpty());
    }

    @Test
    void adaptsPointerButLeavesPasteAndResizeForNewRouting() {
        PointerEvent pointer = new PointerEvent(
            2,
            3,
            PointerEvent.Kind.SCROLL_DOWN,
            PointerEvent.Button.NONE,
            Set.of(KeyStroke.Modifier.CTRL)
        );

        KeyEvent.MouseEvent legacy = assertInstanceOf(
            KeyEvent.MouseEvent.class,
            LegacyKeyEventAdapter.toLegacy(pointer).orElseThrow()
        );
        assertEquals(2, legacy.row());
        assertEquals(3, legacy.col());
        assertEquals(KeyEvent.MouseKind.SCROLL_DOWN, legacy.kind());
        assertEquals(Optional.empty(), LegacyKeyEventAdapter.toLegacy(new PasteEvent("text")));
        assertEquals(Optional.empty(), LegacyKeyEventAdapter.toLegacy(new ResizeEvent(24, 80)));
        assertEquals(
            Optional.empty(),
            LegacyKeyEventAdapter.toLegacy(
                new PointerEvent(2, 3, PointerEvent.Kind.SCROLL_LEFT, PointerEvent.Button.NONE, Set.of())
            )
        );
        assertEquals(
            Optional.empty(),
            LegacyKeyEventAdapter.toLegacy(
                new PointerEvent(2, 3, PointerEvent.Kind.UNKNOWN, PointerEvent.Button.OTHER, Set.of())
            )
        );
    }

    @Test
    void rejectsNullInsteadOfReturningNull() {
        assertThrows(IllegalArgumentException.class, () -> LegacyKeyEventAdapter.toLegacy(null));
    }

    private static void assertAction(KeyStroke stroke, KeyEvent.Action expected) {
        KeyEvent.ActionEvent event = assertInstanceOf(
            KeyEvent.ActionEvent.class,
            LegacyKeyEventAdapter.toLegacy(stroke).orElseThrow()
        );
        assertEquals(expected, event.action());
    }
}
