package dev.tuiframe.widget;

import java.util.Objects;

/**
 * Open, application-independent terminal-widget style role.
 *
 * <p>This type selects a concrete {@code CellStyle} inside a terminal theme. It
 * is intentionally distinct from future frontend-neutral decoration or
 * semantic-token keys.</p>
 */
public final class WidgetStyleRole {
    private final String name;

    public WidgetStyleRole(String name) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("widget style role name must not be blank");
        }
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof WidgetStyleRole role && name.equals(role.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
