package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommandIds.DOCUMENT_SAVE;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_EDIT;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_FILE;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_HELP;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_SETTINGS;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_TABS;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_VIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.command.CommandId;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.terminal.widget.MenuBar;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopMenuControllerTest {
    @Test
    void semanticMenuCommandsOpenAllSixStableMenuIndices() {
        TopMenuController controller = new TopMenuController();
        List<CommandId> commands = List.of(
            MENU_FILE,
            MENU_EDIT,
            MENU_VIEW,
            MENU_TABS,
            MENU_SETTINGS,
            MENU_HELP
        );

        for (int index = 0; index < commands.size(); index++) {
            assertTrue(controller.openFor(CommandInvocation.of(commands.get(index))));
            assertTrue(controller.isOpen());
            assertEquals(index, controller.state().openMenuIndex());
        }
    }

    @Test
    void unrelatedCommandDoesNotMutateStateAndCloseIsExplicit() {
        TopMenuController controller = new TopMenuController();
        controller.adopt(new MenuBar.State(2, 4, 1));

        assertFalse(controller.openFor(CommandInvocation.of(DOCUMENT_SAVE)));
        assertEquals(new MenuBar.State(2, 4, 1), controller.state());

        controller.close();
        assertEquals(MenuBar.State.closed(), controller.state());
        assertFalse(controller.isOpen());
    }

    @Test
    void keyboardOpenCommandResetsExistingSelectionInsteadOfTogglingClosed() {
        TopMenuController controller = new TopMenuController();
        controller.adopt(new MenuBar.State(0, 3, 2));

        assertTrue(controller.openFor(CommandInvocation.of(MENU_FILE)));

        assertEquals(MenuBar.State.open(0), controller.state());
    }
}
