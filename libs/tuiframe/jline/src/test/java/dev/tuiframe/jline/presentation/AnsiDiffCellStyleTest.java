package dev.tuiframe.jline.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.capability.ColorDepth;
import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.screen.ScrollHint;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import org.junit.jupiter.api.Test;

class AnsiDiffCellStyleTest {
    private static final String FULL_REDRAW_PREFIX = "\u001B[H\u001B[1;1H";
    private static final String RESET = "\u001B[0m";

    @Test
    void fullRedrawMapsEveryCellStyleToExactColorAnsi() {
        AnsiDiff diff = new AnsiDiff();

        for (CellColor foreground : CellColor.values()) {
            for (CellColor background : CellColor.values()) {
                for (boolean bold : new boolean[] {false, true}) {
                    for (boolean underline : new boolean[] {false, true}) {
                        CellStyle style = new CellStyle(foreground, background, bold, underline);

                        assertEquals(
                            FULL_REDRAW_PREFIX + colorAnsi(style) + "x" + RESET,
                            fullRedraw(diff, style, true),
                            style.toString()
                        );
                    }
                }
            }
        }
    }

    @Test
    void monochromeFullRedrawIgnoresForegroundAndReversesOnlyNonDefaultBackgrounds() {
        AnsiDiff diff = new AnsiDiff();

        for (CellColor foreground : CellColor.values()) {
            for (CellColor background : CellColor.values()) {
                for (boolean bold : new boolean[] {false, true}) {
                    for (boolean underline : new boolean[] {false, true}) {
                        CellStyle style = new CellStyle(foreground, background, bold, underline);

                        assertEquals(
                            FULL_REDRAW_PREFIX + monochromeAnsi(style) + "x" + RESET,
                            fullRedraw(diff, style, false),
                            style.toString()
                        );
                    }
                }
            }
        }
    }

    @Test
    void styleOnlyChangeWritesAnExactPatch() {
        CellBuffer previous = oneCell("x", CellStyle.DEFAULT);
        CellStyle changed = new CellStyle(
            CellColor.BRIGHT_CYAN,
            CellColor.RED,
            true,
            true
        );
        CellBuffer current = oneCell("x", changed);

        String ansi = new AnsiDiff().diff(previous, current, true, ScrollHint.none());

        assertEquals("\u001B[1;1H" + colorAnsi(changed) + "x" + RESET, ansi);
    }

    @Test
    void equalButDistinctCellStylesProduceNoDiff() {
        CellStyle firstStyle = new CellStyle(CellColor.CYAN, CellColor.BLUE, true, false);
        CellStyle secondStyle = new CellStyle(CellColor.CYAN, CellColor.BLUE, true, false);
        CellBuffer previous = oneCell("x", firstStyle);
        CellBuffer current = oneCell("x", secondStyle);

        assertNotSame(firstStyle, secondStyle);
        assertEquals("", new AnsiDiff().diff(previous, current, true, ScrollHint.none()));
    }

    @Test
    void adjacentEqualStylesDoNotRepeatSgr() {
        CellStyle firstStyle = new CellStyle(CellColor.YELLOW, CellColor.BLUE, true, false);
        CellStyle secondStyle = new CellStyle(CellColor.YELLOW, CellColor.BLUE, true, false);
        CellBuffer current = new CellBuffer(1, 2);
        current.set(0, 0, "x", firstStyle);
        current.set(0, 1, "y", secondStyle);

        String ansi = new AnsiDiff().diff(null, current, true, ScrollHint.none());

        assertEquals(FULL_REDRAW_PREFIX + colorAnsi(firstStyle) + "xy" + RESET, ansi);
    }

    @Test
    void fullRedrawEndsWithReset() {
        String ansi = fullRedraw(new AnsiDiff(), CellStyle.DEFAULT, true);

        assertTrue(ansi.endsWith(RESET));
    }

    @Test
    void deeperColorCapabilitiesUseThePortableAnsi16CellPalette() {
        CellStyle style = new CellStyle(CellColor.BRIGHT_CYAN, CellColor.BLUE, true, false);

        for (ColorDepth depth : new ColorDepth[] {ColorDepth.ANSI_16, ColorDepth.ANSI_256, ColorDepth.TRUECOLOR}) {
            TerminalCapabilities capabilities = new TerminalCapabilities(depth, false, false, false);
            assertEquals(
                FULL_REDRAW_PREFIX + colorAnsi(style) + "x" + RESET,
                new AnsiDiff().diff(null, oneCell("x", style), capabilities, ScrollHint.none()),
                depth.toString()
            );
        }
    }

    private static String fullRedraw(AnsiDiff diff, CellStyle style, boolean colorEnabled) {
        return diff.diff(null, oneCell("x", style), colorEnabled, ScrollHint.none());
    }

    private static CellBuffer oneCell(String grapheme, CellStyle style) {
        CellBuffer buffer = new CellBuffer(1, 1);
        buffer.set(0, 0, grapheme, style);
        return buffer;
    }

    private static String colorAnsi(CellStyle style) {
        return "\u001B["
            + foregroundCode(style.fg()) + ';'
            + backgroundCode(style.bg()) + ';'
            + (style.bold() ? "1" : "22") + ';'
            + (style.underline() ? "4" : "24") + 'm';
    }

    private static String monochromeAnsi(CellStyle style) {
        return "\u001B[39;49;"
            + (style.bold() ? "1" : "22") + ';'
            + (style.underline() ? "4" : "24") + ';'
            + (style.bg() == CellColor.DEFAULT ? "27" : "7") + 'm';
    }

    private static int foregroundCode(CellColor color) {
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

    private static int backgroundCode(CellColor color) {
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
