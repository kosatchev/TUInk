package dev.tuink.editor.command;

import java.util.regex.Pattern;

/**
 * Stable, namespaced identifier for an editor or application command.
 *
 * <p>Identifiers are presentation-independent and use lower-case dot-separated
 * segments, for example {@code tuink.editor.save}.</p>
 */
public record CommandId(String value) {
    private static final int MAX_LENGTH = 200;
    private static final Pattern NAMESPACED_ID = Pattern.compile(
        "[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*(?:\\.[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*)+"
    );

    public CommandId {
        if (value == null) {
            throw new IllegalArgumentException("Command ID must not be null");
        }
        if (value.length() > MAX_LENGTH || !NAMESPACED_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Command ID must be a lower-case namespaced identifier: " + value
            );
        }
    }

    public static CommandId of(String value) {
        return new CommandId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
