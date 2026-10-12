package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuink.tui.ui.HelpPage;
import dev.tuink.tui.style.UiStyles;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HelpOverlayScrollingTest {
    private static final HelpPage LONG_PAGE = new HelpPage(
        "Keyboard hints",
        IntStream.rangeClosed(1, 80).mapToObj(row -> "Help row " + row).toList()
    );

    @Test
    void fullHelpCanBeReadInAStandardTerminalAndNavigationDoesNotCloseTheOverlay() {
        TerminalEditorApp app = new TerminalEditorApp();
        String first = paint(app, 24, 80, LONG_PAGE);
        assertTrue(first.contains("Help row 1 "));
        assertFalse(first.contains("Help row 80"));
        assertTrue(first.contains("1-17/80"));
        assertTrue(first.contains("PgUp/PgDn Home/End"));

        HelpOverlayInputRouter.Result end = app.routeHelpOverlayInput(
            24, 80, LONG_PAGE, KeyStroke.of(KeyStroke.Key.END)
        );
        assertTrue(end.consumed());
        assertFalse(end.closeRequested());
        String last = paint(app, 24, 80, LONG_PAGE);
        assertTrue(last.contains("Help row 80"));
        assertFalse(last.contains("Help row 1 "));
        assertTrue(last.contains("64-80/80"));

        app.routeHelpOverlayInput(24, 80, LONG_PAGE, KeyStroke.of(KeyStroke.Key.HOME));
        assertTrue(paint(app, 24, 80, LONG_PAGE).contains("Help row 1 "));
    }

    @Test
    void growingTheTerminalClampsThePageAndClosingOrChangingHelpResetsScrolling() {
        TerminalEditorApp app = new TerminalEditorApp();
        app.routeHelpOverlayInput(24, 80, LONG_PAGE, KeyStroke.of(KeyStroke.Key.END));
        String enlarged = paint(app, 100, 80, LONG_PAGE);
        assertTrue(enlarged.contains("Help row 1 "));
        assertTrue(enlarged.contains("Help row 80"));

        app.routeHelpOverlayInput(24, 80, LONG_PAGE, KeyStroke.of(KeyStroke.Key.END));
        assertTrue(app.routeHelpOverlayInput(24, 80, LONG_PAGE, KeyStroke.of(KeyStroke.Key.ENTER)).closeRequested());
        assertTrue(paint(app, 24, 80, LONG_PAGE).contains("Help row 1 "));

        app.routeHelpOverlayInput(24, 80, LONG_PAGE, KeyStroke.of(KeyStroke.Key.END));
        paint(app, 24, 80, new HelpPage("About", List.of("A short page")));
        assertTrue(paint(app, 24, 80, LONG_PAGE).contains("Help row 1 "));
        app.routeHelpOverlayInput(24, 80, LONG_PAGE, KeyStroke.of(KeyStroke.Key.END));
        paint(app, 24, 80, null);
        assertTrue(paint(app, 24, 80, LONG_PAGE).contains("Help row 1 "));
    }

    @Test
    void mouseWheelScrollsOnlyInsideHelpAndSmallTerminalsStayClipped() {
        TerminalEditorApp app = new TerminalEditorApp();
        app.routeHelpOverlayInput(24, 80, LONG_PAGE, wheel(10, 10));
        assertTrue(paint(app, 24, 80, LONG_PAGE).contains("4-20/80"));
        app.routeHelpOverlayInput(24, 80, LONG_PAGE, wheel(0, 0));
        assertTrue(paint(app, 24, 80, LONG_PAGE).contains("4-20/80"));
        assertTrue(paint(app, 8, 24, LONG_PAGE).contains("PgUp/PgDn"));
        assertFalse(paint(app, 7, 23, LONG_PAGE).contains("Help row"));
    }

    @Test
    void sharedScrollbarKeepsItsGrabPointAndCapturesUntilReleaseOutsideTheDialog() {
        TerminalEditorApp app = new TerminalEditorApp();
        assertTrue(app.routeHelpOverlayInput(24, 80, LONG_PAGE, pointer(5, 71, PointerEvent.Kind.PRESS)).consumed());
        assertTrue(paint(app, 24, 80, LONG_PAGE).contains("1-17/80"));
        app.routeHelpOverlayInput(24, 80, LONG_PAGE, pointer(5, 71, PointerEvent.Kind.DRAG));
        assertTrue(paint(app, 24, 80, LONG_PAGE).contains("1-17/80"));
        app.routeHelpOverlayInput(24, 80, LONG_PAGE, pointer(23, 0, PointerEvent.Kind.DRAG));
        assertTrue(paint(app, 24, 80, LONG_PAGE).contains("64-80/80"));
        app.routeHelpOverlayInput(24, 80, LONG_PAGE, pointer(23, 0, PointerEvent.Kind.RELEASE));
        app.routeHelpOverlayInput(24, 80, LONG_PAGE, pointer(0, 0, PointerEvent.Kind.DRAG));
        assertTrue(paint(app, 24, 80, LONG_PAGE).contains("64-80/80"));
        assertTrue(app.routeHelpOverlayInput(24, 80, LONG_PAGE, KeyStroke.of(KeyStroke.Key.ENTER)).closeRequested());
    }

    @Test
    void resizingCancelsCaptureAndScrollbarInheritsDialogSurfaceInColorAndMono() throws Exception {
        var applyTheme = TerminalEditorApp.class.getDeclaredMethod("applyTheme", UiStyles.Theme.class);
        applyTheme.setAccessible(true);
        for (UiStyles.Theme theme : UiStyles.Theme.values()) {
            for (boolean colorEnabled : List.of(true, false)) {
                TerminalEditorApp app = new TerminalEditorApp();
                applyTheme.invoke(app, theme);
                CellBuffer cells = new CellBuffer(24, 80);
                app.paintHelpOverlay(new Canvas(cells), 24, 80, LONG_PAGE, colorEnabled);
                var body = cells.get(20, 7).style();
                var border = cells.get(2, 6).style();
                for (int row : List.of(3, 10)) {
                    var scrollbar = cells.get(row, 71).style();
                    assertEquals(body.bg(), scrollbar.bg());
                    assertEquals(border.fg(), scrollbar.fg());
                    assertEquals(border.bold(), scrollbar.bold());
                }
                app.routeHelpOverlayInput(24, 80, LONG_PAGE, pointer(5, 71, PointerEvent.Kind.PRESS));
                paint(app, 30, 100, LONG_PAGE);
                app.routeHelpOverlayInput(30, 100, LONG_PAGE, pointer(29, 0, PointerEvent.Kind.DRAG));
                assertTrue(paint(app, 30, 100, LONG_PAGE).contains("1-23/80"));
            }
        }
    }

    @Test
    void scrollbarAlsoInheritsTheTemporarilyHighlightedParentBorder() {
        for (boolean colorEnabled : List.of(true, false)) {
            TerminalEditorApp app = new TerminalEditorApp();
            CellBuffer cells = new CellBuffer(24, 80);
            app.paintHelpOverlay(new Canvas(cells), 24, 80, LONG_PAGE, colorEnabled, true);
            assertEquals(cells.get(2, 6).style().fg(), cells.get(10, 71).style().fg());
            assertEquals(cells.get(2, 6).style().bold(), cells.get(10, 71).style().bold());
            assertEquals(cells.get(20, 7).style().bg(), cells.get(10, 71).style().bg());
        }
    }

    @Test
    void everyWordOfALongHelpLineCanBeReadAtStandardAndNarrowWidths() {
        String longLine = IntStream.range(0, 60)
            .mapToObj(i -> "word" + String.format(java.util.Locale.ROOT, "%03d", i))
            .collect(Collectors.joining(" ")) + " конец😀 TAIL_REACHED";
        HelpPage page = new HelpPage("Wrapped", List.of(longLine));
        for (int[] size : List.of(new int[] {24, 80}, new int[] {12, 40}, new int[] {8, 24})) {
            TerminalEditorApp app = new TerminalEditorApp();
            StringBuilder read = new StringBuilder();
            for (int step = 0; step < 60; step++) {
                read.append(paint(app, size[0], size[1], page)).append('\n');
                app.routeHelpOverlayInput(size[0], size[1], page, KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            }
            for (int word = 0; word < 60; word++) {
                String expected = "word" + String.format(java.util.Locale.ROOT, "%03d", word);
                assertTrue(read.toString().contains(expected), expected + " at " + size[1] + " columns");
            }
            assertTrue(read.toString().contains("TAIL_REACHED"), size[1] + " columns");
            assertTrue(read.toString().contains("конец😀"));
        }
    }

    private static PointerEvent pointer(int row, int col, PointerEvent.Kind kind) {
        return new PointerEvent(row, col, kind, PointerEvent.Button.LEFT, Set.of());
    }

    private static PointerEvent wheel(int row, int col) {
        return new PointerEvent(row, col, PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE, Set.of());
    }

    private static String paint(TerminalEditorApp app, int rows, int cols, HelpPage page) {
        CellBuffer cells = new CellBuffer(rows, cols);
        app.paintHelpOverlay(new Canvas(cells), rows, cols, page, true);
        return IntStream.range(0, rows).mapToObj(row -> {
            StringBuilder text = new StringBuilder();
            for (int col = 0; col < cols; col++) {
                text.append(cells.get(row, col).grapheme());
            }
            return text.toString();
        }).collect(Collectors.joining("\n"));
    }
}
