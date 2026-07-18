package dev.tuink.tui.input;

import java.util.Objects;

/** Text received between terminal bracketed-paste delimiters. */
public record PasteEvent(String text, boolean truncated) implements TuiInputEvent {
    public PasteEvent {
        Objects.requireNonNull(text, "text");
    }

    /** Creates a complete, non-truncated paste event. */
    public PasteEvent(String text) {
        this(text, false);
    }
}
