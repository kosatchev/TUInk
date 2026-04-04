package dev.tuink.tui;

import dev.tuink.tui.input.KeyEvent;

final class HelpHintsController {
    private HelpHintsController() {
    }

    static Result handle(KeyEvent key, boolean helpHintsMode) {
        if (!helpHintsMode) {
            return Result.notConsumed(helpHintsMode);
        }

        if (key instanceof KeyEvent.CharInput) {
            return new Result(true, true);
        }

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
        return switch (action) {
            case ESCAPE, ENTER, SHOW_KEY_HINTS -> new Result(true, false);
            default -> new Result(true, true);
        };
    }

    record Result(boolean consumed, boolean helpHintsMode) {
        static Result notConsumed(boolean helpHintsMode) {
            return new Result(false, helpHintsMode);
        }
    }
}
