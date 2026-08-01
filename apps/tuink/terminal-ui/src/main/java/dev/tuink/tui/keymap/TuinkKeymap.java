package dev.tuink.tui.keymap;

import static dev.tuink.tui.command.TuinkCommandIds.APP_QUIT;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_DOWN;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_END;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_HOME;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_LEFT;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_PAGE_DOWN;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_PAGE_UP;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_RIGHT;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_SELECT_DOWN;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_SELECT_LEFT;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_SELECT_RIGHT;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_SELECT_UP;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_UP;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_WORD_LEFT;
import static dev.tuink.tui.command.TuinkCommandIds.CURSOR_WORD_RIGHT;
import static dev.tuink.tui.command.TuinkCommandIds.DOCUMENT_OPEN;
import static dev.tuink.tui.command.TuinkCommandIds.DOCUMENT_SAVE;
import static dev.tuink.tui.command.TuinkCommandIds.DOCUMENT_SAVE_AS;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_BACKSPACE;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_COPY;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_CUT;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_DELETE_FORWARD;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_INSERT_NEWLINE;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_INSERT_TAB;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_PASTE;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_REDO;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_SELECT_ALL;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_TOGGLE_LINE_COMMENT;
import static dev.tuink.tui.command.TuinkCommandIds.EDIT_UNDO;
import static dev.tuink.tui.command.TuinkCommandIds.FILE_BROWSER_TOGGLE_HIDDEN;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_EDIT;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_FILE;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_HELP;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_SETTINGS;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_TABS;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_VIEW;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_OPEN;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_PREVIOUS;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_REPLACE;
import static dev.tuink.tui.command.TuinkCommandIds.TAB_NEW;
import static dev.tuink.tui.command.TuinkCommandIds.TAB_NEXT;
import static dev.tuink.tui.command.TuinkCommandIds.TAB_PREVIOUS;
import static dev.tuink.tui.command.TuinkCommandIds.UI_ESCAPE;

import dev.tuink.tui.command.CommandId;
import dev.tuink.tui.command.CommandInvocation;
import dev.tuink.terminal.input.KeyStroke;

/** Builds TUInk's default editor/global bindings from physical key strokes. */
public final class TuinkKeymap {
    private TuinkKeymap() {
    }

    public static Keymap<KeyStroke> defaults() {
        Keymap<KeyStroke> result = new Keymap<>();

        bind(result, key(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL), APP_QUIT);
        bind(result, key(KeyStroke.Key.O, KeyStroke.Modifier.CTRL), DOCUMENT_OPEN);
        bind(result, key(KeyStroke.Key.S, KeyStroke.Modifier.CTRL), DOCUMENT_SAVE);
        bind(result, key(KeyStroke.Key.K, KeyStroke.Modifier.CTRL), DOCUMENT_SAVE_AS);
        bind(result, key(KeyStroke.Key.W, KeyStroke.Modifier.CTRL), DOCUMENT_SAVE_AS);
        bind(result, key(KeyStroke.Key.S, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT), DOCUMENT_SAVE_AS);
        bind(result, key(KeyStroke.Key.F4, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT), DOCUMENT_SAVE_AS);

        bind(result, key(KeyStroke.Key.Z, KeyStroke.Modifier.CTRL), EDIT_UNDO);
        bind(result, key(KeyStroke.Key.Y, KeyStroke.Modifier.CTRL), EDIT_REDO);
        bind(result, key(KeyStroke.Key.X, KeyStroke.Modifier.CTRL), EDIT_CUT);
        bind(result, key(KeyStroke.Key.C, KeyStroke.Modifier.CTRL), EDIT_COPY);
        bind(result, key(KeyStroke.Key.V, KeyStroke.Modifier.CTRL), EDIT_PASTE);
        bind(result, key(KeyStroke.Key.A, KeyStroke.Modifier.CTRL), EDIT_SELECT_ALL);
        bind(result, key(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL), EDIT_TOGGLE_LINE_COMMENT);
        bind(result, key(KeyStroke.Key.TAB), EDIT_INSERT_TAB);
        bind(result, key(KeyStroke.Key.ENTER), EDIT_INSERT_NEWLINE);
        bind(result, key(KeyStroke.Key.BACKSPACE), EDIT_BACKSPACE);
        bind(result, key(KeyStroke.Key.DELETE), EDIT_DELETE_FORWARD);

        bind(result, key(KeyStroke.Key.F, KeyStroke.Modifier.CTRL), SEARCH_OPEN);
        bind(result, key(KeyStroke.Key.G, KeyStroke.Modifier.CTRL), SEARCH_PREVIOUS);
        bind(result, key(KeyStroke.Key.R, KeyStroke.Modifier.CTRL), SEARCH_REPLACE);
        bind(result, key(KeyStroke.Key.H, KeyStroke.Modifier.CTRL), FILE_BROWSER_TOGGLE_HIDDEN);
        bind(result, key(KeyStroke.Key.N, KeyStroke.Modifier.CTRL), TAB_NEW);
        bind(result, key(KeyStroke.Key.PAGE_DOWN, KeyStroke.Modifier.CTRL), TAB_NEXT);
        bind(result, key(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL), TAB_NEXT);
        bind(result, key(KeyStroke.Key.TAB, KeyStroke.Modifier.SUPER), TAB_NEXT);
        bind(result, key(KeyStroke.Key.PAGE_UP, KeyStroke.Modifier.CTRL), TAB_PREVIOUS);

        bind(result, key(KeyStroke.Key.ARROW_UP), CURSOR_UP);
        bind(result, key(KeyStroke.Key.ARROW_DOWN), CURSOR_DOWN);
        bind(result, key(KeyStroke.Key.ARROW_LEFT), CURSOR_LEFT);
        bind(result, key(KeyStroke.Key.ARROW_RIGHT), CURSOR_RIGHT);
        bind(result, key(KeyStroke.Key.ARROW_UP, KeyStroke.Modifier.SHIFT), CURSOR_SELECT_UP);
        bind(result, key(KeyStroke.Key.ARROW_DOWN, KeyStroke.Modifier.SHIFT), CURSOR_SELECT_DOWN);
        bind(result, key(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT), CURSOR_SELECT_LEFT);
        bind(result, key(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT), CURSOR_SELECT_RIGHT);
        bind(result, key(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL), CURSOR_WORD_LEFT);
        bind(result, key(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.CTRL), CURSOR_WORD_RIGHT);
        bind(result, key(KeyStroke.Key.B, KeyStroke.Modifier.ALT), CURSOR_WORD_LEFT);
        bind(result, key(KeyStroke.Key.F, KeyStroke.Modifier.ALT), CURSOR_WORD_RIGHT);
        bind(result, key(KeyStroke.Key.PAGE_UP), CURSOR_PAGE_UP);
        bind(result, key(KeyStroke.Key.PAGE_DOWN), CURSOR_PAGE_DOWN);
        bind(result, key(KeyStroke.Key.HOME), CURSOR_HOME);
        bind(result, key(KeyStroke.Key.END), CURSOR_END);
        bind(result, key(KeyStroke.Key.ESCAPE), UI_ESCAPE);

        bind(result, key(KeyStroke.Key.F1), MENU_FILE);
        bind(result, key(KeyStroke.Key.F2), MENU_EDIT);
        bind(result, key(KeyStroke.Key.F3), MENU_VIEW);
        bind(result, key(KeyStroke.Key.F4), MENU_TABS);
        bind(result, key(KeyStroke.Key.F5), MENU_SETTINGS);
        bind(result, key(KeyStroke.Key.F6), MENU_HELP);
        bind(result, key(KeyStroke.Key.I, KeyStroke.Modifier.ALT), MENU_FILE);
        bind(result, key(KeyStroke.Key.E, KeyStroke.Modifier.ALT), MENU_EDIT);
        bind(result, key(KeyStroke.Key.V, KeyStroke.Modifier.ALT), MENU_VIEW);
        bind(result, key(KeyStroke.Key.T, KeyStroke.Modifier.ALT), MENU_TABS);
        bind(result, key(KeyStroke.Key.S, KeyStroke.Modifier.ALT), MENU_SETTINGS);
        bind(result, key(KeyStroke.Key.H, KeyStroke.Modifier.ALT), MENU_HELP);
        return result;
    }

    private static KeyStroke key(KeyStroke.Key key, KeyStroke.Modifier... modifiers) {
        return KeyStroke.of(key, modifiers);
    }

    private static void bind(Keymap<KeyStroke> keymap, KeyStroke key, CommandId commandId) {
        keymap.bind(key, CommandInvocation.of(commandId));
    }
}
