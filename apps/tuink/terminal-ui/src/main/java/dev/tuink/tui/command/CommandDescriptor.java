package dev.tuink.tui.command;

/**
 * User-facing metadata for a registered command.
 */
public record CommandDescriptor(CommandId id, String title, String description) {
    public CommandDescriptor {
        if (id == null) {
            throw new IllegalArgumentException("Command ID must not be null");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Command title must not be blank");
        }
        if (description == null) {
            throw new IllegalArgumentException("Command description must not be null");
        }
    }

    public CommandDescriptor(CommandId id, String title) {
        this(id, title, "");
    }
}
