package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchParameterMatrixIntegrationTest {
    @TempDir Path root;
    private static final List<String> TEXT = List.of("# alpha a.b aaa a1b", "", "🙂 é id=42", "tail");
    private static final List<String> QUERIES = List.of("alpha", "a.b", "a?b", "a*b", "*", "?", "[", "(",
        "\\d+", "^$", ".*", "\\b", "(?=a)", "🙂", "é");

    @Test void sourceCombinationsNeverEscapeTheWindowOrEditTheDocument() throws Exception {
        try (var window = new EditorWindowFixture(root, false, TEXT)) {
            window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
            for (var mode : SearchOptions.MatchMode.values()) for (boolean insensitive : List.of(false, true))
                for (boolean word : List.of(false, true)) for (String query : QUERIES) {
                    String combination = mode + ", ignoreCase=" + insensitive + ", word=" + word + ", query=" + query;
                    assertDoesNotThrow(() -> {
                        window.selected().uiState().searchUi = new SearchController.SearchUiOptions(!insensitive, mode, false, word);
                        window.focusSearch(SearchFocus.FIND);
                        window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
                        window.event(new TextInput(query));
                        window.command(TuinkCommand.SEARCH_NEXT); window.command(TuinkCommand.SEARCH_PREVIOUS);
                        window.focusSearch(SearchFocus.WORD); window.event(KeyStroke.of(KeyStroke.Key.SPACE));
                        window.focusSearch(SearchFocus.CASE); window.event(KeyStroke.of(KeyStroke.Key.SPACE));
                        window.focusSearch(SearchFocus.MODE); window.event(KeyStroke.of(KeyStroke.Key.SPACE));
                        window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN)); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                    }, combination);
                    assertEquals(TEXT, window.active().tabSession().model().snapshotLines(), combination);
                }
        }
    }

    @Test void previewCombinationsNeverEscapeTheWindowOrEditTheSource() throws Exception {
        try (var window = new EditorWindowFixture(root, "note.md", false, TEXT)) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.controller.previewFrame() != null);
            window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
            for (var mode : SearchOptions.MatchMode.values()) for (boolean insensitive : List.of(false, true))
                for (boolean word : List.of(false, true)) for (String query : QUERIES) {
                    String combination = mode + ", ignoreCase=" + insensitive + ", word=" + word + ", query=" + query;
                    assertDoesNotThrow(() -> {
                        window.selected().uiState().searchUi = new SearchController.SearchUiOptions(!insensitive, mode, false, word);
                        window.selected().uiState().preview.setSearchQuery(query);
                        window.frame();
                        window.command(TuinkCommand.SEARCH_NEXT); window.command(TuinkCommand.SEARCH_PREVIOUS);
                        window.selected().uiState().preview.setSearchOptions(new SearchOptions(insensitive, mode.next(), !word));
                        window.frame();
                    }, combination);
                    assertEquals(TEXT, window.active().tabSession().model().snapshotLines(), combination);
                }
        }
    }

    @Test void projectCombinationsAndInvalidMasksStayInsideTheSearchPanel() throws Exception {
        Files.writeString(root.resolve("closed.txt"), String.join("\n", TEXT));
        try (var window = new EditorWindowFixture(root, false, TEXT)) {
            window.controller.openFolder(root); window.frame();
            window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
            for (var mode : SearchOptions.MatchMode.values()) for (boolean insensitive : List.of(false, true))
                for (boolean word : List.of(false, true)) for (String query : QUERIES)
                    for (String mask : List.of("", "*.txt", "!*.txt", "[")) {
                        String combination = mode + ", ignoreCase=" + insensitive + ", word=" + word + ", query=" + query + ", In=" + mask;
                        assertDoesNotThrow(() -> {
                            var panel = window.ui.sidebar.search;
                            var previous = panel.finished;
                            panel.mode = mode; panel.caseSensitive = !insensitive; panel.wholeWord = word;
                            panel.includes.set(mask);
                            window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
                            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
                            window.event(new TextInput(query)); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                            window.await(() -> panel.finished != null && panel.finished != previous);
                        }, combination);
                        assertEquals(TEXT, window.active().tabSession().model().snapshotLines(), combination);
                    }
        }
    }
}
