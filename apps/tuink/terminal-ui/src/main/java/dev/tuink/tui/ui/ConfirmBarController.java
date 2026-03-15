package dev.tuink.tui.ui;

import dev.tuink.tui.KeyEvent;
import dev.tuink.tui.flow.ConfirmChoice;

public final class ConfirmBarController {
    private ConfirmBarController() {
    }

    public static InputResult handleKey(KeyEvent key, ConfirmChoice selected, ConfirmChoice yesChoice) {
        return handleKey(key, selected, yesChoice, ConfirmChoice.DISCARD, true);
    }

    public static InputResult handleKey(
        KeyEvent key,
        ConfirmChoice selected,
        ConfirmChoice yesChoice,
        ConfirmChoice noChoice,
        boolean includeCancel
    ) {
        ConfirmChoice current = normalizeSelected(selected, yesChoice, noChoice, includeCancel);
        if (key instanceof KeyEvent.CharInput charInput) {
            char ch = Character.toLowerCase(charInput.ch());
            if (ch == 'y') {
                return InputResult.decide(yesChoice);
            }
            if (ch == 'n') {
                return InputResult.decide(includeCancel ? ConfirmChoice.CANCEL : noChoice);
            }
            return InputResult.consumeWithoutChanges(current);
        }

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
        return switch (action) {
            case TAB, TAB_NEXT, ARROW_RIGHT -> InputResult.navigate(nextChoice(current, yesChoice, noChoice, includeCancel));
            case ARROW_LEFT -> InputResult.navigate(previousChoice(current, yesChoice, noChoice, includeCancel));
            case HOME -> InputResult.navigate(yesChoice);
            case END -> InputResult.navigate(includeCancel ? ConfirmChoice.CANCEL : noChoice);
            case ENTER -> InputResult.decide(current);
            case ESCAPE, CTRL_Q -> InputResult.decide(includeCancel ? ConfirmChoice.CANCEL : noChoice);
            case TAB_CLOSE -> InputResult.decide(noChoice);
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

    private static ConfirmChoice normalizeSelected(
        ConfirmChoice selected,
        ConfirmChoice yesChoice,
        ConfirmChoice noChoice,
        boolean includeCancel
    ) {
        if (selected == yesChoice || selected == noChoice || (includeCancel && selected == ConfirmChoice.CANCEL)) {
            return selected;
        }
        return yesChoice;
    }

    private static ConfirmChoice nextChoice(
        ConfirmChoice current,
        ConfirmChoice yesChoice,
        ConfirmChoice noChoice,
        boolean includeCancel
    ) {
        if (!includeCancel) {
            return current == yesChoice ? noChoice : yesChoice;
        }
        if (current == yesChoice) {
            return noChoice;
        }
        if (current == noChoice) {
            return ConfirmChoice.CANCEL;
        }
        return yesChoice;
    }

    private static ConfirmChoice previousChoice(
        ConfirmChoice current,
        ConfirmChoice yesChoice,
        ConfirmChoice noChoice,
        boolean includeCancel
    ) {
        if (!includeCancel) {
            return current == yesChoice ? noChoice : yesChoice;
        }
        if (current == yesChoice) {
            return ConfirmChoice.CANCEL;
        }
        if (current == noChoice) {
            return yesChoice;
        }
        return noChoice;
    }
}
