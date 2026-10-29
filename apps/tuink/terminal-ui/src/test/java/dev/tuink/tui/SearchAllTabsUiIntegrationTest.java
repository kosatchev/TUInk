package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchAllTabsUiIntegrationTest {
    @TempDir Path directory;

    @Test
    void anExplicitSearchResultCarriesIndependentControlsWhileManualSwitchingKeepsTheirEdits() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("plain"))) {
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("needle second"));
            var second = window.active();
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("second"));
            var oldSecondQuery = window.query;
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("needle"));
            window.focusSearch(SearchFocus.REPLACE);
            window.event(new TextInput("replacement"));
            var firstQuery = window.query;
            var firstReplacement = window.replacement;
            enableAllTabsAndFindNext(window);

            assertSame(second, window.active());
            assertEquals("needle", window.query.text());
            assertEquals("replacement", window.replacement.text());
            assertNotSame(firstQuery, window.query);
            assertNotSame(oldSecondQuery, window.query);
            assertNotSame(firstReplacement, window.replacement);
            assertEquals(first.uiState().searchUi, second.uiState().searchUi);
            assertTrue(second.uiState().searchMode);
            assertEquals(SearchFocus.NEXT_BUTTON, window.host.searchFocusManager().focused().orElseThrow());

            window.focusSearch(SearchFocus.FIND);
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput("second"));
            window.command(TuinkCommand.TAB_PREVIOUS);

            assertSame(first, window.active());
            assertSame(firstQuery, window.query);
            assertSame(firstReplacement, window.replacement);
            assertEquals("needle", window.query.text());
            assertEquals("replacement", window.replacement.text());
            window.command(TuinkCommand.TAB_NEXT);
            assertSame(second, window.active());
            assertEquals("second", window.query.text());
            assertEquals(List.of("needle second"), second.tabSession().model().snapshotLines());
            assertEquals(List.of("plain"), first.tabSession().model().snapshotLines());
        }
    }

    @Test
    void navigatingToAMatchKeepsTheDestinationSaveAsDialogAndFilename() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("plain"))) {
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("needle draft"));
            var second = window.active();
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            Path target = directory.resolve("second.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            var dialog = second.uiState().modalState;
            var filename = second.uiState().saveAsInput;
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("needle"));
            enableAllTabsAndFindNext(window);

            assertSame(second, window.active());
            assertTrue(second.uiState().modalState.isSaveAs());
            assertSame(dialog, second.uiState().modalState);
            assertSame(filename, window.filename);
            assertEquals(target.toString(), window.filename.text());
            assertEquals("needle", second.uiState().searchQueryInput.text());
            assertEquals("needle", first.uiState().searchQueryInput.text());
            assertNotSame(first.uiState().searchQueryInput, second.uiState().searchQueryInput);
            assertTrue(second.uiState().searchMode);
            assertTrue(second.uiState().awaitingAnswer());
            assertFalse(Files.exists(target));
            assertEquals(List.of("needle draft"), second.tabSession().model().snapshotLines());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(second.uiState().modalState.isNone());
            assertTrue(window.screenRow(window.height - 2).startsWith("Replace:"));
            assertEquals(SearchFocus.NEXT_BUTTON, window.host.searchFocusManager().focused().orElseThrow());
        }
    }

    private static void enableAllTabsAndFindNext(EditorWindowFixture window) {
        window.focusSearch(SearchFocus.ALL);
        window.event(KeyStroke.of(KeyStroke.Key.SPACE));
        assertTrue(window.active().uiState().searchUi.allTabs());
        window.focusSearch(SearchFocus.NEXT_BUTTON);
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }
}
