package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyboardCompactSearchTest {
    @TempDir Path directory;

    @Test void everyOptionAndReplaceAllAreReachableByKeyboardAtThirtyColumns() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("alpha alpha"))) {
            window.resize(24, 30);
            window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
            window.event(new TextInput("alpha"));
            tab(window); space(window);
            assertTrue(window.active().uiState().searchUi.caseSensitive());
            tab(window); space(window);
            assertEquals(SearchOptions.MatchMode.WHOLE_WORD, window.active().uiState().searchUi.matchMode());
            tab(window); space(window);
            assertEquals(SearchOptions.MatchMode.WILDCARD, window.active().uiState().searchUi.matchMode());
            tab(window); space(window);
            assertTrue(window.active().uiState().searchUi.allTabs());
            tab(window);
            assertEquals(SearchFocus.REPLACE, window.host.searchFocusManager().focused().orElseThrow());
            window.event(new TextInput("X"));
            for (int index = 0; index < 4; index++) tab(window);
            assertEquals(SearchFocus.ALL_BUTTON, window.host.searchFocusManager().focused().orElseThrow());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals("X X", window.active().tabSession().model().getLine(0));
            assertEquals("alpha", window.query.text());
            assertEquals("X", window.replacement.text());
        }
    }

    @Test void reflowKeepsTheFocusedSharedTextFieldAndItsCaret() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
            window.event(new TextInput("界😀query"));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_LEFT));
            int caret = window.query.cursor();
            window.resize(24, 24);
            assertEquals(SearchFocus.FIND, window.host.searchFocusManager().focused().orElseThrow());
            assertEquals(caret, window.query.cursor());
            window.event(new TextInput("X"));
            assertEquals("界😀querXy", window.query.text());
            assertEquals("original", window.active().tabSession().model().getLine(0));
        }
    }

    private static void tab(EditorWindowFixture window) { window.event(KeyStroke.of(KeyStroke.Key.TAB)); }
    private static void space(EditorWindowFixture window) { window.event(new TextInput(" ")); }
}
