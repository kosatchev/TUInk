package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ExplorerFieldFocusTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(value = SearchFocus.class, names = {"FIND", "REPLACE"})
    void clickingVisibleSearchFieldTransfersTypingAndPasteOutOfExplorer(SearchFocus target) throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("needle original"))) {
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("needle"));
            window.controller.openFolder(directory);
            window.await(() -> !window.ui.sidebar.tree.loading());
            assertTrue(window.ui.sidebar.focused);

            String label = target == SearchFocus.FIND ? "Find: " : "Replace: ";
            int row = -1;
            int column = -1;
            int documentLeft = window.ui.sidebar.placement.documentBounds().x();
            for (int candidate = 0; candidate < window.height; candidate++) {
                int found = window.screenRow(candidate).indexOf(label, documentLeft);
                if (found >= 0) { row = candidate; column = found + label.length(); break; }
            }
            assertTrue(row >= 0, "The search field must be visible inside the document container");
            assertTrue(column >= documentLeft);
            window.pointer(PointerEvent.Kind.PRESS, row, column);
            window.pointer(PointerEvent.Kind.RELEASE, row, column);
            window.event(new TextInput("X"));

            assertEquals(target == SearchFocus.FIND ? "Xneedle" : "X",
                target == SearchFocus.FIND ? window.query.text() : window.replacement.text());
            assertFalse(window.ui.sidebar.focused,
                "A clicked search field must replace the explorer as keyboard owner");
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());

            window.event(KeyStroke.of(KeyStroke.Key.END));
            window.event(new PasteEvent("Y"));

            assertEquals(target == SearchFocus.FIND ? "XneedleY" : "XY",
                target == SearchFocus.FIND ? window.query.text() : window.replacement.text(),
                "Cursor movement and pasted text must continue to reach the clicked field");
            assertEquals(List.of("needle original"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.ui.sidebar.visible);
            assertFalse(window.ui.sidebar.focused);
        }
    }
}
