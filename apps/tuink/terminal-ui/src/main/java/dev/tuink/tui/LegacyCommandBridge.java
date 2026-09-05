package dev.tuink.tui;

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
import static dev.tuink.tui.command.TuinkCommandIds.HELP_AUTHOR;
import static dev.tuink.tui.command.TuinkCommandIds.HELP_KEY_HINTS;
import static dev.tuink.tui.command.TuinkCommandIds.HELP_LICENSES;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_EDIT;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_FILE;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_HELP;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_SETTINGS;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_TABS;
import static dev.tuink.tui.command.TuinkCommandIds.MENU_VIEW;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_CLOSE;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_FIELD_BACKSPACE;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_FIELD_DELETE;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_FIELD_END;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_FIELD_HOME;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_FOCUS_NEXT;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_NAVIGATE_DOWN;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_NAVIGATE_LEFT;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_NAVIGATE_RIGHT;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_NAVIGATE_UP;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_NEXT;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_OPEN;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_PREVIOUS;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_REPLACE;
import static dev.tuink.tui.command.TuinkCommandIds.SEARCH_REPLACE_ALL;
import static dev.tuink.tui.command.TuinkCommandIds.SETTINGS_TOGGLE_AUTO_INDENT;
import static dev.tuink.tui.command.TuinkCommandIds.SETTINGS_TOGGLE_AUTO_LIST;
import static dev.tuink.tui.command.TuinkCommandIds.SETTINGS_TOGGLE_AUTO_PAIRS;
import static dev.tuink.tui.command.TuinkCommandIds.SETTINGS_TOGGLE_CONFIRM_ON_CLOSE;
import static dev.tuink.tui.command.TuinkCommandIds.SETTINGS_TOGGLE_MOUSE;
import static dev.tuink.tui.command.TuinkCommandIds.SETTINGS_TOGGLE_READING_MODE;
import static dev.tuink.tui.command.TuinkCommandIds.SETTINGS_TOGGLE_SMART_HOME_END;
import static dev.tuink.tui.command.TuinkCommandIds.SETTINGS_CYCLE_TAB_INSERT_MODE;
import static dev.tuink.tui.command.TuinkCommandIds.TAB_CLOSE;
import static dev.tuink.tui.command.TuinkCommandIds.TAB_ACTIVATE;
import static dev.tuink.tui.command.TuinkCommandIds.TAB_NEW;
import static dev.tuink.tui.command.TuinkCommandIds.TAB_NEXT;
import static dev.tuink.tui.command.TuinkCommandIds.TAB_PREVIOUS;
import static dev.tuink.tui.command.TuinkCommandIds.UI_ESCAPE;
import static dev.tuink.tui.command.TuinkCommandIds.VIEW_TOGGLE_ACTIVE_GUTTER;
import static dev.tuink.tui.command.TuinkCommandIds.VIEW_CYCLE_GUTTER_DIFF;
import static dev.tuink.tui.command.TuinkCommandIds.VIEW_TOGGLE_LINE_WRAP;
import static dev.tuink.tui.command.TuinkCommandIds.VIEW_TOGGLE_RAINBOW_BRACKETS;
import static dev.tuink.tui.command.TuinkCommandIds.VIEW_TOGGLE_SELECTION_HIGHLIGHT;
import static dev.tuink.tui.command.TuinkCommandIds.VIEW_TOGGLE_SYNTAX;
import static dev.tuink.tui.command.TuinkCommandIds.VIEW_TOGGLE_TAB_BAR;
import static dev.tuink.tui.command.TuinkCommandIds.VIEW_CYCLE_TAB_WIDTH;
import static dev.tuink.tui.command.TuinkCommandIds.VIEW_CYCLE_THEME;
import static dev.tuink.tui.command.TuinkCommandIds.VIEW_TOGGLE_WHITESPACE;
import static dev.tuink.tui.command.TuinkCommandIds.VIEW_TOGGLE_WORD_WRAP;

import dev.tuink.editor.command.CommandDescriptor;
import dev.tuink.editor.command.CommandArgumentDescriptor;
import dev.tuink.editor.command.CommandArgumentType;
import dev.tuink.editor.command.CommandId;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.editor.session.command.CommandRegistry;
import dev.tuink.editor.session.command.InvocationResult;
import dev.tuink.tui.input.KeyEvent;
import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.tui.keymap.Keymap;
import dev.tuink.tui.keymap.TuinkKeymap;
import dev.tuink.tui.flow.SearchFocus;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Temporary command adapter around the existing {@link KeyEvent.Action}
 * handlers. It lets physical keys and menus share a semantic registry path
 * while the editor actions are migrated incrementally.
 */
@SuppressWarnings("removal")
final class LegacyCommandBridge {
    private final Keymap<KeyStroke> keymap;
    private final CommandRegistry<ExecutionContext, RoutedCommand> registry = new CommandRegistry<>();
    private final Map<KeyEvent.Action, CommandInvocation> legacyInvocations =
        new EnumMap<>(KeyEvent.Action.class);

    LegacyCommandBridge() {
        this(TuinkKeymap.defaults());
    }

    LegacyCommandBridge(Keymap<KeyStroke> keymap) {
        this.keymap = keymap;
        registerBuiltins();
    }

    Optional<CommandInvocation> invocationFor(KeyStroke stroke) {
        return keymap.resolve(stroke);
    }

    Optional<CommandInvocation> invocationFor(KeyEvent.Action action) {
        return Optional.ofNullable(legacyInvocations.get(action));
    }

    CommandInvocation tabActivationInvocation(int tabIndex) {
        if (tabIndex < 0) {
            throw new IllegalArgumentException("tabIndex must be non-negative");
        }
        return new CommandInvocation(TAB_ACTIVATE, Map.of("index", Integer.toString(tabIndex)));
    }

    CommandInvocation invocationForSearch(
        KeyEvent.Action action,
        SearchFocus focus,
        CommandInvocation globalInvocation
    ) {
        CommandId focusedId = switch (action) {
            case ESCAPE -> SEARCH_CLOSE;
            case TAB -> SEARCH_FOCUS_NEXT;
            case BACKSPACE -> SEARCH_FIELD_BACKSPACE;
            case DELETE -> SEARCH_FIELD_DELETE;
            case ARROW_LEFT -> SEARCH_NAVIGATE_LEFT;
            case ARROW_RIGHT -> SEARCH_NAVIGATE_RIGHT;
            case ARROW_UP -> SEARCH_NAVIGATE_UP;
            case ARROW_DOWN -> SEARCH_NAVIGATE_DOWN;
            case HOME -> SearchController.isSearchFieldFocus(focus) ? SEARCH_FIELD_HOME : null;
            case END -> SearchController.isSearchFieldFocus(focus) ? SEARCH_FIELD_END : null;
            case ENTER -> searchActionId(SearchController.actionForSearchFocus(focus));
            case CTRL_F -> SEARCH_NEXT;
            case CTRL_G -> SEARCH_PREVIOUS;
            case CTRL_R -> SEARCH_REPLACE;
            case CTRL_A -> SEARCH_REPLACE_ALL;
            default -> null;
        };
        if (focusedId != null) {
            return CommandInvocation.of(focusedId);
        }
        if (globalInvocation != null) {
            return globalInvocation;
        }
        return invocationFor(action).orElse(null);
    }

    Optional<KeyEvent.Action> invoke(CommandInvocation invocation) {
        Optional<RoutedCommand> routed = dispatch(invocation);
        if (routed.isPresent() && routed.get() instanceof RoutedAction action) {
            return Optional.of(action.action());
        }
        return Optional.empty();
    }

    OptionalInt invokeTabActivation(CommandInvocation invocation) {
        Optional<RoutedCommand> routed = dispatch(invocation);
        if (routed.isPresent() && routed.get() instanceof ActivateTab activateTab) {
            return OptionalInt.of(activateTab.tabIndex());
        }
        return OptionalInt.empty();
    }

    KeyEvent.Action resolve(CommandInvocation invocation, KeyEvent.Action fallback) {
        if (invocation == null) {
            return fallback;
        }
        return invoke(invocation).orElse(KeyEvent.Action.UNKNOWN);
    }

    int commandCount() {
        return registry.size();
    }

    private void registerBuiltins() {
        bind(KeyEvent.Action.CTRL_Q, APP_QUIT, "Quit");
        bind(KeyEvent.Action.CTRL_O, DOCUMENT_OPEN, "Open document");
        bind(KeyEvent.Action.CTRL_S, DOCUMENT_SAVE, "Save document");
        bind(KeyEvent.Action.CTRL_K, DOCUMENT_SAVE_AS, "Save document as");
        alias(KeyEvent.Action.CTRL_W, DOCUMENT_SAVE_AS);
        alias(KeyEvent.Action.CTRL_SHIFT_S, DOCUMENT_SAVE_AS);

        bind(KeyEvent.Action.CTRL_Z, EDIT_UNDO, "Undo");
        bind(KeyEvent.Action.CTRL_Y, EDIT_REDO, "Redo");
        bind(KeyEvent.Action.CTRL_X, EDIT_CUT, "Cut");
        bind(KeyEvent.Action.CTRL_C, EDIT_COPY, "Copy");
        bind(KeyEvent.Action.CTRL_V, EDIT_PASTE, "Paste");
        bind(KeyEvent.Action.CTRL_A, EDIT_SELECT_ALL, "Select all");
        bind(KeyEvent.Action.TOGGLE_LINE_COMMENT, EDIT_TOGGLE_LINE_COMMENT, "Toggle line comment");
        bind(KeyEvent.Action.TAB, EDIT_INSERT_TAB, "Insert tab");
        bind(KeyEvent.Action.ENTER, EDIT_INSERT_NEWLINE, "Insert newline");
        bind(KeyEvent.Action.BACKSPACE, EDIT_BACKSPACE, "Backspace");
        bind(KeyEvent.Action.DELETE, EDIT_DELETE_FORWARD, "Delete forward");

        bind(KeyEvent.Action.ARROW_UP, CURSOR_UP, "Move cursor up");
        bind(KeyEvent.Action.ARROW_DOWN, CURSOR_DOWN, "Move cursor down");
        bind(KeyEvent.Action.ARROW_LEFT, CURSOR_LEFT, "Move cursor left");
        bind(KeyEvent.Action.ARROW_RIGHT, CURSOR_RIGHT, "Move cursor right");
        bind(KeyEvent.Action.SHIFT_ARROW_UP, CURSOR_SELECT_UP, "Select up");
        bind(KeyEvent.Action.SHIFT_ARROW_DOWN, CURSOR_SELECT_DOWN, "Select down");
        bind(KeyEvent.Action.SHIFT_ARROW_LEFT, CURSOR_SELECT_LEFT, "Select left");
        bind(KeyEvent.Action.SHIFT_ARROW_RIGHT, CURSOR_SELECT_RIGHT, "Select right");
        bind(KeyEvent.Action.WORD_LEFT, CURSOR_WORD_LEFT, "Move one word left");
        bind(KeyEvent.Action.WORD_RIGHT, CURSOR_WORD_RIGHT, "Move one word right");
        bind(KeyEvent.Action.PAGE_UP, CURSOR_PAGE_UP, "Page up");
        bind(KeyEvent.Action.PAGE_DOWN, CURSOR_PAGE_DOWN, "Page down");
        bind(KeyEvent.Action.HOME, CURSOR_HOME, "Move to line start");
        bind(KeyEvent.Action.END, CURSOR_END, "Move to line end");

        bind(KeyEvent.Action.CTRL_F, SEARCH_OPEN, "Open search");
        bind(KeyEvent.Action.CTRL_G, SEARCH_PREVIOUS, "Previous match");
        bind(KeyEvent.Action.CTRL_R, SEARCH_REPLACE, "Replace");
        bind(KeyEvent.Action.CTRL_H, FILE_BROWSER_TOGGLE_HIDDEN, "Toggle hidden files");

        bind(KeyEvent.Action.TAB_NEW, TAB_NEW, "New tab");
        alias(KeyEvent.Action.CTRL_N, TAB_NEW);
        bind(KeyEvent.Action.TAB_CLOSE, TAB_CLOSE, "Close tab");
        bind(KeyEvent.Action.TAB_NEXT, TAB_NEXT, "Next tab");
        bind(KeyEvent.Action.TAB_PREVIOUS, TAB_PREVIOUS, "Previous tab");
        registry.register(
            new CommandDescriptor(
                TAB_ACTIVATE,
                "Activate tab",
                "",
                List.of(new CommandArgumentDescriptor(
                    "index",
                    CommandArgumentType.INTEGER,
                    true,
                    0,
                    null
                ))
            ),
            (request, context) -> new ActivateTab(requiredTabIndex(request))
        );

        bind(KeyEvent.Action.F10, MENU_FILE, "Open File menu");
        bind(KeyEvent.Action.CTRL_E, MENU_EDIT, "Open Edit menu");
        bind(KeyEvent.Action.OPEN_VIEW_MENU, MENU_VIEW, "Open View menu");
        bind(KeyEvent.Action.CTRL_T, MENU_TABS, "Open Tab menu");
        bind(KeyEvent.Action.F2, MENU_SETTINGS, "Open Settings menu");
        bind(KeyEvent.Action.OPEN_HELP_MENU, MENU_HELP, "Open Help menu");

        bind(KeyEvent.Action.TOGGLE_TAB_BAR, VIEW_TOGGLE_TAB_BAR, "Toggle tab bar");
        bind(KeyEvent.Action.TOGGLE_WRAP_LINES, VIEW_TOGGLE_LINE_WRAP, "Toggle line wrapping");
        bind(KeyEvent.Action.TOGGLE_WORD_WRAP, VIEW_TOGGLE_WORD_WRAP, "Toggle word wrapping");
        bind(
            KeyEvent.Action.TOGGLE_ACTIVE_GUTTER_HIGHLIGHT,
            VIEW_TOGGLE_ACTIVE_GUTTER,
            "Toggle active gutter highlight"
        );
        bind(KeyEvent.Action.TOGGLE_VISIBLE_WHITESPACE, VIEW_TOGGLE_WHITESPACE, "Toggle visible whitespace");
        bind(KeyEvent.Action.TOGGLE_SYNTAX_HIGHLIGHTING, VIEW_TOGGLE_SYNTAX, "Toggle syntax highlighting");
        bind(
            KeyEvent.Action.TOGGLE_RAINBOW_BRACKETS,
            VIEW_TOGGLE_RAINBOW_BRACKETS,
            "Toggle rainbow brackets"
        );
        bind(
            KeyEvent.Action.TOGGLE_AUTO_SELECTION_HIGHLIGHT,
            VIEW_TOGGLE_SELECTION_HIGHLIGHT,
            "Toggle selection highlight"
        );
        bind(KeyEvent.Action.TOGGLE_GUTTER_DIFF, VIEW_CYCLE_GUTTER_DIFF, "Cycle gutter diff");
        bind(KeyEvent.Action.TOGGLE_TAB_WIDTH, VIEW_CYCLE_TAB_WIDTH, "Cycle tab width");
        bind(KeyEvent.Action.TOGGLE_THEME, VIEW_CYCLE_THEME, "Cycle theme");

        bind(
            KeyEvent.Action.TOGGLE_READING_MODE,
            SETTINGS_TOGGLE_READING_MODE,
            "Toggle reading mode"
        );
        bind(KeyEvent.Action.TOGGLE_MOUSE_MODE, SETTINGS_TOGGLE_MOUSE, "Toggle mouse input");
        bind(
            KeyEvent.Action.TOGGLE_CONFIRM_ON_CLOSE,
            SETTINGS_TOGGLE_CONFIRM_ON_CLOSE,
            "Toggle close confirmation"
        );
        bind(
            KeyEvent.Action.TOGGLE_SMART_HOME_END,
            SETTINGS_TOGGLE_SMART_HOME_END,
            "Toggle smart Home and End"
        );
        bind(KeyEvent.Action.TOGGLE_AUTO_INDENT, SETTINGS_TOGGLE_AUTO_INDENT, "Toggle auto indent");
        bind(KeyEvent.Action.TOGGLE_AUTO_PAIRS, SETTINGS_TOGGLE_AUTO_PAIRS, "Toggle auto pairs");
        bind(KeyEvent.Action.TOGGLE_AUTO_LIST, SETTINGS_TOGGLE_AUTO_LIST, "Toggle auto list");
        bind(
            KeyEvent.Action.TOGGLE_TAB_INSERT_MODE,
            SETTINGS_CYCLE_TAB_INSERT_MODE,
            "Cycle tab insert mode"
        );

        bind(KeyEvent.Action.SHOW_KEY_HINTS, HELP_KEY_HINTS, "Show key hints");
        bind(KeyEvent.Action.SHOW_LICENSES, HELP_LICENSES, "Show licenses");
        bind(KeyEvent.Action.SHOW_AUTHOR, HELP_AUTHOR, "Show author");
        bind(KeyEvent.Action.ESCAPE, UI_ESCAPE, "Escape");

        registerFocused(SEARCH_NEXT, "Find next", KeyEvent.Action.CTRL_F);
        registerFocused(SEARCH_REPLACE_ALL, "Replace all", KeyEvent.Action.CTRL_A);
        registerFocused(SEARCH_CLOSE, "Close search", KeyEvent.Action.ESCAPE);
        registerFocused(SEARCH_FOCUS_NEXT, "Move search focus", KeyEvent.Action.TAB);
        registerFocused(SEARCH_NAVIGATE_LEFT, "Navigate search left", KeyEvent.Action.ARROW_LEFT);
        registerFocused(SEARCH_NAVIGATE_RIGHT, "Navigate search right", KeyEvent.Action.ARROW_RIGHT);
        registerFocused(SEARCH_NAVIGATE_UP, "Navigate search up", KeyEvent.Action.ARROW_UP);
        registerFocused(SEARCH_NAVIGATE_DOWN, "Navigate search down", KeyEvent.Action.ARROW_DOWN);
        registerFocused(SEARCH_FIELD_BACKSPACE, "Delete search text backward", KeyEvent.Action.BACKSPACE);
        registerFocused(SEARCH_FIELD_DELETE, "Delete search text forward", KeyEvent.Action.DELETE);
        registerFocused(SEARCH_FIELD_HOME, "Move to start of search field", KeyEvent.Action.HOME);
        registerFocused(SEARCH_FIELD_END, "Move to end of search field", KeyEvent.Action.END);
    }

    private static CommandId searchActionId(KeyEvent.Action action) {
        if (action == KeyEvent.Action.CTRL_G) {
            return SEARCH_PREVIOUS;
        }
        if (action == KeyEvent.Action.CTRL_R) {
            return SEARCH_REPLACE;
        }
        if (action == KeyEvent.Action.CTRL_A) {
            return SEARCH_REPLACE_ALL;
        }
        return SEARCH_NEXT;
    }

    private void bind(KeyEvent.Action action, CommandId id, String title) {
        CommandInvocation invocation = CommandInvocation.of(id);
        legacyInvocations.put(action, invocation);
        registry.register(new CommandDescriptor(id, title), (request, context) -> new RoutedAction(action));
    }

    private void alias(KeyEvent.Action action, CommandId id) {
        if (!registry.contains(id)) {
            throw new IllegalStateException("Canonical command must be registered before aliases: " + id);
        }
        legacyInvocations.put(action, CommandInvocation.of(id));
    }

    private void registerFocused(CommandId id, String title, KeyEvent.Action legacyAction) {
        registry.register(
            new CommandDescriptor(id, title),
            (request, context) -> new RoutedAction(legacyAction)
        );
    }

    /** Executes a semantic handler once and exposes its legacy route to the application adapter. */
    Optional<RoutedCommand> dispatch(CommandInvocation invocation) {
        InvocationResult<RoutedCommand> result = registry.invoke(invocation, ExecutionContext.INSTANCE);
        if (result instanceof InvocationResult.Executed<?> executed) {
            return Optional.of((RoutedCommand) executed.result());
        }
        return Optional.empty();
    }

    private static int requiredTabIndex(CommandInvocation invocation) {
        String value = invocation.argument("index").orElseThrow(
            () -> new IllegalArgumentException("Tab activation requires an index")
        );
        try {
            int index = Integer.parseInt(value);
            if (index < 0) {
                throw new IllegalArgumentException("Tab activation index must be non-negative");
            }
            return index;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid tab activation index: " + value, e);
        }
    }

    sealed interface RoutedCommand permits RoutedAction, ActivateTab {
    }

    record RoutedAction(KeyEvent.Action action) implements RoutedCommand {
    }

    record ActivateTab(int tabIndex) implements RoutedCommand {
    }

    private enum ExecutionContext {
        INSTANCE
    }
}
