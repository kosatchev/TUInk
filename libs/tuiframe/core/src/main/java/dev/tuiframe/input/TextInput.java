package dev.tuiframe.input;

import java.util.Objects;

/** Printable Unicode text entered normally, without command modifiers. */
public record TextInput(String text) implements TuiInputEvent {
    public TextInput {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty() || !text.codePoints().allMatch(TextInput::isPrintableScalar)) {
            throw new IllegalArgumentException("TextInput requires non-empty printable Unicode scalar text");
        }
    }

    private static boolean isPrintableScalar(int codePoint) {
        return Character.isValidCodePoint(codePoint)
            && (codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE)
            && !Character.isISOControl(codePoint);
    }
}
