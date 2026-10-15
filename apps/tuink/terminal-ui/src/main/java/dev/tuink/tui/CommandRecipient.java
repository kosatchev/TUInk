package dev.tuink.tui;

import dev.tuink.tui.command.TuinkCommand;

/** Selects one recipient for a semantic command after overlay access policy. */
enum CommandRecipient {
    EDITOR,
    SEARCH_CONTROL,
    SEARCH_DOCUMENT;

    static CommandRecipient resolve(TuinkCommand command, boolean searchVisible, boolean documentFocused) {
        if (!searchVisible || command == null) {
            return EDITOR;
        }
        if (isSearchOperation(command)
            || !documentFocused && (command == TuinkCommand.CURSOR_PAGE_UP || command == TuinkCommand.CURSOR_PAGE_DOWN)) {
            return SEARCH_DOCUMENT;
        }
        if (command == TuinkCommand.EDIT_INSERT_TAB || command == TuinkCommand.SEARCH_FOCUS_NEXT
            || !documentFocused && ControlCommandRouter.owns(command)) {
            return SEARCH_CONTROL;
        }
        return EDITOR;
    }
    private static boolean isSearchOperation(TuinkCommand command) {
        return command == TuinkCommand.SEARCH_NEXT || command == TuinkCommand.SEARCH_PREVIOUS
            || command == TuinkCommand.SEARCH_REPLACE || command == TuinkCommand.SEARCH_REPLACE_ALL;
    }
}
