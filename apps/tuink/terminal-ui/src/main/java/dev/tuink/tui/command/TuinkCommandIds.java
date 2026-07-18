package dev.tuink.tui.command;

/**
 * Semantic command identifiers built into the TUInk frontend.
 *
 * <p>This catalog is incubating. Key combinations and menu positions are kept
 * out of the identifiers so the same invocation can come from a keymap, menu,
 * macro or future plugin contribution.</p>
 */
public final class TuinkCommandIds {
    public static final CommandId APP_QUIT = id("tuink.app.quit");

    public static final CommandId DOCUMENT_OPEN = id("tuink.document.open");
    public static final CommandId DOCUMENT_SAVE = id("tuink.document.save");
    public static final CommandId DOCUMENT_SAVE_AS = id("tuink.document.save-as");

    public static final CommandId EDIT_UNDO = id("tuink.edit.undo");
    public static final CommandId EDIT_REDO = id("tuink.edit.redo");
    public static final CommandId EDIT_CUT = id("tuink.edit.cut");
    public static final CommandId EDIT_COPY = id("tuink.edit.copy");
    public static final CommandId EDIT_PASTE = id("tuink.edit.paste");
    public static final CommandId EDIT_SELECT_ALL = id("tuink.edit.select-all");
    public static final CommandId EDIT_TOGGLE_LINE_COMMENT = id("tuink.edit.toggle-line-comment");
    public static final CommandId EDIT_INSERT_TAB = id("tuink.edit.insert-tab");
    public static final CommandId EDIT_INSERT_NEWLINE = id("tuink.edit.insert-newline");
    public static final CommandId EDIT_BACKSPACE = id("tuink.edit.backspace");
    public static final CommandId EDIT_DELETE_FORWARD = id("tuink.edit.delete-forward");

    public static final CommandId CURSOR_UP = id("tuink.cursor.up");
    public static final CommandId CURSOR_DOWN = id("tuink.cursor.down");
    public static final CommandId CURSOR_LEFT = id("tuink.cursor.left");
    public static final CommandId CURSOR_RIGHT = id("tuink.cursor.right");
    public static final CommandId CURSOR_SELECT_UP = id("tuink.cursor.select-up");
    public static final CommandId CURSOR_SELECT_DOWN = id("tuink.cursor.select-down");
    public static final CommandId CURSOR_SELECT_LEFT = id("tuink.cursor.select-left");
    public static final CommandId CURSOR_SELECT_RIGHT = id("tuink.cursor.select-right");
    public static final CommandId CURSOR_WORD_LEFT = id("tuink.cursor.word-left");
    public static final CommandId CURSOR_WORD_RIGHT = id("tuink.cursor.word-right");
    public static final CommandId CURSOR_PAGE_UP = id("tuink.cursor.page-up");
    public static final CommandId CURSOR_PAGE_DOWN = id("tuink.cursor.page-down");
    public static final CommandId CURSOR_HOME = id("tuink.cursor.home");
    public static final CommandId CURSOR_END = id("tuink.cursor.end");

    public static final CommandId SEARCH_OPEN = id("tuink.search.open");
    public static final CommandId SEARCH_NEXT = id("tuink.search.next");
    public static final CommandId SEARCH_PREVIOUS = id("tuink.search.previous");
    public static final CommandId SEARCH_REPLACE = id("tuink.search.replace");
    public static final CommandId SEARCH_REPLACE_ALL = id("tuink.search.replace-all");
    public static final CommandId SEARCH_CLOSE = id("tuink.search.close");
    public static final CommandId SEARCH_FOCUS_NEXT = id("tuink.search.focus-next");
    public static final CommandId SEARCH_NAVIGATE_LEFT = id("tuink.search.navigate-left");
    public static final CommandId SEARCH_NAVIGATE_RIGHT = id("tuink.search.navigate-right");
    public static final CommandId SEARCH_NAVIGATE_UP = id("tuink.search.navigate-up");
    public static final CommandId SEARCH_NAVIGATE_DOWN = id("tuink.search.navigate-down");
    public static final CommandId SEARCH_FIELD_BACKSPACE = id("tuink.search.field-backspace");
    public static final CommandId SEARCH_FIELD_DELETE = id("tuink.search.field-delete");
    public static final CommandId SEARCH_FIELD_HOME = id("tuink.search.field-home");
    public static final CommandId SEARCH_FIELD_END = id("tuink.search.field-end");

    public static final CommandId TAB_NEW = id("tuink.tab.new");
    public static final CommandId TAB_CLOSE = id("tuink.tab.close");
    public static final CommandId TAB_NEXT = id("tuink.tab.next");
    public static final CommandId TAB_PREVIOUS = id("tuink.tab.previous");
    public static final CommandId TAB_ACTIVATE = id("tuink.tab.activate");

    public static final CommandId MENU_FILE = id("tuink.menu.file");
    public static final CommandId MENU_EDIT = id("tuink.menu.edit");
    public static final CommandId MENU_VIEW = id("tuink.menu.view");
    public static final CommandId MENU_TABS = id("tuink.menu.tabs");
    public static final CommandId MENU_SETTINGS = id("tuink.menu.settings");
    public static final CommandId MENU_HELP = id("tuink.menu.help");

    public static final CommandId VIEW_TOGGLE_TAB_BAR = id("tuink.view.toggle-tab-bar");
    public static final CommandId VIEW_TOGGLE_LINE_WRAP = id("tuink.view.toggle-line-wrap");
    public static final CommandId VIEW_TOGGLE_WORD_WRAP = id("tuink.view.toggle-word-wrap");
    public static final CommandId VIEW_TOGGLE_ACTIVE_GUTTER = id("tuink.view.toggle-active-gutter");
    public static final CommandId VIEW_TOGGLE_WHITESPACE = id("tuink.view.toggle-whitespace");
    public static final CommandId VIEW_TOGGLE_SYNTAX = id("tuink.view.toggle-syntax");
    public static final CommandId VIEW_TOGGLE_RAINBOW_BRACKETS = id("tuink.view.toggle-rainbow-brackets");
    public static final CommandId VIEW_TOGGLE_SELECTION_HIGHLIGHT = id("tuink.view.toggle-selection-highlight");
    public static final CommandId VIEW_CYCLE_GUTTER_DIFF = id("tuink.view.cycle-gutter-diff");
    public static final CommandId VIEW_CYCLE_TAB_WIDTH = id("tuink.view.cycle-tab-width");
    public static final CommandId VIEW_CYCLE_THEME = id("tuink.view.cycle-theme");

    public static final CommandId SETTINGS_TOGGLE_READING_MODE = id("tuink.settings.toggle-reading-mode");
    public static final CommandId SETTINGS_TOGGLE_MOUSE = id("tuink.settings.toggle-mouse");
    public static final CommandId SETTINGS_TOGGLE_CONFIRM_ON_CLOSE = id("tuink.settings.toggle-confirm-on-close");
    public static final CommandId SETTINGS_TOGGLE_SMART_HOME_END = id("tuink.settings.toggle-smart-home-end");
    public static final CommandId SETTINGS_TOGGLE_AUTO_INDENT = id("tuink.settings.toggle-auto-indent");
    public static final CommandId SETTINGS_TOGGLE_AUTO_PAIRS = id("tuink.settings.toggle-auto-pairs");
    public static final CommandId SETTINGS_TOGGLE_AUTO_LIST = id("tuink.settings.toggle-auto-list");
    public static final CommandId SETTINGS_CYCLE_TAB_INSERT_MODE = id("tuink.settings.cycle-tab-insert-mode");

    public static final CommandId FILE_BROWSER_TOGGLE_HIDDEN = id("tuink.file-browser.toggle-hidden");
    public static final CommandId HELP_KEY_HINTS = id("tuink.help.key-hints");
    public static final CommandId HELP_LICENSES = id("tuink.help.licenses");
    public static final CommandId HELP_AUTHOR = id("tuink.help.author");
    public static final CommandId UI_ESCAPE = id("tuink.ui.escape");

    private TuinkCommandIds() {
    }

    private static CommandId id(String value) {
        return CommandId.of(value);
    }
}
