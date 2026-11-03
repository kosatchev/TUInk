package dev.tuink.tui.command;

import dev.tuink.editor.command.CommandId;
import java.util.Objects;
import java.util.Optional;

/** Built-in semantic editor commands, independent of their keyboard or menu bindings. */
public enum TuinkCommand {
    APP_QUIT("tuink.app.quit", "Quit"),

    DOCUMENT_RECOVER_DRAFTS("tuink.document.recover-drafts", "Recover drafts"),
    DOCUMENT_OPEN("tuink.document.open", "Open document"),
    DOCUMENT_SAVE("tuink.document.save", "Save document"),
    DOCUMENT_SAVE_AS("tuink.document.save-as", "Save document as"),
    DOCUMENT_FORMAT("tuink.document.format", "Change document format"),
    DOCUMENT_FORMAT_APPLY("tuink.document.format.apply", "Apply document format"),
    DOCUMENT_FORMAT_CANCEL("tuink.document.format.cancel", "Cancel document format"),
    DOCUMENT_OPEN_ENCODING("tuink.document.open-encoding", "Open with encoding"),
    DOCUMENT_REOPEN_ENCODING("tuink.document.reopen-encoding", "Reopen with encoding"),

    EDIT_UNDO("tuink.edit.undo", "Undo"),
    EDIT_REDO("tuink.edit.redo", "Redo"),
    EDIT_CUT("tuink.edit.cut", "Cut"),
    EDIT_COPY("tuink.edit.copy", "Copy"),
    EDIT_PASTE("tuink.edit.paste", "Paste"),
    EDIT_SELECT_ALL("tuink.edit.select-all", "Select all"),
    EDIT_TOGGLE_LINE_COMMENT("tuink.edit.toggle-line-comment", "Toggle line comment"),
    EDIT_INSERT_TAB("tuink.edit.insert-tab", "Insert tab"),
    EDIT_INSERT_NEWLINE("tuink.edit.insert-newline", "Insert newline"),
    EDIT_BACKSPACE("tuink.edit.backspace", "Backspace"),
    EDIT_DELETE_FORWARD("tuink.edit.delete-forward", "Delete forward"),

    CURSOR_UP("tuink.cursor.up", "Move cursor up"),
    CURSOR_DOWN("tuink.cursor.down", "Move cursor down"),
    CURSOR_LEFT("tuink.cursor.left", "Move cursor left"),
    CURSOR_RIGHT("tuink.cursor.right", "Move cursor right"),
    CURSOR_SELECT_UP("tuink.cursor.select-up", "Select up"),
    CURSOR_SELECT_DOWN("tuink.cursor.select-down", "Select down"),
    CURSOR_SELECT_LEFT("tuink.cursor.select-left", "Select left"),
    CURSOR_SELECT_RIGHT("tuink.cursor.select-right", "Select right"),
    CURSOR_WORD_LEFT("tuink.cursor.word-left", "Move one word left"),
    CURSOR_WORD_RIGHT("tuink.cursor.word-right", "Move one word right"),
    CURSOR_PAGE_UP("tuink.cursor.page-up", "Page up"),
    CURSOR_PAGE_DOWN("tuink.cursor.page-down", "Page down"),
    CURSOR_HOME("tuink.cursor.home", "Move to line start"),
    CURSOR_END("tuink.cursor.end", "Move to line end"),
    CURSOR_SELECT_HOME("tuink.cursor.select-home", "Select to line start"),
    CURSOR_SELECT_END("tuink.cursor.select-end", "Select to line end"),
    CURSOR_DOCUMENT_START("tuink.cursor.document-start", "Move to document start"),
    CURSOR_DOCUMENT_END("tuink.cursor.document-end", "Move to document end"),
    CURSOR_SELECT_DOCUMENT_START("tuink.cursor.select-document-start", "Select to document start"),
    CURSOR_SELECT_DOCUMENT_END("tuink.cursor.select-document-end", "Select to document end"),

    SEARCH_OPEN("tuink.search.open", "Open search"),
    SEARCH_NEXT("tuink.search.next", "Find next"),
    SEARCH_PREVIOUS("tuink.search.previous", "Previous match"),
    SEARCH_REPLACE("tuink.search.replace", "Replace"),
    SEARCH_REPLACE_ALL("tuink.search.replace-all", "Replace all"),
    SEARCH_CLOSE("tuink.search.close", "Close search"),
    SEARCH_FOCUS_NEXT("tuink.search.focus-next", "Move search focus"),
    SEARCH_NAVIGATE_LEFT("tuink.search.navigate-left", "Navigate search left"),
    SEARCH_NAVIGATE_RIGHT("tuink.search.navigate-right", "Navigate search right"),
    SEARCH_NAVIGATE_UP("tuink.search.navigate-up", "Navigate search up"),
    SEARCH_NAVIGATE_DOWN("tuink.search.navigate-down", "Navigate search down"),
    SEARCH_FIELD_BACKSPACE("tuink.search.field-backspace", "Delete search text backward"),
    SEARCH_FIELD_DELETE("tuink.search.field-delete", "Delete search text forward"),
    SEARCH_FIELD_HOME("tuink.search.field-home", "Move to start of search field"),
    SEARCH_FIELD_END("tuink.search.field-end", "Move to end of search field"),

    TAB_NEW("tuink.tab.new", "New tab"),
    TAB_CLOSE("tuink.tab.close", "Close tab"),
    TAB_NEXT("tuink.tab.next", "Next tab"),
    TAB_PREVIOUS("tuink.tab.previous", "Previous tab"),
    TAB_ACTIVATE("tuink.tab.activate", "Activate tab"),

    MENU_FILE("tuink.menu.file", "Open File menu"),
    MENU_EDIT("tuink.menu.edit", "Open Edit menu"),
    MENU_VIEW("tuink.menu.view", "Open View menu"),
    MENU_TABS("tuink.menu.tabs", "Open Tab menu"),
    MENU_SETTINGS("tuink.menu.settings", "Open Settings menu"),
    MENU_HELP("tuink.menu.help", "Open Help menu"),

    VIEW_TOGGLE_TAB_BAR("tuink.view.toggle-tab-bar", "Toggle tab bar"),
    VIEW_TOGGLE_LINE_WRAP("tuink.view.toggle-line-wrap", "Toggle line wrapping"),
    VIEW_TOGGLE_WORD_WRAP("tuink.view.toggle-word-wrap", "Toggle word wrapping"),
    VIEW_TOGGLE_ACTIVE_GUTTER("tuink.view.toggle-active-gutter", "Toggle active gutter highlight"),
    VIEW_TOGGLE_WHITESPACE("tuink.view.toggle-whitespace", "Toggle visible whitespace"),
    VIEW_TOGGLE_SYNTAX("tuink.view.toggle-syntax", "Toggle syntax highlighting"),
    VIEW_TOGGLE_RAINBOW_BRACKETS("tuink.view.toggle-rainbow-brackets", "Toggle rainbow brackets"),
    VIEW_TOGGLE_SELECTION_HIGHLIGHT("tuink.view.toggle-selection-highlight", "Toggle selection highlight"),
    VIEW_CYCLE_GUTTER_DIFF("tuink.view.cycle-gutter-diff", "Cycle gutter diff"),
    VIEW_CYCLE_TAB_WIDTH("tuink.view.cycle-tab-width", "Cycle tab width"),
    VIEW_CYCLE_THEME("tuink.view.cycle-theme", "Cycle theme"),

    SETTINGS_TOGGLE_READING_MODE("tuink.settings.toggle-reading-mode", "Toggle reading mode"),
    SETTINGS_TOGGLE_MOUSE("tuink.settings.toggle-mouse", "Toggle mouse input"),
    SETTINGS_TOGGLE_CONFIRM_ON_CLOSE("tuink.settings.toggle-confirm-on-close", "Toggle close confirmation"),
    SETTINGS_TOGGLE_SMART_HOME_END("tuink.settings.toggle-smart-home-end", "Toggle smart Home and End"),
    SETTINGS_TOGGLE_AUTO_INDENT("tuink.settings.toggle-auto-indent", "Toggle auto indent"),
    SETTINGS_TOGGLE_AUTO_PAIRS("tuink.settings.toggle-auto-pairs", "Toggle auto pairs"),
    SETTINGS_TOGGLE_AUTO_LIST("tuink.settings.toggle-auto-list", "Toggle auto list"),
    SETTINGS_CYCLE_TAB_INSERT_MODE("tuink.settings.cycle-tab-insert-mode", "Cycle tab insert mode"),

    FILE_BROWSER_TOGGLE_HIDDEN("tuink.file-browser.toggle-hidden", "Toggle hidden files"),

    HELP_KEY_HINTS("tuink.help.key-hints", "Show key hints"),
    HELP_LICENSES("tuink.help.licenses", "Show licenses"),
    HELP_AUTHOR("tuink.help.author", "Show author"),

    UI_ESCAPE("tuink.ui.escape", "Escape");

    private final CommandId id;
    private final String title;

    TuinkCommand(String id, String title) {
        this.id = CommandId.of(id);
        this.title = title;
    }

    public CommandId id() {
        return id;
    }

    public String title() {
        return title;
    }

    /** Whether this command requests a change to document text or its edit history. */
    public boolean editsDocumentText() {
        return switch (this) {
            case EDIT_UNDO, EDIT_REDO, EDIT_CUT, EDIT_PASTE, EDIT_TOGGLE_LINE_COMMENT,
                EDIT_INSERT_TAB, EDIT_INSERT_NEWLINE, EDIT_BACKSPACE, EDIT_DELETE_FORWARD,
                SEARCH_REPLACE, SEARCH_REPLACE_ALL -> true;
            default -> false;
        };
    }

    public static Optional<TuinkCommand> fromId(CommandId id) {
        Objects.requireNonNull(id, "id");
        for (TuinkCommand command : values()) {
            if (command.id.equals(id)) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }
}
