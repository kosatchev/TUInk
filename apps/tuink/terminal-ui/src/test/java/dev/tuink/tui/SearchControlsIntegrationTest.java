package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchControlsIntegrationTest {
    @TempDir Path directory;
    private void open(EditorWindowFixture window) {
        window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
        window.focusSearch(SearchFocus.MODE); window.event(KeyStroke.of(KeyStroke.Key.SPACE));
        assertTrue(window.selected().uiState().searchModes.popupOpen());
    }
    private int rowContaining(EditorWindowFixture window, String label) {
        return java.util.stream.IntStream.range(0, window.height).filter(row -> window.screenRow(row).contains(label)).findFirst().orElseThrow();
    }
    @Test void documentPopupPaintsAboveTheBarAndPointerChoiceNeverTouchesTheDocument() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("a.b axb"))) {
            open(window);
            int row = rowContaining(window, "expressions");
            assertTrue(row < window.rowStartingWith("Find: "));
            int column = window.screenRow(row).indexOf("Regex");
            assertNotEquals(window.host.styles.windows().renderBody(), window.screenStyle(row, column));
            window.pointer(PointerEvent.Kind.PRESS, row, column); window.pointer(PointerEvent.Kind.RELEASE, row, column);
            assertEquals(SearchOptions.MatchMode.REGEX, window.selected().uiState().searchUi.matchMode());
            assertFalse(window.selected().uiState().searchModes.popupOpen());
            assertEquals(List.of("a.b axb"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
            window.focusSearch(SearchFocus.FIND); window.event(new TextInput("a.b"));
            assertTrue(window.screenRow(window.height - 1).contains("Find 2"));
        }
    }
    @Test void cancelAndOutsidePressPreserveModeAndFirstEscapeKeepsTheSearchBar() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            open(window); window.event(KeyStroke.of(KeyStroke.Key.END)); window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.selected().uiState().searchMode);
            assertEquals(SearchOptions.MatchMode.SUBSTRING, window.selected().uiState().searchUi.matchMode());
            open(window); window.pointer(PointerEvent.Kind.PRESS, 4, 5); window.pointer(PointerEvent.Kind.RELEASE, 4, 5);
            assertFalse(window.selected().uiState().searchModes.popupOpen());
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().hasSelection());
            open(window); window.resize(20, 45); assertFalse(window.selected().uiState().searchModes.popupOpen());
        }
    }
    @Test void previewUsesTheSamePopupAndOptionsWhileKeepingSourceReadOnly() throws Exception {
        try (var window = new EditorWindowFixture(directory, "note.md", false, List.of("# id=42"))) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.controller.previewFrame() != null);
            open(window); window.event(KeyStroke.of(KeyStroke.Key.END)); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(SearchOptions.MatchMode.REGEX, window.selected().uiState().searchUi.matchMode());
            window.focusSearch(SearchFocus.FIND); window.event(new TextInput("id=\\d+"));
            assertEquals(1, window.selected().uiState().preview.searchMatches.size());
            assertEquals(List.of("# id=42"), window.active().tabSession().model().snapshotLines());
        }
    }
    @Test void searchSettingsEncodingUsesTheSharedPickerAndEscapeCancelsOnlyThePicker() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.command(TuinkCommand.PROJECT_SEARCH_SETTINGS);
            var draft = window.ui.searchSettingsDialog;
            for (int step = 0; step < 4; step++) window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.SPACE)); assertTrue(draft.encodings.popupOpen());
            window.event(KeyStroke.of(KeyStroke.Key.END)); window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertSame(draft, window.ui.searchSettingsDialog); assertEquals(DocumentOpenMode.DETECT, draft.encoding);
            window.event(KeyStroke.of(KeyStroke.Key.SPACE));
            window.event(KeyStroke.of(KeyStroke.Key.END)); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(DocumentOpenMode.KOI8_R, draft.encoding);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE)); assertNull(window.ui.searchSettingsDialog);
        }
    }
}
