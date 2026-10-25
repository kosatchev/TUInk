package dev.tuink.editor.document;

public final class Utf16Text {
    private Utf16Text() {
    }

    public static String requireWellFormed(String text, String field) {
        if (text == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new IllegalArgumentException(field + " contains an unpaired UTF-16 surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(field + " contains an unpaired UTF-16 surrogate");
            }
        }
        return text;
    }
}
