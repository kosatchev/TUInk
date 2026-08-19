package dev.tuink.editor.command;

import java.util.LinkedHashSet;
import java.util.List;

/** User-facing metadata and argument schema for a registered command. */
public record CommandDescriptor(
    CommandId id,
    String title,
    String description,
    List<CommandArgumentDescriptor> arguments
) {
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
        if (arguments == null) {
            throw new IllegalArgumentException("Command argument schema must not be null");
        }
        arguments = List.copyOf(arguments);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (CommandArgumentDescriptor argument : arguments) {
            if (argument == null || !names.add(argument.name())) {
                throw new IllegalArgumentException("Command argument names must be unique");
            }
        }
    }

    public CommandDescriptor(CommandId id, String title, String description) {
        this(id, title, description, List.of());
    }

    public CommandDescriptor(CommandId id, String title) {
        this(id, title, "", List.of());
    }
}
