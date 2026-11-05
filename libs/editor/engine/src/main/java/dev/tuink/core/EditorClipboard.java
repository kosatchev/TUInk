package dev.tuink.core;

import java.util.Objects;

/** In-memory text clipboard shared by the tabs of one editor workspace. */
public final class EditorClipboard {
    private String text = "";
    private long revision;

    public String text() {
        return text;
    }

    /**
     * Opaque publication token observed after an owner-thread operation commits.
     * Compare for equality: repeated copies publish even when their text is equal,
     * and the token may wrap after exhausting its 64-bit sequence.
     */
    public long revision() {
        return revision;
    }

    public void setText(String text) {
        this.text = Objects.requireNonNull(text, "text");
        revision++;
    }

    /** Restores an unpublished command without creating a clipboard publication. */
    void restore(String text, long revision) {
        this.text = Objects.requireNonNull(text, "text");
        this.revision = revision;
    }
}
