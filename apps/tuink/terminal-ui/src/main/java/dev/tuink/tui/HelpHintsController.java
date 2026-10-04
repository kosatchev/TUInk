package dev.tuink.tui;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.input.EditorInput;
import dev.tuiframe.input.TextInput;

final class HelpHintsController {
    private HelpHintsController() {
    }

    static Result handle(EditorInput input, boolean helpHintsMode) {
        if (!helpHintsMode) {
            return Result.notConsumed(helpHintsMode);
        }

        if (input.event() instanceof TextInput) {
            return new Result(true, true);
        }

        TuinkCommand command = input.command();
        if (command == null) {
            return new Result(true, true);
        }
        return switch (command) {
            case UI_ESCAPE, EDIT_INSERT_NEWLINE, HELP_KEY_HINTS -> new Result(true, false);
            default -> new Result(true, true);
        };
    }

    record Result(boolean consumed, boolean helpHintsMode) {
        static Result notConsumed(boolean helpHintsMode) {
            return new Result(false, helpHintsMode);
        }
    }
}
