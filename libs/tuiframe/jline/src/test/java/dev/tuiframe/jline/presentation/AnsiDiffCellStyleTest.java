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
import java.util.List;
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
    void monochromeFullRedrawKeepsBlackBackgroundOrdinaryAndReversesLighterSurfaces() {
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

        String ansi = new AnsiDiff().diff(previous, current, TerminalCapabilities.DEFAULT, ScrollHint.none());

        assertEquals("\u001B[1;1H" + colorAnsi(changed) + "x" + RESET, ansi);
    }

    @Test
    void equalButDistinctCellStylesProduceNoDiff() {
        CellStyle firstStyle = new CellStyle(CellColor.CYAN, CellColor.BLUE, true, false);
        CellStyle secondStyle = new CellStyle(CellColor.CYAN, CellColor.BLUE, true, false);
        CellBuffer previous = oneCell("x", firstStyle);
        CellBuffer current = oneCell("x", secondStyle);

        assertNotSame(firstStyle, secondStyle);
        assertEquals("", new AnsiDiff().diff(previous, current, TerminalCapabilities.DEFAULT, ScrollHint.none()));
    }

    @Test
    void adjacentEqualStylesDoNotRepeatSgr() {
        CellStyle firstStyle = new CellStyle(CellColor.YELLOW, CellColor.BLUE, true, false);
        CellStyle secondStyle = new CellStyle(CellColor.YELLOW, CellColor.BLUE, true, false);
        CellBuffer current = new CellBuffer(1, 2);
        current.set(0, 0, "x", firstStyle);
        current.set(0, 1, "y", secondStyle);

        String ansi = new AnsiDiff().diff(null, current, TerminalCapabilities.DEFAULT, ScrollHint.none());

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

    @Test
    void darkGrayUsesItsOwnIndexAndFallsBackByNegotiatedDepthWithoutCacheLeakage() {
        var diff = new AnsiDiff();
        var style = new CellStyle(CellColor.DARK_GRAY, CellColor.DARK_GRAY, false, false);
        for (var depth : new ColorDepth[] {ColorDepth.ANSI_256, ColorDepth.ANSI_16,
            ColorDepth.TRUECOLOR, ColorDepth.MONOCHROME, ColorDepth.ANSI_256}) {
            String expected = switch (depth) {
                case ANSI_256 -> "\u001B[38;5;236;48;5;236;22;24;27m";
                case TRUECOLOR -> "\u001B[38;2;48;48;48;48;2;48;48;48;22;24;27m";
                case ANSI_16 -> "\u001B[30;40;22;24;27m";
                case MONOCHROME -> "\u001B[39;49;22;24;7m";
            };
            assertEquals(FULL_REDRAW_PREFIX + expected + "x" + RESET,
                diff.diff(null, oneCell("x", style), new TerminalCapabilities(depth, false, false, false),
                    ScrollHint.none()), depth.toString());
        }
    }

    @Test
    void indexedBackgroundAndGrayCommentForegroundReplayAcrossFullAndStyleOnlyFrames() {
        var capabilities = new TerminalCapabilities(ColorDepth.ANSI_256, false, false, false);
        var previous = oneCell("x", new CellStyle(CellColor.BRIGHT_BLACK, CellColor.DARK_GRAY, false, false));
        var current = oneCell("x", new CellStyle(CellColor.WHITE, CellColor.DARK_GRAY, false, false));
        var diff = new AnsiDiff();
        var terminal = new ReferenceTerminal(1, 1);
        terminal.apply(diff.diff(null, previous, capabilities, ScrollHint.none()));
        assertEquals(previous.get(0, 0), terminal.cell(0, 0));
        terminal.apply(diff.diff(previous, current, capabilities, ScrollHint.none()));
        assertEquals(current.get(0, 0), terminal.cell(0, 0));
    }

    @Test
    void neutralShadesUseExactDepthCodesAndKeepDecorationsAcrossDepthChanges() {
        record Shade(CellColor color, int level, int index, int foreground, int background) {}
        var shades = List.of(
            new Shade(CellColor.GRAY_028, 28, 234, 30, 40),
            new Shade(CellColor.DARK_GRAY, 48, 236, 30, 40),
            new Shade(CellColor.GRAY_128, 128, 244, 90, 100),
            new Shade(CellColor.GRAY_158, 158, 247, 37, 47),
            new Shade(CellColor.GRAY_188, 188, 250, 37, 47),
            new Shade(CellColor.GRAY_218, 218, 253, 97, 107),
            new Shade(CellColor.GRAY_238, 238, 255, 97, 107));
        var diff = new AnsiDiff();
        for (var shade : shades) for (boolean bold : new boolean[] {false, true})
            for (boolean underline : new boolean[] {false, true}) {
                var style = new CellStyle(shade.color(), shade.color(), bold, underline);
                // One encoder must not return the indexed cache entry for a later RGB frame, or vice versa.
                for (var depth : List.of(ColorDepth.TRUECOLOR, ColorDepth.ANSI_256, ColorDepth.ANSI_16,
                    ColorDepth.MONOCHROME, ColorDepth.TRUECOLOR, ColorDepth.ANSI_256)) {
                    String attributes = (bold ? "1" : "22") + ";" + (underline ? "4" : "24") + ";27m";
                    String expected = switch (depth) {
                        case ANSI_16 -> "\u001B[" + shade.foreground() + ";" + shade.background() + ";" + attributes;
                        case ANSI_256 -> "\u001B[38;5;" + shade.index() + ";48;5;" + shade.index() + ";" + attributes;
                        case TRUECOLOR -> "\u001B[38;2;" + shade.level() + ";" + shade.level() + ";" + shade.level()
                            + ";48;2;" + shade.level() + ";" + shade.level() + ";" + shade.level() + ";" + attributes;
                        case MONOCHROME -> monochromeAnsi(style);
                    };
                    assertEquals(FULL_REDRAW_PREFIX + expected + "x" + RESET,
                        diff.diff(null, oneCell("x", style), new TerminalCapabilities(depth, false, false, false),
                            ScrollHint.none()), shade.color() + "/" + depth + "/" + bold + "/" + underline);
                }
            }
    }

    @Test
    void grayStyleOnlyPatchesReplayDistinctForegroundBackgroundAndBoldAtDeepColorDepths() {
        var before = oneCell("x", new CellStyle(CellColor.GRAY_128, CellColor.GRAY_238, false, false));
        var after = oneCell("x", new CellStyle(CellColor.GRAY_218, CellColor.GRAY_158, true, true));
        for (var depth : List.of(ColorDepth.ANSI_256, ColorDepth.TRUECOLOR)) {
            var capabilities = new TerminalCapabilities(depth, false, false, false);
            var diff = new AnsiDiff();
            var terminal = new ReferenceTerminal(1, 1);
            terminal.apply(diff.diff(null, before, capabilities, ScrollHint.none()));
            assertEquals(before.get(0, 0), terminal.cell(0, 0));
            String patch = diff.diff(before, after, capabilities, ScrollHint.none());
            terminal.apply(patch);
            assertEquals(after.get(0, 0), terminal.cell(0, 0));
            assertTrue(patch.contains(depth == ColorDepth.TRUECOLOR
                ? "38;2;218;218;218;48;2;158;158;158;1;4;27m" : "38;5;253;48;5;247;1;4;27m"));
        }
    }

    @Test
    void explicitInverseAndItsResetReplayWithoutLeakingIntoAdjacentCells() {
        for (var depth : List.of(ColorDepth.ANSI_16, ColorDepth.ANSI_256, ColorDepth.TRUECOLOR)) {
            var cells = new CellBuffer(1, 3);
            cells.set(0, 0, "i", CellStyle.DEFAULT.inverted());
            cells.set(0, 1, "n", CellStyle.DEFAULT);
            cells.set(0, 2, "b", CellStyle.fg(CellColor.BLUE).inverted());
            var capabilities = new TerminalCapabilities(depth, false, false, false);
            var diff = new AnsiDiff(); var terminal = new ReferenceTerminal(1, 3);
            terminal.apply(diff.diff(null, cells, capabilities, ScrollHint.none()));
            for (int col = 0; col < 3; col++) assertEquals(cells.get(0, col), terminal.cell(0, col));
            var normal = new CellBuffer(1, 3); normal.set(0, 0, "i", CellStyle.DEFAULT);
            terminal.apply(diff.diff(cells, normal, capabilities, ScrollHint.none()));
            for (int col = 0; col < 3; col++) assertEquals(normal.get(0, col), terminal.cell(0, col));
        }
        for (var background : List.of(CellColor.DEFAULT, CellColor.BLUE)) {
            var base = new CellStyle(CellColor.WHITE, background, false, false);
            assertEquals(FULL_REDRAW_PREFIX + monochromeAnsi(base.inverted()) + "x" + RESET,
                fullRedraw(new AnsiDiff(), base.inverted(), false));
        }
    }

    @Test
    void blackPaletteBodyAndLightInputRetainOppositeSurfacesWithoutColor() {
        var body = new CellStyle(CellColor.GRAY_188, CellColor.BLACK, false, false);
        var field = new CellStyle(CellColor.GRAY_028, CellColor.GRAY_188, false, false);
        assertTrue(fullRedraw(new AnsiDiff(), body, false).contains(";27m"));
        assertTrue(fullRedraw(new AnsiDiff(), field, false).contains(";7m"));
        assertTrue(fullRedraw(new AnsiDiff(), body.inverted(), false).contains(";7m"));
    }

    private static String fullRedraw(AnsiDiff diff, CellStyle style, boolean colorEnabled) {
        return diff.diff(
            null,
            oneCell("x", style),
            TerminalCapabilities.DEFAULT.withColorEnabled(colorEnabled),
            ScrollHint.none()
        );
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
            + (style.underline() ? "4" : "24") + ';' + (style.inverse() ? "7" : "27") + 'm';
    }

    private static String monochromeAnsi(CellStyle style) {
        return "\u001B[39;49;"
            + (style.bold() ? "1" : "22") + ';'
            + (style.underline() ? "4" : "24") + ';'
            + ((style.bg() != CellColor.DEFAULT && style.bg() != CellColor.BLACK) ^ style.inverse() ? "7" : "27") + 'm';
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
            case DARK_GRAY, GRAY_028 -> 30;
            case GRAY_128 -> 90;
            case GRAY_158, GRAY_188 -> 37;
            case GRAY_218, GRAY_238 -> 97;
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
            case DARK_GRAY, GRAY_028 -> 40;
            case GRAY_128 -> 100;
            case GRAY_158, GRAY_188 -> 47;
            case GRAY_218, GRAY_238 -> 107;
        };
    }
}
