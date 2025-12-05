package dev.tuink.tui;

/**
 * Decoded key events from terminal input.
 */
public sealed interface KeyEvent permits KeyEvent.ActionEvent, KeyEvent.CharInput, KeyEvent.MouseEvent {
    enum MouseButton {
        LEFT,
        MIDDLE,
        RIGHT,
        NONE
    }

    enum MouseKind {
        PRESS,
        RELEASE,
        DRAG,
        SCROLL_UP,
        SCROLL_DOWN
    }

    enum Action {
        CTRL_E,
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
        F10,
        CTRL_F,
        CTRL_R,
        CTRL_A,
        CTRL_G,
        CTRL_N,
        CTRL_T,
        TAB_NEXT,
        TAB_PREVIOUS,
        TAB_NEW,
        TAB_CLOSE,
        TOGGLE_TAB_BAR,
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

    record MouseEvent(int row, int col, MouseKind kind, MouseButton button) implements KeyEvent {
    }
}
