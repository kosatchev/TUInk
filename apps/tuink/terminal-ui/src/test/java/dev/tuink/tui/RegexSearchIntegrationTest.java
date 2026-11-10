package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegexSearchIntegrationTest {
    @TempDir Path root;
    private void regex(EditorWindowFixture window) {
        window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
        window.focusSearch(SearchFocus.MODE);
        window.event(KeyStroke.of(KeyStroke.Key.SPACE)); window.event(KeyStroke.of(KeyStroke.Key.END)); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        window.focusSearch(SearchFocus.FIND);
    }

    @Test void invalidSourceExpressionPreservesDocumentAndRecoversWhenCorrected() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("id=42"))) {
            regex(window);
            window.event(new TextInput("[")); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(List.of("id=42"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
            assertTrue(java.util.stream.IntStream.range(0, 24).mapToObj(window::screenRow)
                .anyMatch(row -> row.contains("Invalid regular expression")));
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput("id=\\d+")); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(5, window.active().state().getCursor().col());
            assertEquals(0, window.active().state().getSelectionAnchor().col());
        }
    }

    @Test void previewInheritsTheSameRegexAndWholeWordOptions() throws Exception {
        try (var window = new EditorWindowFixture(root, "note.md", false, List.of("# id=42"))) {
            regex(window);
            window.focusSearch(SearchFocus.WORD); window.event(KeyStroke.of(KeyStroke.Key.SPACE));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.controller.previewFrame() != null);
            assertEquals(SearchOptions.MatchMode.REGEX, window.selected().uiState().searchUi.matchMode());
            assertTrue(window.selected().uiState().searchUi.wholeWord());
            window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
            window.event(new TextInput("id=\\d+"));
            assertEquals(1, window.selected().uiState().preview.searchMatches.size());
            assertEquals(List.of("# id=42"), window.active().tabSession().model().snapshotLines());
        }
    }
}
