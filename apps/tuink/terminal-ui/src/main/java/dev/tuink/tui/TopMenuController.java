package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.MENU_EDIT;
import static dev.tuink.tui.command.TuinkCommand.MENU_EXPLORER;
import static dev.tuink.tui.command.TuinkCommand.MENU_FILE;
import static dev.tuink.tui.command.TuinkCommand.MENU_HELP;
import static dev.tuink.tui.command.TuinkCommand.MENU_SETTINGS;
import static dev.tuink.tui.command.TuinkCommand.MENU_TABS;
import static dev.tuink.tui.command.TuinkCommand.MENU_VIEW;

import dev.tuink.editor.command.CommandId;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuiframe.widget.MenuBar;
import java.util.Objects;

/** Application-owned lifecycle for the reusable top-menu state. */
final class TopMenuController {
    static final int FILE_INDEX = 0;
    static final int EXPLORER_INDEX = 1;
    static final int EDIT_INDEX = 2;
    static final int VIEW_INDEX = 3;
    static final int TABS_INDEX = 4;
    static final int SETTINGS_INDEX = 5;
    static final int HELP_INDEX = 6;

    private MenuBar.State state = MenuBar.State.closed();

    MenuBar.State state() {
        return state;
    }

    boolean isOpen() {
        return state.isOpen();
    }

    void adopt(MenuBar.State nextState) {
        state = Objects.requireNonNull(nextState, "nextState");
    }

    void close() {
        state = MenuBar.State.closed();
    }

    /**
     * Opens the menu addressed by a semantic keymap command. The command is
     * consumed only when it denotes one of the top-level menus.
     */
    boolean openFor(CommandInvocation invocation) {
        if (invocation == null) {
            return false;
        }
        int menuIndex = menuIndex(invocation.id());
        if (menuIndex < 0) {
            return false;
        }
        state = MenuBar.State.open(menuIndex);
        return true;
    }

    private static int menuIndex(CommandId id) {
        if (MENU_FILE.id().equals(id)) {
            return FILE_INDEX;
        }
        if (MENU_EDIT.id().equals(id)) {
            return EDIT_INDEX;
        }
        if (MENU_VIEW.id().equals(id)) {
            return VIEW_INDEX;
        }
        if (MENU_TABS.id().equals(id)) {
            return TABS_INDEX;
        }
        if (MENU_SETTINGS.id().equals(id)) {
            return SETTINGS_INDEX;
        }
        if (MENU_HELP.id().equals(id)) {
            return HELP_INDEX;
        }
        if (MENU_EXPLORER.id().equals(id)) {
            return EXPLORER_INDEX;
        }
        return -1;
    }
}
