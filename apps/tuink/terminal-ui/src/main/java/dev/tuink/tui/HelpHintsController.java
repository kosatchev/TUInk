package dev.tuink.tui;

import dev.tuink.tui.command.TuinkCommand;

final class HelpHintsController {
    private HelpHintsController() {
    }

    static Result handle(WindowInput input, boolean helpHintsMode) {
        if (!helpHintsMode) {
            return Result.notConsumed(helpHintsMode);
        }

        if (!(input instanceof WindowInput.Command resolved)
            || !(resolved.route() instanceof CommandRouter.RoutedAction action)) {
            return new Result(true, true);
        }
        TuinkCommand command = action.action();
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
