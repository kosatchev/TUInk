package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises shared preparation through real Source/Preview input and frame ownership. */
class WindowChromeIntegrationTest {
    @TempDir Path directory;

    @Test void globalConfirmationRetainsTheSourcesSearchFieldFocusAndUsesItsOwnSafeFocus() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            window.event(ctrl(KeyStroke.Key.F));
            window.focusSearch(SearchFocus.REPLACE);
            window.replacement.set("retained replacement");
            window.frame();
            var sourceUi = window.active().uiState();
            assertEquals(SearchFocus.REPLACE, sourceUi.searchFocusManager.focused().orElseThrow());

            window.command(TuinkCommand.APP_QUIT);
            assertEquals(ConfirmChoice.CANCEL, window.host.confirmFocusManager().focused().orElseThrow());
            assertTrue(sourceUi.confirmFocusManager.focused().isEmpty(), "a window question does not acquire the source's focus manager");
            assertEquals(SearchFocus.REPLACE, sourceUi.searchFocusManager.focused().orElseThrow());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.controller.running());
            assertEquals(SearchFocus.REPLACE, window.host.searchFocusManager().focused().orElseThrow());
            assertEquals("retained replacement", window.replacement.text());
        }
    }


    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
}
