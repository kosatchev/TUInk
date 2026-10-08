package dev.tuiframe.widget;

import dev.tuiframe.style.CellStyle;
import dev.tuiframe.scroll.ScrollStyle;
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

    /** Scrollbars inherit the containing surface instead of a separate palette. */
    default ScrollStyle scrollStyle(CellStyle fallbackBody) {
        CellStyle body = resolveStyle(WidgetThemeKeys.MODAL_BODY, fallbackBody);
        CellStyle border = resolveStyle(WidgetThemeKeys.MODAL_BORDER, body);
        return ScrollStyle.fromParent(body, border);
    }

    /** Supplies the parent surface to children while retaining their widget roles. */
    default Theme withSurface(CellStyle body, CellStyle border) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(border, "border");
        return role -> {
            if (WidgetThemeKeys.MODAL_BODY.equals(role)) {
                return body;
            }
            if (WidgetThemeKeys.MODAL_BORDER.equals(role)) {
                return border;
            }
            return style(role);
        };
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
