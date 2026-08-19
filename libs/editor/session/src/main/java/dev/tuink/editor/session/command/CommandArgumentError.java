package dev.tuink.editor.session.command;

import java.util.Objects;

/** One schema violation found before a command handler is invoked. */
public record CommandArgumentError(String name, Reason reason) {
    public CommandArgumentError {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Argument error name must not be blank");
        }
        Objects.requireNonNull(reason, "reason");
    }

    public enum Reason {
        UNKNOWN,
        MISSING_REQUIRED,
        INVALID_TYPE,
        OUT_OF_RANGE
    }
}
