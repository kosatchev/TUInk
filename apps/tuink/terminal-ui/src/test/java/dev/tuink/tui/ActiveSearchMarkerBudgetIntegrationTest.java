package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.KeyStroke;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.render.EditorStyleAdapter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ActiveSearchMarkerBudgetIntegrationTest {
    @Test
    @Timeout(30)
    void costlyInactiveTabDoesNotDisableLocalCountsHighlightsOrNavigation(@TempDir Path directory) throws Exception {
        String original = "a".repeat(160) + "z";
        String query = "a?".repeat(80) + "z";
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(original))) {
            var inactive = window.host.newEditorTab();
            inactive.tabSession().model().replaceLines(List.of("a".repeat(4_100_000) + "z"));
            window.context.addAll(List.of(inactive));
            window.frame();
            window.command(TuinkCommand.SEARCH_OPEN);
            window.focusSearch(SearchFocus.MODE);
            window.event(new TextInput(" "));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN)); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.focusSearch(SearchFocus.FIND);

            window.event(new TextInput(query));

            assertEquals(query, window.query.text());
            assertTrue(window.screenRow(window.height - 1).contains("Find 1"));
            assertFalse(window.screenRow(window.height - 1).contains("Search is too complex"));
            assertTrue(window.screenRow(1).contains("#document.txt"));
            int documentRow = window.rowStartingWith(" 1 ");
            int firstCharacter = window.screenRow(documentRow).indexOf('a');
            var highlighted = EditorStyleAdapter.toCell(window.host.styles.windows().renderSearchMatch());
            assertEquals(highlighted, window.screenStyle(documentRow, firstCharacter));
            window.frame();
            assertEquals(highlighted, window.screenStyle(documentRow, firstCharacter));

            window.command(TuinkCommand.SEARCH_NEXT);

            assertEquals(new Position(0, original.length()), window.active().state().getCursor());
            assertEquals(new Position(0, 0), window.active().state().getSelectionAnchor());
            assertEquals(original, window.active().tabSession().model().getLine(0));

            window.focusSearch(SearchFocus.ALL);
            window.event(new TextInput(" "));

            assertEquals(query, window.query.text());
            assertTrue(window.notification.message.startsWith("Search is too complex"));
            assertTrue(window.screenRow(window.height - 1).contains("Search is too complex"));

            window.event(new TextInput(" "));

            assertEquals(query, window.query.text());
            assertTrue(window.screenRow(window.height - 1).contains("Find 1"));
            assertFalse(window.screenRow(window.height - 1).contains("Search is too complex"));
            assertEquals(original, window.active().tabSession().model().getLine(0));
        }
    }
}
