package dev.tuink.editor.command;

import java.util.Objects;
import java.util.regex.Pattern;

/** Schema entry for one serialized command argument. */
public record CommandArgumentDescriptor(
    String name,
    CommandArgumentType type,
    boolean required,
    Integer minimumInteger,
    Integer maximumInteger
) {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*");

    public CommandArgumentDescriptor {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid command argument name: " + name);
        }
        Objects.requireNonNull(type, "type");
        if ((minimumInteger != null || maximumInteger != null)
            && type != CommandArgumentType.INTEGER) {
            throw new IllegalArgumentException("Integer bounds require an INTEGER argument");
        }
        if (minimumInteger != null
            && maximumInteger != null
            && minimumInteger > maximumInteger) {
            throw new IllegalArgumentException("Minimum integer must not exceed maximum integer");
        }
    }

    public CommandArgumentDescriptor(String name, CommandArgumentType type, boolean required) {
        this(name, type, required, null, null);
    }

    public boolean acceptsValue(String value) {
        if (!type.accepts(value)) {
            return false;
        }
        if (type != CommandArgumentType.INTEGER) {
            return true;
        }
        int parsed = Integer.parseInt(value);
        return (minimumInteger == null || parsed >= minimumInteger)
            && (maximumInteger == null || parsed <= maximumInteger);
    }
}
