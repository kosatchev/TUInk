package dev.tuink.editor.storage;

import java.io.IOException;

/** The loaded disk version no longer matches; a fresh inspected overwrite requires user approval. */
public final class ExternalFileChangedException extends IOException {
    public ExternalFileChangedException(String message) { super(message); }
}
