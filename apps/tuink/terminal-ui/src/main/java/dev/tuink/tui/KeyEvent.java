package dev.tuink.tui;

/**
 * Decoded key events from terminal input.
 */
public sealed interface KeyEvent permits KeyEvent.ActionEvent, KeyEvent.CharInput {
    enum Action {
        CTRL_Q,
        CTRL_S,
        CTRL_F,
        CTRL_G,
        ARROW_UP,
        ARROW_DOWN,
        ARROW_LEFT,
        ARROW_RIGHT,
        PAGE_UP,
        PAGE_DOWN,
        HOME,
        END,
        BACKSPACE,
        DELETE,
        ENTER,
        UNKNOWN
    }

    record ActionEvent(Action action) implements KeyEvent {
    }

    record CharInput(char ch) implements KeyEvent {
    }
}
