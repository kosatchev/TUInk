package dev.tuiframe.widget;

import dev.tuiframe.style.CellStyle;
import dev.tuiframe.style.CellColor;

/** Default interaction surfaces. Themes can override each role without changing widget behavior. */
public final class ControlStyles {
    private ControlStyles() {}
    public static CellStyle accent(Theme theme) {
        return theme.resolveStyle(WidgetThemeKeys.ACCENT, new CellStyle(CellColor.BLACK, CellColor.CYAN, true, false));
    }
    /** Selection retains contrast when its list loses focus. */
    public static CellStyle listSelection(Theme theme, WidgetState state) {
        var normal = theme.resolveStyle(WidgetThemeKeys.LIST_ITEM, theme.resolveStyle(WidgetThemeKeys.MODAL_BODY));
        if (!state.enabled()) return theme.resolveStyle(WidgetThemeKeys.LIST_ITEM_DISABLED, normal);
        var focusedFallback = theme.resolveStyle(WidgetThemeKeys.ACCENT,
            theme.resolveStyle(WidgetThemeKeys.LIST_ITEM_SELECTED, accent(theme)));
        return state.focused() ? theme.resolveStyle(WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED, focusedFallback)
            : theme.resolveStyle(WidgetThemeKeys.LIST_ITEM_SELECTED, normal.inverted());
    }
    public static CellStyle button(Theme theme, WidgetState state) {
        var normal = theme.resolveStyle(WidgetThemeKeys.BUTTON, theme.resolveStyle(WidgetThemeKeys.MODAL_BODY));
        if (!state.enabled()) return theme.resolveStyle(WidgetThemeKeys.BUTTON_DISABLED, normal.emphasis(false, false));
        return state.focused() ? theme.resolveStyle(WidgetThemeKeys.BUTTON_FOCUSED, normal.inverted().emphasis(true, false)) : normal;
    }
    public static CellStyle input(Theme theme, WidgetState state) {
        var body = theme.resolveStyle(WidgetThemeKeys.MODAL_BODY);
        var normal = theme.resolveStyle(WidgetThemeKeys.TEXT_FIELD, body.inverted());
        if (!state.enabled()) return theme.resolveStyle(WidgetThemeKeys.TEXT_FIELD_DISABLED, ink(normal, CellColor.GRAY_128).emphasis(false, false));
        return state.focused() ? theme.resolveStyle(WidgetThemeKeys.TEXT_FIELD_FOCUSED, accent(theme)) : normal;
    }
    /** Changes visible ink while retaining the containing surface, including semantic inversion. */
    public static CellStyle ink(CellStyle style, CellColor color) {
        return style.inverse() ? new CellStyle(style.fg(), color, style.bold(), style.underline(), true)
            : new CellStyle(color, style.bg(), style.bold(), style.underline());
    }
    public static CellStyle placeholder(Theme theme, WidgetState state) {
        var field = input(theme, state);
        var foreground = field.inverse() ? field.bg() : field.fg();
        var muted = foreground == CellColor.BLACK || foreground == CellColor.GRAY_028 || foreground == CellColor.DEFAULT
            ? CellColor.DARK_GRAY : CellColor.GRAY_218;
        var role = !state.enabled() ? WidgetThemeKeys.TEXT_FIELD_PLACEHOLDER_DISABLED
            : state.focused() ? WidgetThemeKeys.TEXT_FIELD_PLACEHOLDER_FOCUSED : WidgetThemeKeys.TEXT_FIELD_PLACEHOLDER;
        var requested = theme.resolveStyle(role, ink(field, state.enabled() ? muted : CellColor.GRAY_128));
        return ink(field, requested.inverse() ? requested.bg() : requested.fg()).emphasis(false, false);
    }
    public static CellStyle popup(Theme theme) {
        return theme.resolveStyle(WidgetThemeKeys.POPUP_BODY, theme.resolveStyle(WidgetThemeKeys.MODAL_BODY).inverted());
    }
    public static CellStyle popupSelected(Theme theme) {
        return theme.resolveStyle(WidgetThemeKeys.POPUP_SELECTED, popup(theme).inverted().emphasis(true, true));
    }
    /** A dropdown inverts its own containing surface, independently of inherited menu styles. */
    public static CellStyle dropDownPopup(Theme theme) {
        return theme.resolveStyle(WidgetThemeKeys.MODAL_BODY).inverted();
    }
    public static CellStyle dropDownPopupSelected(Theme theme) {
        return dropDownPopup(theme).inverted().emphasis(true, true);
    }
    /** Palette headers have an accent by default; themes own the concrete normal/focused roles. */
    public static CellStyle paletteHeader(Theme theme, WidgetState state) {
        var body = theme.resolveStyle(WidgetThemeKeys.MODAL_BODY);
        var normal = theme.resolveStyle(WidgetThemeKeys.PALETTE_HEADER,
            new CellStyle(CellColor.CYAN, body.bg(), true, false, body.inverse()));
        if (!state.enabled()) return normal.emphasis(false, false);
        return state.focused() ? theme.resolveStyle(WidgetThemeKeys.PALETTE_HEADER_FOCUSED,
            normal.inverted().emphasis(true, true)) : normal;
    }
}
