package dev.tuink.terminal.widget;

import dev.tuink.terminal.style.CellStyle;
import java.util.Objects;

/** Resolves reusable widget roles without depending on an application palette. */
@FunctionalInterface
public interface Theme {
    /** Returns a cell style for {@code key}, or null to request resolver fallback. */
    CellStyle style(WidgetStyleRole role);

    /** Resolves with the portable default style when the theme does not know the key. */
    default CellStyle resolveStyle(WidgetStyleRole role) {
        return resolveStyle(role, CellStyle.DEFAULT);
    }

    /** Resolves with an explicit caller-owned fallback for an unknown key. */
    default CellStyle resolveStyle(WidgetStyleRole role, CellStyle fallback) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(fallback, "fallback");
        CellStyle resolved = style(role);
        return resolved == null ? fallback : resolved;
    }

    /** Creates a theme useful for single-style widgets and compatibility adapters. */
    static Theme fixed(CellStyle style) {
        CellStyle fixed = Objects.requireNonNull(style, "style");
        return key -> {
            Objects.requireNonNull(key, "key");
            return fixed;
        };
    }
}
