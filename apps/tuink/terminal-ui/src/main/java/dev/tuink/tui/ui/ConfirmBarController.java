package dev.tuink.tui.ui;

import dev.tuink.tui.input.KeyEvent;
import dev.tuink.tui.flow.ConfirmChoice;

public final class ConfirmBarController {
    private ConfirmBarController() {
    }

    public static InputResult handleKey(KeyEvent key, ConfirmChoice selected, ConfirmChoice yesDecision) {
        return handleKey(key, selected, yesDecision, ConfirmChoice.DISCARD, true);
    }

    public static InputResult handleKey(
        KeyEvent key,
        ConfirmChoice selected,
        ConfirmChoice yesDecision,
        ConfirmChoice noDecision,
        boolean includeCancel
    ) {
        ConfirmChoice current = normalizeSelected(selected, includeCancel);
        if (key instanceof KeyEvent.CharInput charInput) {
            char ch = Character.toLowerCase(charInput.asciiCharOrZero());
            if (ch == 'y') {
                return InputResult.decide(yesDecision);
            }
            if (ch == 'n') {
                return InputResult.decide(includeCancel ? ConfirmChoice.CANCEL : noDecision);
            }
            return InputResult.consumeWithoutChanges(current);
        }

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
        // Navigation follows the rendered button order; y/n may intentionally map to different decisions.
        return switch (action) {
            case TAB, TAB_NEXT, ARROW_RIGHT -> InputResult.navigate(nextChoice(current, includeCancel));
            case ARROW_LEFT -> InputResult.navigate(previousChoice(current, includeCancel));
            case HOME -> InputResult.navigate(ConfirmChoice.SAVE);
            case END -> InputResult.navigate(includeCancel ? ConfirmChoice.CANCEL : ConfirmChoice.DISCARD);
            case ENTER -> InputResult.decide(current);
            case ESCAPE, CTRL_Q -> InputResult.decide(includeCancel ? ConfirmChoice.CANCEL : noDecision);
            case TAB_CLOSE -> InputResult.decide(noDecision);
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

    private static ConfirmChoice normalizeSelected(ConfirmChoice selected, boolean includeCancel) {
        if (selected == ConfirmChoice.SAVE
            || selected == ConfirmChoice.DISCARD
            || includeCancel && selected == ConfirmChoice.CANCEL) {
            return selected;
        }
        return ConfirmChoice.SAVE;
    }

    private static ConfirmChoice nextChoice(ConfirmChoice current, boolean includeCancel) {
        if (!includeCancel) {
            return current == ConfirmChoice.SAVE ? ConfirmChoice.DISCARD : ConfirmChoice.SAVE;
        }
        return current.next();
    }

    private static ConfirmChoice previousChoice(ConfirmChoice current, boolean includeCancel) {
        if (!includeCancel) {
            return current == ConfirmChoice.SAVE ? ConfirmChoice.DISCARD : ConfirmChoice.SAVE;
        }
        return current.previous();
    }
}
