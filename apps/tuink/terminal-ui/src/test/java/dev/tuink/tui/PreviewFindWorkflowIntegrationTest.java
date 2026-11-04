package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.search.SearchOptions;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PreviewFindWorkflowIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void longQueryKeepsItsDisplayedViewportAcrossBodyAndModalFocus(ActiveSurface.Kind kind) throws Exception {
        try (var window = window(kind, List.of("# Test", "", "needle one needle two needle three"))) {
            window.command(TuinkCommand.SEARCH_OPEN);
            String query = "PREFIX-" + "абв界😀".repeat(30) + "-SUFFIX";
            window.event(new TextInput(query));
            var preview = window.preview();
            int queryRow = preview.searchPlacement().bounds().y();
            String displayed = window.screenRow(queryRow);
            assertTrue(displayed.contains("SUFFIX"));
            assertFalse(displayed.contains("PREFIX"));
            int cursor = preview.searchField.cursor();
            clickBody(window);
            assertTrue(window.selected().uiState().searchEditorFocused);
            assertEquals(displayed, window.screenRow(queryRow), "Losing focus must not replace the field's viewport");
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            assertEquals(displayed, window.screenRow(queryRow), "A modal must paint the same retained query placement");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(preview.searchVisible);
            assertEquals(displayed, window.screenRow(queryRow));
            window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
            assertFalse(window.selected().uiState().searchEditorFocused);
            assertEquals(displayed, window.screenRow(queryRow));
            assertEquals(cursor, preview.searchField.cursor());
            assertEquals(query, preview.searchQuery);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void repeatedFindAndEnterAdvanceFromEitherOwnerWhilePreviousAndEscapeRemainConsistent(ActiveSurface.Kind kind) throws Exception {
        try (var window = window(kind, List.of("# Test", "", "needle one needle two needle three"))) {
            window.event(ctrl(KeyStroke.Key.F));
            window.event(new TextInput("needle"));
            var preview = window.preview();
            assertEquals(3, preview.searchMatches.size());
            window.event(ctrl(KeyStroke.Key.F));
            assertEquals(0, preview.activeMatch, "Repeating Find advances the existing query");
            window.event(ctrl(KeyStroke.Key.F));
            assertEquals(1, preview.activeMatch);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(2, preview.activeMatch);
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertTrue(window.selected().uiState().searchEditorFocused);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(0, preview.activeMatch, "Readonly body Enter is Find Next while Find is visible");
            assertFalse(window.notification.isError());
            window.event(ctrl(KeyStroke.Key.G));
            assertEquals(2, preview.activeMatch);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(preview.searchVisible);
            assertTrue(window.controller.running());
            window.event(ctrl(KeyStroke.Key.Q));
            assertFalse(window.controller.running());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void findWithAnEmptyQueryReturnsFromTheBodyToTheRealQueryField(ActiveSurface.Kind kind) throws Exception {
        try (var window = window(kind, List.of("# Test", "", "needle one needle two"))) {
            window.event(ctrl(KeyStroke.Key.F));
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertTrue(window.selected().uiState().searchEditorFocused);
            assertEquals("", window.preview().searchQuery);
            window.event(ctrl(KeyStroke.Key.F));
            assertFalse(window.selected().uiState().searchEditorFocused);
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            window.event(new TextInput("needle"));
            assertEquals("needle", window.preview().searchQuery);
            assertEquals(2, window.preview().searchMatches.size());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void projectionMatchingUsesTheSourceQueryOptionsWithoutConstructingAnEditor(ActiveSurface.Kind kind) throws Exception {
        try (var window = window(kind, List.of("# Test", "", "ALPHA alpha alphabet alphaX βeta İx Ix ıx ix"))) {
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("alpha"));
            var preview = window.preview();
            assertEquals(4, preview.searchMatches.size(), "The shared UI default ignores case");
            window.selected().uiState().searchUi = new SearchController.SearchUiOptions(true, SearchOptions.MatchMode.SUBSTRING, false);
            window.frame();
            assertEquals(3, preview.searchMatches.size());
            window.selected().uiState().searchUi = new SearchController.SearchUiOptions(false, SearchOptions.MatchMode.WHOLE_WORD, false);
            window.frame();
            assertEquals(2, preview.searchMatches.size());
            window.selected().uiState().searchUi = new SearchController.SearchUiOptions(false, SearchOptions.MatchMode.WILDCARD, false);
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            window.event(new TextInput("i?"));
            assertEquals(4, preview.searchMatches.size(), "Unicode folding and wildcard semantics come from the common matcher");
            if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) assertTrue(window.tabs.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void previewInheritsTheSourceMatchingOptionsOnceAndKeepsItsOwnLocalQueryScope(ActiveSurface.Kind kind) throws Exception {
        var options = new SearchController.SearchUiOptions(true, SearchOptions.MatchMode.WILDCARD, true);
        try (var window = window(kind, List.of("# Test", "", "ALPHA alpha"), options)) {
            assertEquals(new SearchController.SearchUiOptions(true, SearchOptions.MatchMode.WILDCARD, false),
                window.selected().uiState().searchUi);
            if (!window.tabs.isEmpty()) window.tabs.getFirst().uiState().searchUi = SearchController.SearchUiOptions.DEFAULT;
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("a?pha"));
            assertEquals(1, window.preview().searchMatches.size());
            assertFalse(window.selected().uiState().searchUi.allTabs());
            assertEquals("a?pha", window.preview().searchQuery);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void clipboardAlwaysUsesTheActualFieldOrBodyOwnerAndKeepsTheQuery(ActiveSurface.Kind kind) throws Exception {
        try (var window = window(kind, List.of("# Test", "", "needle one needle two"))) {
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("needle"));
            window.event(ctrl(KeyStroke.Key.A)); window.event(ctrl(KeyStroke.Key.C));
            assertEquals("needle", window.host.clipboard.text());
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertTrue(window.selected().uiState().searchEditorFocused);
            window.event(ctrl(KeyStroke.Key.A)); window.event(ctrl(KeyStroke.Key.C));
            assertEquals(window.preview().result.document().projection().text(), window.host.clipboard.text());
            assertEquals("needle", window.preview().searchQuery);
            window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
            window.event(ctrl(KeyStroke.Key.A));
            window.host.clipboard.setText("needle\none");
            window.event(ctrl(KeyStroke.Key.V));
            assertEquals("needle one", window.preview().searchQuery);
            assertEquals(1, window.preview().searchMatches.size());
        }
    }

    private static void clickBody(EditorWindowFixture window) {
        var body = window.preview().contentBounds();
        window.pointer(PointerEvent.Kind.PRESS, body.y(), body.x() + 1);
        window.pointer(PointerEvent.Kind.RELEASE, body.y(), body.x() + 1);
    }

    private EditorWindowFixture window(ActiveSurface.Kind kind, List<String> source) throws Exception {
        return window(kind, source, SearchController.SearchUiOptions.DEFAULT);
    }

    private EditorWindowFixture window(ActiveSurface.Kind kind, List<String> source,
        SearchController.SearchUiOptions options) throws Exception {
        var window = new EditorWindowFixture(directory.resolve(kind.name()), "document.md", source,
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])));
        window.selected().uiState().searchUi = options;
        window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW); window.await(() -> window.preview().ready());
        if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW); window.command(TuinkCommand.TAB_CLOSE);
            window.await(() -> window.selected() instanceof PreviewTab preview
                && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
        }
        return window;
    }

    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
}
