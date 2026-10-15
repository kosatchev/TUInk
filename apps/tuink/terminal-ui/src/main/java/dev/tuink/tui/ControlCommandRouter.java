package dev.tuink.tui;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.EditorClipboard;
import dev.tuink.tui.command.TuinkCommand;
import java.util.Objects;
import java.util.Optional;

/** TUInk semantic control commands translated to the reusable controls' raw route. */
final class ControlCommandRouter {
    private ControlCommandRouter() {
    }

    static Optional<KeyStroke> eventFor(TuinkCommand command) {
        Objects.requireNonNull(command, "command");
        return Optional.ofNullable(switch (command) {
            case EDIT_INSERT_NEWLINE -> KeyStroke.of(KeyStroke.Key.ENTER);
            case EDIT_INSERT_TAB, SEARCH_FOCUS_NEXT -> KeyStroke.of(KeyStroke.Key.TAB);
            case UI_ESCAPE, SEARCH_CLOSE -> KeyStroke.of(KeyStroke.Key.ESCAPE);
            case EDIT_BACKSPACE, SEARCH_FIELD_BACKSPACE -> KeyStroke.of(KeyStroke.Key.BACKSPACE);
            case EDIT_DELETE_FORWARD, SEARCH_FIELD_DELETE -> KeyStroke.of(KeyStroke.Key.DELETE);
            case CURSOR_HOME, SEARCH_FIELD_HOME -> KeyStroke.of(KeyStroke.Key.HOME);
            case CURSOR_END, SEARCH_FIELD_END -> KeyStroke.of(KeyStroke.Key.END);
            case CURSOR_LEFT, SEARCH_NAVIGATE_LEFT -> KeyStroke.of(KeyStroke.Key.ARROW_LEFT);
            case CURSOR_RIGHT, SEARCH_NAVIGATE_RIGHT -> KeyStroke.of(KeyStroke.Key.ARROW_RIGHT);
            case CURSOR_UP, SEARCH_NAVIGATE_UP -> KeyStroke.of(KeyStroke.Key.ARROW_UP);
            case CURSOR_DOWN, SEARCH_NAVIGATE_DOWN -> KeyStroke.of(KeyStroke.Key.ARROW_DOWN);
            case CURSOR_SELECT_LEFT -> KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT);
            case CURSOR_SELECT_RIGHT -> KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT);
            case CURSOR_SELECT_UP -> KeyStroke.of(KeyStroke.Key.ARROW_UP, KeyStroke.Modifier.SHIFT);
            case CURSOR_SELECT_DOWN -> KeyStroke.of(KeyStroke.Key.ARROW_DOWN, KeyStroke.Modifier.SHIFT);
            case CURSOR_WORD_LEFT -> KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL);
            case CURSOR_WORD_RIGHT -> KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.CTRL);
            case CURSOR_PAGE_UP -> KeyStroke.of(KeyStroke.Key.PAGE_UP);
            case CURSOR_PAGE_DOWN -> KeyStroke.of(KeyStroke.Key.PAGE_DOWN);
            case EDIT_SELECT_ALL -> KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL);
            default -> null;
        });
    }

    static boolean owns(TuinkCommand command) {
        return command != null && (eventFor(command).isPresent() || unsupportedLocally(command)
            || command == TuinkCommand.EDIT_COPY || command == TuinkCommand.EDIT_CUT
            || command == TuinkCommand.EDIT_PASTE);
    }

    static boolean unsupportedLocally(TuinkCommand command) {
        return command == TuinkCommand.EDIT_UNDO || command == TuinkCommand.EDIT_REDO
            || command == TuinkCommand.EDIT_TOGGLE_LINE_COMMENT;
    }

    static ClipboardResult clipboard(TuinkCommand command, TextField field, EditorClipboard clipboard) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(clipboard, "clipboard");
        if (command != TuinkCommand.EDIT_COPY && command != TuinkCommand.EDIT_CUT
            && command != TuinkCommand.EDIT_PASTE) {
            return new ClipboardResult(false, false);
        }
        if (field == null) {
            return new ClipboardResult(true, false);
        }
        String before = field.text();
        FieldClipboard.handle(command, field, clipboard);
        return new ClipboardResult(true, !before.equals(field.text()));
    }

    record ClipboardResult(boolean handled, boolean valueChanged) {
    }
}
