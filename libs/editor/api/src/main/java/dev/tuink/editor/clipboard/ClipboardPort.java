package dev.tuink.editor.clipboard;

import java.util.Optional;

/**
 * A platform's plain-text clipboard. The frontend owns scheduling and must respect
 * its platform's threading rules; blocking adapters need a worker. An empty String
 * is valid clipboard content.
 */
public interface ClipboardPort {
    /** Returns no value when text is unavailable or the platform operation failed. */
    Optional<String> readText();

    /** Returns true when the platform accepted the complete text, false on failure. */
    boolean writeText(String text);
}
