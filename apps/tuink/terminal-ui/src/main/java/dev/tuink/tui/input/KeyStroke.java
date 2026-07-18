package dev.tuink.tui.input;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** A non-text key press with its physical modifiers. */
public record KeyStroke(Key key, Set<Modifier> modifiers) implements TuiInputEvent {
    public KeyStroke {
        Objects.requireNonNull(key, "key");
        modifiers = Set.copyOf(Objects.requireNonNull(modifiers, "modifiers"));
    }

    public static KeyStroke of(Key key, Modifier... modifiers) {
        Objects.requireNonNull(modifiers, "modifiers");
        return new KeyStroke(key, Set.copyOf(Arrays.asList(modifiers)));
    }

    public boolean has(Modifier modifier) {
        return modifiers.contains(modifier);
    }

    public enum Modifier {
        SHIFT,
        ALT,
        CTRL,
        SUPER
    }

    public enum Key {
        A,
        B,
        C,
        D,
        E,
        F,
        G,
        H,
        I,
        J,
        K,
        L,
        M,
        N,
        O,
        P,
        Q,
        R,
        S,
        T,
        U,
        V,
        W,
        X,
        Y,
        Z,
        DIGIT_0,
        DIGIT_1,
        DIGIT_2,
        DIGIT_3,
        DIGIT_4,
        DIGIT_5,
        DIGIT_6,
        DIGIT_7,
        DIGIT_8,
        DIGIT_9,
        SPACE,
        MINUS,
        EQUALS,
        LEFT_BRACKET,
        RIGHT_BRACKET,
        BACKSLASH,
        SEMICOLON,
        APOSTROPHE,
        GRAVE,
        COMMA,
        PERIOD,
        SLASH,
        ESCAPE,
        TAB,
        ENTER,
        BACKSPACE,
        INSERT,
        DELETE,
        ARROW_UP,
        ARROW_DOWN,
        ARROW_LEFT,
        ARROW_RIGHT,
        HOME,
        END,
        PAGE_UP,
        PAGE_DOWN,
        F1,
        F2,
        F3,
        F4,
        F5,
        F6,
        F7,
        F8,
        F9,
        F10,
        F11,
        F12,
        UNKNOWN
    }
}
