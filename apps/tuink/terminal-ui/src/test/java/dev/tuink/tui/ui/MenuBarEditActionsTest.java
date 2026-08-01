package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.terminal.style.CellStyle;
import dev.tuink.tui.input.KeyEvent;
import org.junit.jupiter.api.Test;

class MenuBarEditActionsTest {
    @Test
    void toggleLineCommentIsAvailableFromEditMenu() {
        MenuBar menu = new MenuBar(
            CellStyle.DEFAULT,
            CellStyle.DEFAULT,
            CellStyle.DEFAULT,
            CellStyle.DEFAULT
        );
        menu.openEdit();
        for (int i = 0; i < 5; i++) {
            menu.handleKey(KeyEvent.Action.ARROW_DOWN);
        }

        assertEquals(KeyEvent.Action.TOGGLE_LINE_COMMENT, menu.handleKey(KeyEvent.Action.ENTER));
    }
}
