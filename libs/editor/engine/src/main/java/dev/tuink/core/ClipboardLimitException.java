package dev.tuink.core;

/** Clipboard text could not be prepared; the existing clipboard has not changed. */
public final class ClipboardLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ClipboardLimitException() {
        super("Not enough memory to copy this text; clipboard unchanged");
    }
}
