package dev.tuink.editor.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One presentation-independent request to execute a command.
 *
 * <p>The source is deliberately absent: keyboard, menu, palette, macro and
 * plugin contributions all use this same value.</p>
 */
public record CommandInvocation(CommandId id, Map<String, String> arguments) {
    public CommandInvocation {
        if (id == null) {
            throw new IllegalArgumentException("Command ID must not be null");
        }
        if (arguments == null) {
            throw new IllegalArgumentException("Command arguments must not be null");
        }

        Map<String, String> copy = new LinkedHashMap<>();
        arguments.forEach((name, value) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Command argument names must not be blank");
            }
            if (value == null) {
                throw new IllegalArgumentException("Command argument values must not be null");
            }
            copy.put(name, value);
        });
        arguments = Collections.unmodifiableMap(copy);
    }

    public CommandInvocation(CommandId id) {
        this(id, Map.of());
    }

    public static CommandInvocation of(CommandId id) {
        return new CommandInvocation(id);
    }

    public static CommandInvocation of(String id) {
        return new CommandInvocation(CommandId.of(id));
    }

    public Optional<String> argument(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(arguments.get(name));
    }
}
