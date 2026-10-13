package dev.tuink.tui;

import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.DialogInputPolicy;
import dev.tuiframe.widget.DialogInputPolicy.Action;
import dev.tuiframe.widget.DialogInputPolicy.Decision;
import dev.tuiframe.widget.DialogInputPolicy.Target;
import dev.tuiframe.widget.DialogMode;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.SaveAsBarView;

/** TUInk's classification of commands; TUIframe decides how dialog modes handle them. */
final class DialogCommandPolicy {
    private DialogCommandPolicy() {
    }

    /** TUInk's current dialog and search controls have no local undo history. */
    static Decision decide(DialogMode mode, Target target, Action action) {
        Decision access = DialogInputPolicy.decide(mode, target, action);
        if (access == Decision.ALLOW && target != Target.CONTENT
            && (action == Action.UNDO || action == Action.REDO)) {
            return Decision.IGNORE;
        }
        return access;
    }

    static DialogMode mode(ModalInputPolicy.Kind kind) {
        return switch (kind) {
            case HELP, OPEN_FILE -> DialogMode.EXCLUSIVE;
            case NONE -> DialogMode.NON_BLOCKING;
            case SAVE_AS, CONFIRM -> DialogMode.BLOCKING;
        };
    }

    static Action action(TuiInputEvent event, TuinkCommand command) {
        if (event instanceof PasteEvent) {
            return Action.PASTE;
        }
        if (event instanceof TextInput) {
            return Action.MODIFY;
        }
        if (event instanceof PointerEvent) {
            return Action.READ;
        }
        if (command == null) {
            return Action.OTHER;
        }
        return switch (command) {
            case EDIT_COPY -> Action.COPY;
            case EDIT_CUT -> Action.CUT;
            case EDIT_PASTE -> Action.PASTE;
            case EDIT_UNDO -> Action.UNDO;
            case EDIT_REDO -> Action.REDO;
            case EDIT_SELECT_ALL, CURSOR_UP, CURSOR_DOWN, CURSOR_LEFT, CURSOR_RIGHT,
                CURSOR_SELECT_UP, CURSOR_SELECT_DOWN, CURSOR_SELECT_LEFT, CURSOR_SELECT_RIGHT,
                CURSOR_WORD_LEFT, CURSOR_WORD_RIGHT, CURSOR_PAGE_UP, CURSOR_PAGE_DOWN,
                CURSOR_HOME, CURSOR_END -> Action.READ;
            // File/tab/menu commands also stay behind a pending blocking operation.
            default -> Action.MODIFY;
        };
    }

    static Target target(ModalInputPolicy.Kind kind, OpenFileInputRouter.Focus openFocus,
        SaveAsBarView.Target saveAsFocus, boolean contentFocused) {
        if (contentFocused) {
            return Target.CONTENT;
        }
        return switch (kind) {
            case SAVE_AS -> saveAsFocus == SaveAsBarView.Target.FIELD ? Target.TEXT_INPUT : Target.BUTTON;
            case CONFIRM, HELP -> Target.BUTTON;
            case OPEN_FILE -> openFocus == OpenFileInputRouter.Focus.LIST ? Target.OTHER_CONTROL : Target.BUTTON;
            case NONE -> Target.OTHER_CONTROL;
        };
    }

    static boolean cancels(ModalInputPolicy.Kind kind, TuinkCommand command) {
        return command == TuinkCommand.UI_ESCAPE || command == TuinkCommand.APP_QUIT
            && (kind == ModalInputPolicy.Kind.SAVE_AS || kind == ModalInputPolicy.Kind.CONFIRM);
    }
}
