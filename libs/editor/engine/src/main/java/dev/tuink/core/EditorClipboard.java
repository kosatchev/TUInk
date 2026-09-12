package dev.tuink.core;

import java.util.Objects;

/** In-memory text clipboard shared by the tabs of one editor workspace. */
public final class EditorClipboard {
    private String text = "";

    public String text() {
        return text;
    }

    public void setText(String text) {
        this.text = Objects.requireNonNull(text, "text");
    }
}
