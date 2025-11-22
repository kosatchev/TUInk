package dev.tuink.tui;

/**
 * Decoded key events from terminal input.
 */
public sealed interface KeyEvent permits KeyEvent.ActionEvent, KeyEvent.CharInput {
    enum Action {
        CTRL_Q,
        CTRL_S,
        CTRL_SHIFT_S,
        CTRL_Z,
        CTRL_Y,
        CTRL_C,
        CTRL_X,
        CTRL_V,
        CTRL_K,
        CTRL_W,
        CTRL_O,
        F2,
        CTRL_F,
        CTRL_R,
        CTRL_A,
        CTRL_G,
        ESCAPE,
        TAB,
        ARROW_UP,
        ARROW_DOWN,
        ARROW_LEFT,
        ARROW_RIGHT,
        SHIFT_ARROW_UP,
        SHIFT_ARROW_DOWN,
        SHIFT_ARROW_LEFT,
        SHIFT_ARROW_RIGHT,
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
