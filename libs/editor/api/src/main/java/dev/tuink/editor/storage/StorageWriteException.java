package dev.tuink.editor.storage;

import java.io.IOException;
import java.util.Objects;

/** A write failure whose outcome cannot be inferred from an ordinary I/O error. */
public final class StorageWriteException extends IOException {
    public enum Outcome { FAILED_BEFORE_COMMIT, UNKNOWN }

    private final Outcome outcome;
    private final DocumentResource resource;

    public StorageWriteException(String message, Throwable cause, Outcome outcome, DocumentResource resource) {
        super(message, cause);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.resource = Objects.requireNonNull(resource, "resource");
    }

    public Outcome outcome() { return outcome; }
    public DocumentResource resource() { return resource; }
}
