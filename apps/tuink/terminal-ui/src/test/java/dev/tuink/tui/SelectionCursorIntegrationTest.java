package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.style.CellColor;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.render.EditorStyleAdapter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectionCursorIntegrationTest {
    @TempDir Path root;

    @Test void bothSelectionDirectionsHaveALightCaretAndWideGraphemesStayIntactInEveryTheme() throws Exception {
        var original = List.of("中ABC", "middle", "last");
        for (var theme : UiStyles.Theme.values()) for (boolean backwards : new boolean[] {false, true}) {
            try (var window = new EditorWindowFixture(root, false, original)) {
                window.host.applyTheme(theme);
                var first = new Position(0, 0); var last = new Position(2, 0);
                window.active().tabSession().engine().restoreSelection(backwards ? first : last, backwards ? last : first);
                window.frame();
                int row = window.rowStartingWith(backwards ? " 1 " : " 3 ");
                assertEquals(CellColor.BRIGHT_WHITE, window.screenStyle(row, 3).fg());
                assertFalse(window.screenStyle(row, 3).inverse());
                int firstRow = window.rowStartingWith(" 1 ");
                var selected = EditorStyleAdapter.toCell(window.host.styles.windows().renderSelection());
                assertTrue(window.screenRow(firstRow).contains("中 ABC"), window.screenRow(firstRow));
                assertEquals(selected.fg(), window.screenStyle(firstRow, 5).fg(), "Other selected glyphs retain their selection colors");
                if (backwards) assertEquals(CellColor.BRIGHT_WHITE, window.screenStyle(firstRow, 4).fg(), "The wide-cell continuation shares the caret style");
                window.command(TuinkCommand.DOCUMENT_SAVE_AS);
                assertTrue(window.active().state().hasSelection());
                assertEquals(selected.fg(), window.screenStyle(firstRow, 3).fg(), "A field owns the cursor while Save As is open");
                window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
                assertEquals(CellColor.BRIGHT_WHITE, window.screenStyle(row, 3).fg());
                assertEquals(original, window.active().tabSession().model().snapshotLines());
                assertFalse(window.active().state().isDirty());
            }
        }
    }

    @Test void mouseSelectionIsImmediateAndItsCaretIsLightInEitherDirection() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("first", "middle", "last"))) {
            for (boolean backwards : new boolean[] {false, true}) {
                int first = window.rowStartingWith(" 1 "), last = window.rowStartingWith(" 3 ");
                int from = backwards ? last : first, to = backwards ? first : last;
                window.pointer(PointerEvent.Kind.PRESS, from, 3);
                window.pointer(PointerEvent.Kind.DRAG, to, 3);
                assertTrue(window.active().state().hasSelection());
                assertEquals(backwards ? 0 : 2, window.active().state().getCursor().line());
                assertEquals(CellColor.BRIGHT_WHITE, window.screenStyle(to, 3).fg());
                window.pointer(PointerEvent.Kind.RELEASE, to, 3);
                assertEquals(CellColor.BRIGHT_WHITE, window.screenStyle(to, 3).fg());
            }
        }
    }
}
