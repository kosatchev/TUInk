package dev.tuink.tui.ui;

import dev.tuink.tui.KeyEvent;
import dev.tuink.tui.flow.ConfirmChoice;

public final class ConfirmBarController {
    private ConfirmBarController() {
    }

    public static InputResult handleKey(KeyEvent key, ConfirmChoice selected, ConfirmChoice yesChoice) {
        ConfirmChoice current = selected == null ? ConfirmChoice.CANCEL : selected;
        if (key instanceof KeyEvent.CharInput charInput) {
            char ch = Character.toLowerCase(charInput.ch());
            if (ch == 'y') {
                return InputResult.decide(yesChoice);
            }
            if (ch == 'n') {
                return InputResult.decide(ConfirmChoice.CANCEL);
            }
            return InputResult.consumeWithoutChanges(current);
        }

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
        return switch (action) {
            case TAB, TAB_NEXT, ARROW_RIGHT -> InputResult.navigate(current.next());
            case ARROW_LEFT -> InputResult.navigate(current.previous());
            case HOME -> InputResult.navigate(ConfirmChoice.SAVE);
            case END -> InputResult.navigate(ConfirmChoice.CANCEL);
            case ENTER -> InputResult.decide(current);
            case ESCAPE, CTRL_Q -> InputResult.decide(ConfirmChoice.CANCEL);
            case TAB_CLOSE -> InputResult.decide(ConfirmChoice.DISCARD);
            default -> InputResult.consumeWithoutChanges(current);
        };
    }

    public static InputResult handleMousePress(ConfirmBarView bar, int mouseRow, int mouseCol, int topRow) {
        if (bar == null) {
            return InputResult.notConsumed(ConfirmChoice.CANCEL);
        }
        ConfirmChoice clickedChoice = bar.choiceAtMouse(mouseRow, mouseCol, topRow);
        if (clickedChoice == null) {
            return InputResult.notConsumed(ConfirmChoice.CANCEL);
        }
        return InputResult.decide(clickedChoice);
    }

    public record InputResult(
        boolean consumed,
        ConfirmChoice confirmChoice,
        ConfirmChoice decision
    ) {
        static InputResult notConsumed(ConfirmChoice choice) {
            return new InputResult(false, choice, null);
        }

        static InputResult navigate(ConfirmChoice choice) {
            return new InputResult(true, choice, null);
        }

        static InputResult decide(ConfirmChoice choice) {
            return new InputResult(true, choice, choice);
        }

        static InputResult consumeWithoutChanges(ConfirmChoice choice) {
            return new InputResult(true, choice, null);
        }
    }
}
