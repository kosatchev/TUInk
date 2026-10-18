package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.style.CellColor;
import dev.tuink.core.Position;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmptyLineSelectionIntegrationTest {
    @TempDir Path directory;

    @Test
    void keyboardSelectionShowsTheTwoLineBreaksThatDeleteWillRemove() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("abc", "", "xyz"))) {
            window.event(KeyStroke.of(KeyStroke.Key.END));
            window.event(shift(KeyStroke.Key.ARROW_RIGHT));
            window.event(shift(KeyStroke.Key.ARROW_RIGHT));

            assertEquals(new Position(0, 3), window.active().state().getSelectionAnchor());
            assertEquals(new Position(2, 0), window.active().state().getCursor());
            assertOnlyTwoLineBreakCellsAreSelected(window);

            window.ui.visibleWhitespaceSetting = false;
            window.frame();
            assertOnlyTwoLineBreakCellsAreSelected(window);

            window.event(KeyStroke.of(KeyStroke.Key.DELETE));

            assertEquals(List.of("abcxyz"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().hasSelection());
            assertEquals(0, selectedCells(window, 1, 3));

            window.event(ctrl(KeyStroke.Key.Z));

            assertEquals(List.of("abc", "", "xyz"), window.active().tabSession().model().snapshotLines());
            assertOnlyTwoLineBreakCellsAreSelected(window);
        }
    }

    @Test
    void reversedKeyboardSelectionHasTheSameVisibleExtentAndClearsOnNavigation() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("abc", "", "xyz"))) {
            window.event(ctrl(KeyStroke.Key.END));
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.event(shift(KeyStroke.Key.ARROW_LEFT));
            window.event(shift(KeyStroke.Key.ARROW_LEFT));

            assertEquals(new Position(2, 0), window.active().state().getSelectionAnchor());
            assertEquals(new Position(0, 3), window.active().state().getCursor());
            assertOnlyTwoLineBreakCellsAreSelected(window);

            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));

            assertFalse(window.active().state().hasSelection());
            assertEquals(0, selectedCells(window, 1, 3), "Clearing a selection must repaint cached blank cells");
        }
    }

    @Test
    void selectAllShowsEmptyLinesButDoesNotInventALineBreakAfterEof() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("", "", ""))) {
            window.event(ctrl(KeyStroke.Key.A));

            int firstRow = window.rowStartingWith(" 1 ");
            int bodyColumn = " 1 ".length();
            assertEquals(new Position(2, 0), window.active().state().getCursor());
            assertSelectedBlank(window, firstRow, bodyColumn);
            assertSelectedBlank(window, firstRow + 1, bodyColumn);
            assertEquals(2, selectedCells(window, firstRow, firstRow + 2),
                "The final empty logical line contains no selected character or newline");

            window.event(KeyStroke.of(KeyStroke.Key.DELETE));

            assertEquals(List.of(""), window.active().tabSession().model().snapshotLines());
            assertEquals(0, selectedCells(window, firstRow, firstRow + 2));
        }
    }

    @Test
    void lineBreakMarkerFollowsTheTerminalWidthOfEmojiAndTabs() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("😀\ta", "tail"))) {
            window.event(KeyStroke.of(KeyStroke.Key.END));
            window.event(shift(KeyStroke.Key.ARROW_RIGHT));

            int firstRow = window.rowStartingWith(" 1 ");
            int bodyColumn = " 1 ".length();
            // Emoji occupies two cells; the tab advances to column four; 'a' occupies one.
            assertSelectedBlank(window, firstRow, bodyColumn + 5);
            assertEquals(1, selectedCells(window, firstRow, firstRow + 1));
            assertEquals(List.of("😀\ta", "tail"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void aWrappedLineHasOneLineBreakMarkerAfterItsLastVisualSegment() throws Exception {
        String line = "0123456789ABCDEFGHIJtail";
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(line, "next"))) {
            window.ui.wrapLinesSetting = true;
            window.ui.wrapWordsSetting = false;
            window.resize(12, 18);
            window.active().tabSession().engine().restoreSelection(new Position(1, 0), new Position(0, line.length()));
            window.frame();

            int lastSegmentRow = -1;
            int markerColumn = -1;
            for (int row = 1; row < window.height - 1; row++) {
                int tailColumn = window.screenRow(row).indexOf("tail");
                if (tailColumn >= 0) {
                    lastSegmentRow = row;
                    markerColumn = tailColumn + "tail".length();
                    break;
                }
            }
            assertFalse(lastSegmentRow < 0, "The final wrapped segment must be visible");
            assertSelectedBlank(window, lastSegmentRow, markerColumn);
            assertEquals(1, selectedCells(window, 1, window.height - 2),
                "Soft wrapping does not create selected line breaks");
        }
    }

    private static void assertOnlyTwoLineBreakCellsAreSelected(EditorWindowFixture window) {
        // Undo may leave a historical diff marker in the gutter's first cell.
        int firstRow = 1;
        assertEquals("1 abc", window.screenRow(firstRow).substring(1, 6));
        int textColumn = window.screenRow(firstRow).indexOf("abc");
        assertSelectedBlank(window, firstRow, textColumn + "abc".length());
        assertSelectedBlank(window, firstRow + 1, textColumn);
        assertEquals(2, selectedCells(window, firstRow, firstRow + 2),
            "The ending position at column zero must leave the next line unselected");
    }

    private static void assertSelectedBlank(EditorWindowFixture window, int row, int column) {
        assertEquals(CellColor.BRIGHT_CYAN, window.screenStyle(row, column).bg(),
            "The selected line break must occupy one highlighted terminal cell");
    }

    private static int selectedCells(EditorWindowFixture window, int firstRow, int lastRow) {
        int selected = 0;
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = 0; column < window.width; column++) {
                if (window.screenStyle(row, column).bg() == CellColor.BRIGHT_CYAN) {
                    selected++;
                }
            }
        }
        return selected;
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }

    private static KeyStroke shift(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.SHIFT);
    }
}
