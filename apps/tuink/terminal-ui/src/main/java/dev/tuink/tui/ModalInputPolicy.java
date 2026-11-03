package dev.tuink.tui;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.DialogInputPolicy.Target;
import dev.tuink.tui.command.TuinkCommand;
import java.util.Objects;
import java.util.Set;

/** Classifies blocked keyboard intent for feedback; modal routers still own input handling. */
final class ModalInputPolicy {
    enum Kind {
        NONE,
        SAVE_AS,
        CONFIRM,
        HELP,
        OPEN_FILE,
        FORMAT
    }

    private static final Set<KeyStroke.Modifier> SHIFT = Set.of(KeyStroke.Modifier.SHIFT);
    private static final Set<KeyStroke.Modifier> CTRL = Set.of(KeyStroke.Modifier.CTRL);

    private ModalInputPolicy() {
    }

    static boolean blocked(Kind kind, TuiInputEvent event, TuinkCommand command, Target target) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(target, "target");
        if (kind == Kind.NONE) {
            return false;
        }
        if (event instanceof TextInput text) {
            if (target == Target.TEXT_INPUT) return false;
            return switch (kind) {
                case SAVE_AS, NONE -> false;
                case CONFIRM -> !isConfirmationText(text.text());
                case HELP -> !" ".equals(text.text()) && !isHelpCommand(command);
                case OPEN_FILE, FORMAT -> !" ".equals(text.text());
            };
        }
        if (event instanceof PasteEvent paste) {
            // Focused fields own paste; oversized content has its own rejection feedback.
            return target != Target.TEXT_INPUT && !paste.truncated();
        }
        if (!(event instanceof KeyStroke stroke) || stroke.key() == KeyStroke.Key.UNKNOWN) {
            return false;
        }
        if (kind == Kind.HELP && isHelpCommand(command)) {
            return false;
        }
        if (allowedKey(kind, stroke, target)) {
            return false;
        }
        // An unbound non-text key does not establish an attempted editor action.
        return command != null;
    }

    private static boolean allowedKey(Kind kind, KeyStroke stroke, Target target) {
        if (target == Target.TEXT_INPUT && (TextField.ownsEditingKey(stroke)
            || stroke.modifiers().equals(CTRL) && switch (stroke.key()) {
                case C, X, V -> true;
                default -> false;
            })) {
            return true;
        }
        if (stroke.modifiers().equals(SHIFT)) {
            return stroke.key() == KeyStroke.Key.TAB
                || kind == Kind.OPEN_FILE && (stroke.key() == KeyStroke.Key.ARROW_LEFT
                    || stroke.key() == KeyStroke.Key.ARROW_RIGHT);
        }
        if (stroke.modifiers().equals(CTRL)) {
            return stroke.key() == KeyStroke.Key.Q && (kind == Kind.SAVE_AS || kind == Kind.CONFIRM)
                || stroke.key() == KeyStroke.Key.H && kind == Kind.OPEN_FILE;
        }
        if (!stroke.modifiers().isEmpty()) {
            return false;
        }
        return switch (stroke.key()) {
            case TAB, ENTER, ESCAPE, HOME, END -> true;
            case ARROW_LEFT, ARROW_RIGHT -> kind != Kind.HELP;
            case BACKSPACE -> kind == Kind.SAVE_AS || kind == Kind.OPEN_FILE;
            case DELETE -> kind == Kind.SAVE_AS;
            case SPACE -> true;
            case ARROW_UP, ARROW_DOWN, PAGE_UP, PAGE_DOWN -> kind == Kind.HELP || kind == Kind.OPEN_FILE || kind == Kind.FORMAT;
            default -> false;
        };
    }

    private static boolean isConfirmationText(String text) {
        if (" ".equals(text)) {
            return true;
        }
        if (text.codePointCount(0, text.length()) != 1) {
            return false;
        }
        int codePoint = Character.toLowerCase(text.codePointAt(0));
        return codePoint == 'y' || codePoint == 'n';
    }

    private static boolean isHelpCommand(TuinkCommand command) {
        return command == TuinkCommand.HELP_KEY_HINTS
            || command == TuinkCommand.HELP_LICENSES
            || command == TuinkCommand.HELP_AUTHOR;
    }
}
