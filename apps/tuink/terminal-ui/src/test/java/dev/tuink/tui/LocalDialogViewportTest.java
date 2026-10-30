package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.style.CellStyle;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.HelpPage;
import dev.tuink.tui.ui.OpenFileDialogController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDialogViewportTest {
    @TempDir
    Path directory;

    @Test
    void helpPaintingPreservesChromeEvenWhenNoContentRowRemains() {
        HelpPage page = new HelpPage("Help", List.of("A visible help row"));
        TerminalEditorApp host = new TerminalEditorApp();
        for (int[] size : List.of(new int[] {24, 80}, new int[] {10, 24},
            new int[] {7, 80}, new int[] {24, 23}, new int[] {3, 1}, new int[] {2, 80})) {
            int rows = size[0];
            int columns = size[1];
            CellBuffer cells = new CellBuffer(rows, columns);
            Canvas canvas = new Canvas(cells);
            for (int row = 0; row < 2; row++) {
                for (int column = 0; column < columns; column++) {
                    canvas.put(column, row, "H", CellStyle.DEFAULT);
                }
            }
            var overlay = host.helpOverlayPlacement(rows, columns, page, 2);

            host.paintHelpOverlay(canvas, overlay, true);

            assertEquals(new Rect(0, 2, columns, rows - 2), overlay.viewport());
            assertTrue(overlay.viewport().contains(overlay.frame().bounds()));
            for (int row = 0; row < 2; row++) {
                for (int column = 0; column < columns; column++) {
                    assertEquals("H", cells.get(row, column).grapheme(),
                        "help overwrote chrome at " + columns + "x" + rows);
                }
            }
        }
    }

    @Test
    void filePickerFitsTheRemainingViewportAndLegacyGeometryIsPreserved() {
        assertEquals(OpenFileDialogController.layoutForTerminal(24, 80),
            OpenFileDialogController.layoutForTerminal(24, 80, 0));
        for (int rows : List.of(0, 1, 2, 3, 7, 10, 24)) {
            for (int columns : List.of(0, 1, 23, 24, 80)) {
                var layout = OpenFileDialogController.layoutForTerminal(rows, columns, 2);
                int inset = Math.min(2, rows);
                Rect viewport = new Rect(0, inset, columns, rows - inset);
                Rect bounds = new Rect(layout.left(), layout.top(), layout.width(), layout.height());
                assertTrue(viewport.contains(bounds), "file picker escaped " + viewport);
                assertTrue(layout.listStartRow() >= bounds.y());
                assertTrue(layout.listStartRow() + layout.listRows() <= bounds.bottomExclusive());
            }
        }
    }

    @Test
    void compactLocalDialogsKeepBothTabHeadersClickableAndEscapeWorksWithoutContentRows() throws Exception {
        for (TuinkCommand owner : List.of(TuinkCommand.DOCUMENT_OPEN, TuinkCommand.HELP_KEY_HINTS)) {
            for (int[] size : List.of(new int[] {7, 80}, new int[] {24, 23}, new int[] {2, 80})) {
                Path root = directory.resolve(owner + "-" + size[0] + "-" + size[1]);
                try (EditorWindowFixture window = new EditorWindowFixture(root, false, List.of("alpha"))) {
                    var first = window.active();
                    first.tabSession().engine().open(JvmDocumentStorage.resource(
                        Files.writeString(root.resolve("a.txt"), "alpha")));
                    window.command(TuinkCommand.TAB_NEW);
                    var second = window.active();
                    second.tabSession().engine().open(JvmDocumentStorage.resource(
                        Files.writeString(root.resolve("b.txt"), "beta")));
                    window.command(TuinkCommand.TAB_PREVIOUS);
                    window.resize(size[0], size[1]);

                    window.command(owner);

                    assertTrue(first.uiState().awaitingAnswer());
                    assertTrue(window.screenRow(1).contains("?a.txt"), window.screenRow(1));
                    assertTrue(window.screenRow(1).contains("b.txt"), window.screenRow(1));
                    clickTab(window, "b.txt");
                    assertSame(second.uiState(), window.active().uiState());
                    assertTrue(window.screenRow(1).contains("?a.txt"), window.screenRow(1));
                    window.event(new TextInput("X"));
                    assertEquals("Xbeta", second.tabSession().model().getLine(0));
                    assertEquals("alpha", first.tabSession().model().getLine(0));

                    clickTab(window, "a.txt");
                    assertSame(first.uiState(), window.active().uiState());
                    assertTrue(first.uiState().awaitingAnswer());
                    window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
                    assertFalse(first.uiState().awaitingAnswer());
                    window.event(new TextInput("X"));
                    assertEquals("Xalpha", first.tabSession().model().getLine(0));
                }
            }
        }
    }

    private static void clickTab(EditorWindowFixture window, String filename) {
        int column = window.screenRow(1).indexOf(filename);
        assertTrue(column >= 0, window.screenRow(1));
        window.pointer(PointerEvent.Kind.PRESS, 1, column + 1);
        window.pointer(PointerEvent.Kind.RELEASE, 1, column + 1);
    }
}
