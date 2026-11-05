package dev.tuink.tui.render;

import dev.tuink.render.Color;
import dev.tuink.render.Style;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps editor render styles to concrete terminal cell styles at the TUI edge.
 */
public final class EditorStyleAdapter {
    private static final Map<CellStyle, Style> EDITOR_STYLES = new ConcurrentHashMap<>();

    private EditorStyleAdapter() {
    }

    public static CellStyle toCell(Style style) {
        if (style == null) {
            return null;
        }
        if (style.equals(Style.DEFAULT)) {
            return CellStyle.DEFAULT;
        }
        return new CellStyle(
            toCellColor(style.fg()),
            toCellColor(style.bg()),
            style.bold(),
            style.underline()
        );
    }

    public static Style toEditor(CellStyle style) {
        if (style == null) {
            return null;
        }
        if (style.equals(CellStyle.DEFAULT)) {
            return Style.DEFAULT;
        }
        return EDITOR_STYLES.computeIfAbsent(
            style,
            value -> new Style(
                toEditorColor(value.fg()),
                toEditorColor(value.bg()),
                value.bold(),
                value.underline()
            )
        );
    }

    static CellColor toCellColor(Color color) {
        return switch (color) {
            case DEFAULT -> CellColor.DEFAULT;
            case BLACK -> CellColor.BLACK;
            case RED -> CellColor.RED;
            case GREEN -> CellColor.GREEN;
            case YELLOW -> CellColor.YELLOW;
            case BLUE -> CellColor.BLUE;
            case MAGENTA -> CellColor.MAGENTA;
            case CYAN -> CellColor.CYAN;
            case WHITE -> CellColor.WHITE;
            case BRIGHT_BLACK -> CellColor.BRIGHT_BLACK;
            case BRIGHT_RED -> CellColor.BRIGHT_RED;
            case BRIGHT_GREEN -> CellColor.BRIGHT_GREEN;
            case BRIGHT_YELLOW -> CellColor.BRIGHT_YELLOW;
            case BRIGHT_BLUE -> CellColor.BRIGHT_BLUE;
            case BRIGHT_MAGENTA -> CellColor.BRIGHT_MAGENTA;
            case BRIGHT_CYAN -> CellColor.BRIGHT_CYAN;
            case BRIGHT_WHITE -> CellColor.BRIGHT_WHITE;
            case DARK_GRAY -> CellColor.DARK_GRAY;
            case GRAY_128 -> CellColor.GRAY_128;
            case GRAY_158 -> CellColor.GRAY_158;
            case GRAY_188 -> CellColor.GRAY_188;
            case GRAY_218 -> CellColor.GRAY_218;
            case GRAY_238 -> CellColor.GRAY_238;
            case GRAY_028 -> CellColor.GRAY_028;
        };
    }

    static Color toEditorColor(CellColor color) {
        return switch (color) {
            case DEFAULT -> Color.DEFAULT;
            case BLACK -> Color.BLACK;
            case RED -> Color.RED;
            case GREEN -> Color.GREEN;
            case YELLOW -> Color.YELLOW;
            case BLUE -> Color.BLUE;
            case MAGENTA -> Color.MAGENTA;
            case CYAN -> Color.CYAN;
            case WHITE -> Color.WHITE;
            case BRIGHT_BLACK -> Color.BRIGHT_BLACK;
            case BRIGHT_RED -> Color.BRIGHT_RED;
            case BRIGHT_GREEN -> Color.BRIGHT_GREEN;
            case BRIGHT_YELLOW -> Color.BRIGHT_YELLOW;
            case BRIGHT_BLUE -> Color.BRIGHT_BLUE;
            case BRIGHT_MAGENTA -> Color.BRIGHT_MAGENTA;
            case BRIGHT_CYAN -> Color.BRIGHT_CYAN;
            case BRIGHT_WHITE -> Color.BRIGHT_WHITE;
            case DARK_GRAY -> Color.DARK_GRAY;
            case GRAY_128 -> Color.GRAY_128;
            case GRAY_158 -> Color.GRAY_158;
            case GRAY_188 -> Color.GRAY_188;
            case GRAY_218 -> Color.GRAY_218;
            case GRAY_238 -> Color.GRAY_238;
            case GRAY_028 -> Color.GRAY_028;
        };
    }
}
