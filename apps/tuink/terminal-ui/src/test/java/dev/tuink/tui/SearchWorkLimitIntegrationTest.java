package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchWorkLimitIntegrationTest {
    @Test
    void costlyWildcardShowsAnErrorKeepsTheQueryAndCanBeChanged(@TempDir Path directory) throws Exception {
        String original = "a".repeat(262_144);
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(original))) {
            window.ui.wrapLinesSetting = false;
            window.command(TuinkCommand.SEARCH_OPEN);
            window.focusSearch(SearchFocus.WILDCARD);
            window.event(new TextInput(" "));
            window.focusSearch(SearchFocus.FIND);
            String costly = "a".repeat(4096) + "?a";
            window.event(new TextInput(costly));

            assertEquals(costly, window.query.text());
            assertTrue(window.notification.message.startsWith("Search is too complex"));
            assertEquals(window.host.styles.notifyError(), window.notification.style());
            assertEquals(-1L, window.notification.nanosUntilExpire(System.nanoTime()));
            assertTrue(window.screenRow(window.height - 1).contains("Search is too complex"));
            window.frame();
            assertEquals(window.host.styles.notifyError(), window.notification.style());

            long revision = window.active().tabSession().model().revision();
            window.replacement.set("b");
            window.command(TuinkCommand.SEARCH_REPLACE_ALL);
            assertTrue(window.notification.message.startsWith("Search is too complex"));
            assertEquals(original, window.active().tabSession().model().getLine(0));
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
            assertFalse(window.active().tabSession().engine().undo());

            window.focusSearch(SearchFocus.FIND);
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput("a"));

            assertEquals("a", window.query.text());
            assertTrue(window.screenRow(window.height - 1).contains("Find 262144"));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.selected().uiState().searchMode);
            assertEquals(original, window.active().tabSession().model().getLine(0));
        }
    }
}
