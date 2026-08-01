package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.style.CellStyle;
import dev.tuink.tui.input.KeyEvent;
import dev.tuink.tui.ui.MenuBar;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("removal")
class TopMenuInputControllerTest {
    @Test
    void dynamicTabActivationConsumesEnterWithoutForwardingACommand() {
        MenuBar menu = menu();
        menu.setOpenTabs(
            List.of(
                new MenuBar.OpenTab(Path.of("first.txt"), false, false),
                new MenuBar.OpenTab(Path.of("second.txt"), false, false)
            ),
            0
        );
        menu.openTab();
        for (int i = 0; i < 5; i++) {
            menu.handleKey(KeyEvent.Action.ARROW_DOWN);
        }

        TopMenuInputController.HandleResult result = TopMenuInputController.handleWhenOpen(
            menu,
            action(KeyEvent.Action.ENTER)
        );

        assertTrue(result.consumed());
        assertFalse(result.keepMenuOpen());
        assertEquals(1, result.tabActivation());
        assertNull(result.selectedAction());
    }

    @Test
    void escapeOnlyDismissesTheMenuAndIsNotASelectedCommand() {
        MenuBar menu = menu();
        menu.openFile();

        TopMenuInputController.HandleResult result = TopMenuInputController.handleWhenOpen(
            menu,
            action(KeyEvent.Action.ESCAPE)
        );

        assertTrue(result.consumed());
        assertFalse(result.keepMenuOpen());
        assertNull(result.tabActivation());
        assertNull(result.selectedAction());
    }

    @Test
    void staticSelectionForwardsExactlyOneMappedAction() {
        MenuBar menu = menu();
        menu.openFile();

        TopMenuInputController.HandleResult result = TopMenuInputController.handleWhenOpen(
            menu,
            action(KeyEvent.Action.ENTER)
        );

        assertEquals(KeyEvent.Action.TAB_NEW, result.selectedAction());
        assertEquals(action(KeyEvent.Action.TAB_NEW), result.nextKey());
        assertFalse(result.keepMenuOpen());
    }

    private static MenuBar menu() {
        return new MenuBar(
            CellStyle.DEFAULT,
            CellStyle.DEFAULT,
            CellStyle.DEFAULT,
            CellStyle.DEFAULT
        );
    }

    private static KeyEvent.ActionEvent action(KeyEvent.Action action) {
        return new KeyEvent.ActionEvent(action);
    }
}
