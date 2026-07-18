package dev.tuink.tui.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One request to execute a command with immutable string arguments.
 *
 * <p>The invocation deliberately does not encode whether it came from a key,
 * menu, palette, macro or plugin. All of those sources can therefore use the
 * same registry path.</p>
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
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(arguments.get(name));
    }
}
