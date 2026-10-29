package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchTabMarkerCacheIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void aNewUnmatchedTabDoesNotInheritTheClosedTabsSearchMarker() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("plain"))) {
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("needle"));
            long revision = window.active().tabSession().model().revision();
            window.event(ctrl(KeyStroke.Key.F));
            window.event(new TextInput("needle"));
            assertTrue(window.screenRow(1).contains("#--no name--"));
            assertTrue(window.screenRow(window.height - 1).contains("Find 1"));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.TAB_CLOSE);
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(1, window.tabs.size());
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("other"));
            assertEquals(revision, window.active().tabSession().model().revision());

            window.event(ctrl(KeyStroke.Key.F));

            assertEquals("", window.query.text());
            assertTrue(window.active().uiState().searchMode);
            assertTrue(window.screenRow(1).contains("/--no name--"), window.screenRow(1));
            assertFalse(window.screenRow(window.height - 1).contains("Find "),
                window.screenRow(window.height - 1));
            assertFalse(window.screenRow(1).contains("#--no name--"), window.screenRow(1));
            assertEquals(List.of("other"), window.active().tabSession().model().snapshotLines());
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}
