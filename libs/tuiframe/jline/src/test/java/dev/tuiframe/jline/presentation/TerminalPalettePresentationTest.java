package dev.tuiframe.jline.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.capability.ColorDepth;
import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.screen.Frame;
import dev.tuiframe.screen.ScreenCursor;
import dev.tuiframe.screen.ScrollHint;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TerminalPalettePresentationTest {
    private static final List<CellColor> ANSI_COLORS = List.of(
        CellColor.BLACK, CellColor.RED, CellColor.GREEN, CellColor.YELLOW,
        CellColor.BLUE, CellColor.MAGENTA, CellColor.CYAN, CellColor.WHITE,
        CellColor.BRIGHT_BLACK, CellColor.BRIGHT_RED, CellColor.BRIGHT_GREEN, CellColor.BRIGHT_YELLOW,
        CellColor.BRIGHT_BLUE, CellColor.BRIGHT_MAGENTA, CellColor.BRIGHT_CYAN, CellColor.BRIGHT_WHITE);

    @Test
    void defaultAndEveryCustomAnsiForegroundKeepTheirColorWhenBoldAtDeepColorDepths() {
        var palette = new TerminalPalette();
        assertTrue(palette.acceptOsc("10;rgb:5f/87/af"));
        for (int index = 0; index < ANSI_COLORS.size(); index++)
            assertTrue(palette.acceptOsc("4;" + index + ";" + rgbReply(cubeRgb(100 + index))));

        var colors = new ArrayList<>(ANSI_COLORS);
        colors.addFirst(CellColor.DEFAULT);
        for (var depth : List.of(ColorDepth.TRUECOLOR, ColorDepth.ANSI_256)) {
            var diff = new AnsiDiff(palette);
            for (var color : colors) {
                int expectedIndex = color == CellColor.DEFAULT ? 67 : 100 + ANSI_COLORS.indexOf(color);
                int expectedRgb = cubeRgb(expectedIndex);
                var cells = new CellBuffer(1, 2);
                cells.set(0, 0, "p", new CellStyle(color, CellColor.DEFAULT, false, false));
                cells.set(0, 1, "b", new CellStyle(color, CellColor.DEFAULT, true, false));
                String output = diff.diff(null, cells, capabilities(depth), ScrollHint.none());
                var paints = paints(output);
                String context = depth + "/" + color;

                assertEquals(2, paints.size(), context);
                var plain = paints.get(0);
                var bold = paints.get(1);
                assertEquals(plain.foreground(), bold.foreground(), context);
                assertEquals(depth == ColorDepth.TRUECOLOR ? rgbColor(expectedRgb) : "indexed:" + expectedIndex,
                    plain.foreground(), context);
                assertEquals("default", plain.background(), context);
                assertEquals(plain.background(), bold.background(), context);
                assertFalse(plain.bold(), context);
                assertTrue(bold.bold(), context);
                assertFalse(output.contains("\u001B]"), "Painting must not change or query the terminal palette");
            }
        }
    }

    @Test
    void styleOnlyBoldChangesKeepTheResolvedForegroundAndOriginalBackground() {
        var palette = new TerminalPalette();
        assertTrue(palette.acceptOsc("10;rgb:1234/abcd/5678"));
        var diff = new AnsiDiff(palette);
        var capabilities = capabilities(ColorDepth.TRUECOLOR);
        var plain = oneCell(new CellStyle(CellColor.DEFAULT, CellColor.BLUE, false, true));
        var bold = oneCell(new CellStyle(CellColor.DEFAULT, CellColor.BLUE, true, true));

        var initial = onlyPaint(diff.diff(null, plain, capabilities, ScrollHint.none()));
        String boldPatch = diff.diff(plain, bold, capabilities, ScrollHint.none());
        String plainPatch = diff.diff(bold, plain, capabilities, ScrollHint.none());
        var changed = onlyPaint(boldPatch);
        var restored = onlyPaint(plainPatch);

        assertEquals("rgb:18:171:86", initial.foreground(), "16-bit OSC components must resolve to their 8-bit colors");
        assertEquals(initial.foreground(), changed.foreground());
        assertEquals(initial.foreground(), restored.foreground());
        assertEquals("ansi:4", initial.background());
        assertEquals(initial.background(), changed.background());
        assertEquals(initial.background(), restored.background());
        assertFalse(initial.bold());
        assertTrue(changed.bold());
        assertFalse(restored.bold());
        assertTrue(initial.underline() && changed.underline() && restored.underline());
        assertFalse(boldPatch.startsWith("\u001B[H"), "A bold change alone is an ordinary cell patch");
        assertFalse(plainPatch.startsWith("\u001B[H"));
    }

    @Test
    void presentersKeepCustomForegroundsLocalToTheirOwnTerminalSession() {
        var firstPalette = new TerminalPalette();
        var secondPalette = new TerminalPalette();
        assertTrue(firstPalette.acceptOsc("10;rgb:12/34/56"));
        assertTrue(secondPalette.acceptOsc("10;rgb:65/43/21"));
        assertTrue(firstPalette.acceptOsc("4;4;rgb:22/44/66"));
        assertTrue(secondPalette.acceptOsc("4;4;rgb:55/77/99"));
        var firstOutput = new StringWriter();
        var secondOutput = new StringWriter();
        var first = new ScreenPresenter(new PrintWriter(firstOutput), capabilities(ColorDepth.TRUECOLOR), firstPalette);
        var second = new ScreenPresenter(new PrintWriter(secondOutput), capabilities(ColorDepth.TRUECOLOR), secondPalette);
        var cells = new CellBuffer(1, 2);
        cells.set(0, 0, "p", CellStyle.DEFAULT);
        cells.set(0, 1, "b", new CellStyle(CellColor.BLUE, CellColor.DEFAULT, true, false));
        var frame = frame(cells);

        first.present(frame);
        second.present(frame);
        assertEquals(List.of("rgb:18:52:86", "rgb:34:68:102"), foregrounds(firstOutput.toString()));
        assertEquals(List.of("rgb:101:67:33", "rgb:85:119:153"), foregrounds(secondOutput.toString()));

        firstOutput.getBuffer().setLength(0);
        first.invalidate();
        first.present(frame);
        assertEquals(List.of("rgb:18:52:86", "rgb:34:68:102"), foregrounds(firstOutput.toString()),
            "Warming another terminal's color cache must not change this session's palette");
    }

    @Test
    void paletteRevisionRedrawsIdenticalCellsAndInvalidatesTheirResolvedStyleCache() {
        for (var color : List.of(CellColor.DEFAULT, CellColor.BLUE)) {
            var palette = new TerminalPalette();
            String target = color == CellColor.DEFAULT ? "10;" : "4;4;";
            assertTrue(palette.acceptOsc(target + "rgb:5f/87/af"));
            var output = new StringWriter();
            var presenter = new ScreenPresenter(new PrintWriter(output), capabilities(ColorDepth.TRUECOLOR), palette);
            var frame = frame(oneCell(new CellStyle(color, CellColor.DEFAULT, true, false)));
            presenter.present(frame);
            assertEquals("rgb:95:135:175", onlyPaint(output.toString()).foreground());
            output.getBuffer().setLength(0);
            assertFalse(presenter.present(frame).updated());
            assertEquals("", output.toString());

            long before = palette.revision();
            assertTrue(palette.acceptOsc(target + "rgb:af/87/5f"));
            assertNotEquals(before, palette.revision());
            var changed = presenter.present(frame);
            assertTrue(changed.updated(), color.toString());
            assertTrue(changed.fullRedraw(), color.toString());
            assertEquals("rgb:175:135:95", onlyPaint(output.toString()).foreground(),
                "The previous revision's cached SGR must not survive a palette reply");

            output.getBuffer().setLength(0);
            assertFalse(presenter.present(frame).updated());
            long stable = palette.revision();
            assertFalse(palette.acceptOsc(target + "rgb:af/87/5f"));
            assertEquals(stable, palette.revision(), "A repeated reply does not change the effective palette");
            assertFalse(presenter.present(frame).updated());
            assertEquals("", output.toString());
        }
    }

    @Test
    void directDiffAlsoRedrawsEqualCellsWhenItsPaletteChanges() {
        var palette = new TerminalPalette();
        assertTrue(palette.acceptOsc("10;rgb:5f/87/af"));
        var diff = new AnsiDiff(palette);
        var cells = oneCell(CellStyle.DEFAULT);
        var capabilities = capabilities(ColorDepth.ANSI_256);
        assertEquals("indexed:67", onlyPaint(diff.diff(null, cells, capabilities, ScrollHint.none())).foreground());
        assertEquals("", diff.diff(cells, cells, capabilities, ScrollHint.none()));

        assertTrue(palette.acceptOsc("10;rgb:af/87/5f"));
        String changed = diff.diff(cells, cells, capabilities, ScrollHint.none());
        assertTrue(changed.startsWith("\u001B[H"));
        assertEquals("indexed:137", onlyPaint(changed).foreground());
        assertEquals("", diff.diff(cells, cells, capabilities, ScrollHint.none()));
    }

    @Test
    void oscRgbComponentsOfEverySupportedWidthAreScaledToEightBits() {
        String[][] samples = {
            {"a/b/c", "rgb:170:187:204"},
            {"12/ab/CD", "rgb:18:171:205"},
            {"123/abc/def", "rgb:18:171:222"},
            {"1234/abcd/5678", "rgb:18:171:86"},
            {"f/80/800", "rgb:255:128:128"}
        };
        for (var sample : samples) {
            var palette = new TerminalPalette();
            assertTrue(palette.acceptOsc("10;rgb:" + sample[0]));
            assertEquals(sample[1], onlyPaint(new AnsiDiff(palette).diff(null, oneCell(CellStyle.DEFAULT),
                capabilities(ColorDepth.TRUECOLOR), ScrollHint.none())).foreground(), sample[0]);
        }
    }

    @Test
    void malformedAndOversizedOscReportsLeaveEveryPaletteEntryUnchanged() {
        var palette = new TerminalPalette();
        assertTrue(palette.acceptOsc("10;rgb:12/34/56"));
        assertTrue(palette.acceptOsc("4;4;rgb:22/44/66"));
        long revision = palette.revision();
        assertFalse(palette.acceptOsc(null));
        for (String payload : List.of("", "10;?", "10;rgb:12/34", "10;rgb:/34/56", "10;rgb:12345/34/56",
            "10;rgb:gg/34/56", "10;rgb:１２/34/56", "11;rgb:12/34/56", "4;16;rgb:11/22/33",
            "4;-1;rgb:11/22/33", "4;4;rgb:aa/bb/cc;16;rgb:11/22/33",
            "4;4;rgb:aa/bb/cc;5;rgb:??", "4;4;rgb:aa/bb/cc;5", "4;4;?",
            "10;rgb:12/34/56\u001B]4;0;rgb:11/22/33", "4;4;rgb:aa/bb/cc;" + "0".repeat(1024))) {
            assertFalse(palette.acceptOsc(payload), payload);
            assertEquals(revision, palette.revision(), "An invalid batch must not partially commit its earlier entries");
        }
        var diff = new AnsiDiff(palette);
        assertEquals("rgb:18:52:86", onlyPaint(diff.diff(null, oneCell(CellStyle.DEFAULT),
            capabilities(ColorDepth.TRUECOLOR), ScrollHint.none())).foreground());
        assertEquals("rgb:34:68:102", onlyPaint(diff.diff(null,
            oneCell(new CellStyle(CellColor.BLUE, CellColor.DEFAULT, false, false)),
            capabilities(ColorDepth.TRUECOLOR), ScrollHint.none())).foreground());
    }

    @Test
    void unansweredQueriesKeepDefaultAndUnknownAnsiForegroundsPortable() {
        var palette = new TerminalPalette();
        for (var depth : List.of(ColorDepth.ANSI_256, ColorDepth.TRUECOLOR)) {
            for (boolean bold : new boolean[] {false, true}) {
                for (var color : List.of(CellColor.DEFAULT, CellColor.BLUE, CellColor.BRIGHT_RED)) {
                    var style = new CellStyle(color, CellColor.DEFAULT, bold, false);
                    String output = new AnsiDiff(palette).diff(null, oneCell(style), capabilities(depth), ScrollHint.none());
                    assertEquals(new AnsiDiff().diff(null, oneCell(style), capabilities(depth), ScrollHint.none()), output);
                    assertEquals(switch (color) {
                        case DEFAULT -> "default";
                        case BLUE -> "ansi:4";
                        case BRIGHT_RED -> "ansi:9";
                        default -> throw new AssertionError(color);
                    }, onlyPaint(output).foreground(), depth + "/" + color + "/" + bold);
                }
            }
        }
        assertEquals(0L, palette.revision());
    }

    @Test
    void knownForegroundsDoNotAlterAnsi16OrNoColorPresentation() {
        var palette = new TerminalPalette();
        assertTrue(palette.acceptOsc("10;rgb:12/34/56"));
        assertTrue(palette.acceptOsc("4;4;rgb:65/43/21"));
        for (var depth : List.of(ColorDepth.ANSI_16, ColorDepth.MONOCHROME)) {
            for (var color : List.of(CellColor.DEFAULT, CellColor.BLUE)) {
                var cells = oneCell(new CellStyle(color, CellColor.BLUE, true, true));
                String expected = new AnsiDiff().diff(null, cells, capabilities(depth), ScrollHint.none());
                String actual = new AnsiDiff(palette).diff(null, cells, capabilities(depth), ScrollHint.none());
                assertEquals(expected, actual, depth + "/" + color);
                assertTrue(onlyPaint(actual).bold());
                assertTrue(onlyPaint(actual).underline());
                assertFalse(actual.contains("38;2;") || actual.contains("38;5;"));
            }
        }
    }

    @Test
    void paletteQueryOnlyAsksForDefaultForegroundAndSixteenAnsiEntries() {
        String query = TerminalPalette.query();
        var matcher = Pattern.compile("\u001B\\]([^\u0007\u001B]*)(?:\u0007|\u001B\\\\)").matcher(query);
        Set<Integer> indices = new HashSet<>();
        int offset = 0;
        int defaultQueries = 0;
        while (matcher.find()) {
            assertEquals(offset, matcher.start(), "Queries must contain only complete readonly OSC commands");
            offset = matcher.end();
            var fields = matcher.group(1).split(";", -1);
            if (fields[0].equals("10")) {
                assertEquals(List.of("10", "?"), List.of(fields));
                defaultQueries++;
            } else {
                assertEquals("4", fields[0], "Palette writes, resets and unrelated OSC commands are forbidden");
                assertEquals(1, fields.length % 2);
                for (int field = 1; field < fields.length; field += 2) {
                    int index = Integer.parseInt(fields[field]);
                    assertTrue(index >= 0 && index < 16);
                    assertTrue(indices.add(index), "Each ANSI palette entry needs only one query");
                    assertEquals("?", fields[field + 1], "An OSC4 palette entry must never be assigned a color");
                }
            }
        }
        assertEquals(query.length(), offset);
        assertEquals(1, defaultQueries);
        assertEquals(16, indices.size());
    }

    private static TerminalCapabilities capabilities(ColorDepth depth) {
        return new TerminalCapabilities(depth, false, false, false);
    }

    private static CellBuffer oneCell(CellStyle style) {
        var cells = new CellBuffer(1, 1);
        cells.set(0, 0, "x", style);
        return cells;
    }

    private static Frame frame(CellBuffer cells) {
        return new Frame(cells, new ScreenCursor(new Point(0, 0), false), ScrollHint.none());
    }

    private static int cubeRgb(int index) {
        int cube = index - 16;
        int[] levels = {0, 95, 135, 175, 215, 255};
        return levels[cube / 36] << 16 | levels[cube / 6 % 6] << 8 | levels[cube % 6];
    }

    private static String rgbReply(int rgb) {
        return "rgb:%02x/%02x/%02x".formatted(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255);
    }

    private static String rgbColor(int rgb) {
        return "rgb:" + (rgb >> 16 & 255) + ':' + (rgb >> 8 & 255) + ':' + (rgb & 255);
    }

    private static List<String> foregrounds(String output) {
        return paints(output).stream().map(Paint::foreground).toList();
    }

    private static Paint onlyPaint(String output) {
        var paints = paints(output);
        assertEquals(1, paints.size(), output);
        return paints.getFirst();
    }

    /** Independent physical SGR oracle; deliberately never maps colors back to semantic CellColor values. */
    private static List<Paint> paints(String output) {
        var result = new ArrayList<Paint>();
        String foreground = "default", background = "default";
        boolean bold = false, underline = false;
        for (int offset = 0; offset < output.length();) {
            if (output.charAt(offset) != '\u001B') {
                char glyph = output.charAt(offset++);
                if (glyph == 'p' || glyph == 'b' || glyph == 'x')
                    result.add(new Paint(foreground, background, bold, underline));
                continue;
            }
            assertTrue(offset + 1 < output.length() && output.charAt(offset + 1) == '[', output);
            int end = offset + 2;
            while (end < output.length() && (output.charAt(end) < '@' || output.charAt(end) > '~')) end++;
            assertTrue(end < output.length(), output);
            if (output.charAt(end) == 'm') {
                String[] parameters = output.substring(offset + 2, end).split(";", -1);
                for (int index = 0; index < parameters.length; index++) {
                    int code = parameters[index].isEmpty() ? 0 : Integer.parseInt(parameters[index]);
                    switch (code) {
                        case 0 -> { foreground = background = "default"; bold = underline = false; }
                        case 1 -> bold = true;
                        case 22 -> bold = false;
                        case 4 -> underline = true;
                        case 24 -> underline = false;
                        case 39 -> foreground = "default";
                        case 49 -> background = "default";
                        case 38, 48 -> {
                            int mode = Integer.parseInt(parameters[++index]);
                            String color;
                            if (mode == 5) color = "indexed:" + Integer.parseInt(parameters[++index]);
                            else {
                                assertEquals(2, mode, output);
                                color = "rgb:" + Integer.parseInt(parameters[++index]) + ':'
                                    + Integer.parseInt(parameters[++index]) + ':' + Integer.parseInt(parameters[++index]);
                            }
                            if (code == 38) foreground = color;
                            else background = color;
                        }
                        case 7, 27 -> { /* Reverse video is already covered by the existing no-color tests. */ }
                        default -> {
                            if (code >= 30 && code <= 37) foreground = "ansi:" + (code - 30);
                            else if (code >= 90 && code <= 97) foreground = "ansi:" + (code - 90 + 8);
                            else if (code >= 40 && code <= 47) background = "ansi:" + (code - 40);
                            else if (code >= 100 && code <= 107) background = "ansi:" + (code - 100 + 8);
                            else throw new AssertionError("Unsupported SGR in test oracle: " + code);
                        }
                    }
                }
            }
            offset = end + 1;
        }
        return result;
    }

    private record Paint(String foreground, String background, boolean bold, boolean underline) {}
}
