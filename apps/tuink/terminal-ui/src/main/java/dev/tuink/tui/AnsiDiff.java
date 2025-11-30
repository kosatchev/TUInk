package dev.tuink.tui;

import dev.tuink.tui.render.Cell;
import dev.tuink.tui.render.CellBuffer;
import dev.tuink.render.Color;
import dev.tuink.render.Style;

/**
 * Produces ANSI output for differences between two cell buffers.
 */
public final class AnsiDiff {
    private AnsiDiff() {
    }

    public static String diff(CellBuffer previous, CellBuffer current) {
        return diff(previous, current, true);
    }

    public static String diff(CellBuffer previous, CellBuffer current, boolean colorEnabled) {
        StringBuilder out = new StringBuilder();
        String activeStyleAnsi = null;
        boolean wroteAnyCell = false;

        if (previous == null || previous.rows() != current.rows() || previous.cols() != current.cols()) {
            out.append("\u001B[H");
            for (int r = 0; r < current.rows(); r++) {
                moveTo(out, r, 0);
                for (int c = 0; c < current.cols(); c++) {
                    Cell cell = current.get(r, c);
                    String styleAnsi = colorEnabled ? toAnsi(cell.style()) : toMonochromeAnsi(cell.style());
                    if (!styleAnsi.equals(activeStyleAnsi)) {
                        out.append(styleAnsi);
                        activeStyleAnsi = styleAnsi;
                    }
                    out.append(cell.grapheme());
                    wroteAnyCell = true;
                }
            }
            if (wroteAnyCell) {
                out.append("\u001B[0m");
            }
            return out.toString();
        }

        for (int r = 0; r < current.rows(); r++) {
            for (int c = 0; c < current.cols(); c++) {
                Cell next = current.get(r, c);
                Cell prev = previous.get(r, c);
                if (next.equals(prev)) {
                    continue;
                }

                moveTo(out, r, c);
                String styleAnsi = colorEnabled ? toAnsi(next.style()) : toMonochromeAnsi(next.style());
                if (!styleAnsi.equals(activeStyleAnsi)) {
                    out.append(styleAnsi);
                    activeStyleAnsi = styleAnsi;
                }
                out.append(next.grapheme());
                wroteAnyCell = true;
            }
        }

        if (wroteAnyCell) {
            out.append("\u001B[0m");
        }
        return out.toString();
    }

    private static void moveTo(StringBuilder out, int row, int col) {
        out.append("\u001B[").append(row + 1).append(';').append(col + 1).append('H');
    }

    private static String toAnsi(Style style) {
        return "\u001B["
            + fgCode(style.fg()) + ';'
            + bgCode(style.bg()) + ';'
            + (style.bold() ? "1" : "22") + ';'
            + (style.underline() ? "4" : "24") + 'm';
    }

    private static String toMonochromeAnsi(Style style) {
        return "\u001B["
            + "39;49;"
            + (style.bold() ? "1" : "22") + ';'
            + (style.underline() ? "4" : "24") + ';'
            + (style.bg() != Color.DEFAULT ? "7" : "27") + 'm';
    }

    private static int fgCode(Color color) {
        return switch (color) {
            case DEFAULT -> 39;
            case BLACK -> 30;
            case RED -> 31;
            case GREEN -> 32;
            case YELLOW -> 33;
            case BLUE -> 34;
            case MAGENTA -> 35;
            case CYAN -> 36;
            case WHITE -> 37;
            case BRIGHT_BLACK -> 90;
            case BRIGHT_RED -> 91;
            case BRIGHT_GREEN -> 92;
            case BRIGHT_YELLOW -> 93;
            case BRIGHT_BLUE -> 94;
            case BRIGHT_MAGENTA -> 95;
            case BRIGHT_CYAN -> 96;
            case BRIGHT_WHITE -> 97;
        };
    }

    private static int bgCode(Color color) {
        return switch (color) {
            case DEFAULT -> 49;
            case BLACK -> 40;
            case RED -> 41;
            case GREEN -> 42;
            case YELLOW -> 43;
            case BLUE -> 44;
            case MAGENTA -> 45;
            case CYAN -> 46;
            case WHITE -> 47;
            case BRIGHT_BLACK -> 100;
            case BRIGHT_RED -> 101;
            case BRIGHT_GREEN -> 102;
            case BRIGHT_YELLOW -> 103;
            case BRIGHT_BLUE -> 104;
            case BRIGHT_MAGENTA -> 105;
            case BRIGHT_CYAN -> 106;
            case BRIGHT_WHITE -> 107;
        };
    }
}
