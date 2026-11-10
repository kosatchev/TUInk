package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PreviewSearchToolbarIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enterOnThePreviousButtonMovesBackwardInsteadOfBecomingFindNext(boolean frozen) throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false,
                List.of("# Header", "", "needle one needle two needle three"))) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            if (frozen) {
                window.command(TuinkCommand.TAB_PREVIOUS);
                window.command(TuinkCommand.TAB_CLOSE);
                window.await(() -> window.selected() instanceof PreviewTab preview
                    && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
            }
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("needle"));
            window.command(TuinkCommand.SEARCH_NEXT);
            window.command(TuinkCommand.SEARCH_NEXT);
            assertEquals(1, window.preview().activeMatch);
            for (int index = 0; index < 5; index++) window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(SearchFocus.PREV_BUTTON, window.selected().uiState().searchFocusManager.focused().orElseThrow());
            assertFalse(window.selected().uiState().searchEditorFocused);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(0, window.preview().activeMatch);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(2, window.preview().activeMatch);
            assertEquals("needle", window.preview().searchQuery);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sharedFindFooterHasTheSourceSurfaceAndSafeControlsForLiveAndFrozenPreview(boolean frozen) throws Exception {
        var lines = List.of("# Header", "", "needle Needle needleX");
        try (var window = new EditorWindowFixture(directory, "document.md", false, lines)) {
            var source = window.active().tabSession();
            long revision = source.model().revision();
            window.command(TuinkCommand.SEARCH_OPEN);
            int sourceRow = findRow(window);
            int sourceColumn = window.screenRow(sourceRow).indexOf("Find:") + 6;
            var sourceStyle = window.screenStyle(sourceRow, sourceColumn);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            if (frozen) {
                window.command(TuinkCommand.TAB_PREVIOUS);
                window.command(TuinkCommand.TAB_CLOSE);
                window.await(() -> window.selected() instanceof PreviewTab preview
                    && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
            }
            window.controller.openFolder(directory);
            // The controller API only invalidates chrome; publish its new pane
            // before asking whether the old-width Markdown layout is ready.
            window.frame();
            window.await(() -> !window.ui.sidebar.tree.loading() && window.preview().ready());
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("needle"));
            var preview = window.preview();
            assertEquals("needle", preview.searchQuery, "The real Find field must receive the query before layout completion");
            window.await(() -> preview.ready() && preview.searchMatches.size() == 3);
            var bar = preview.searchBar();
            assertNotNull(bar);
            assertTrue(bar.readOnly());
            var query = bar.targetBounds(SearchFocus.FIND);
            assertTrue(query.y() >= preview.bounds.bottomExclusive());
            assertTrue(query.x() >= preview.bounds.x());
            assertTrue(window.ui.sidebar.placement.bounds().bottomExclusive() > query.y(),
                "The document footer must not shorten the neighbouring tree");
            assertEquals(sourceStyle, window.screenStyle(query.y(), query.x()));
            assertFalse(window.screenRow(query.y()).contains("Replace"));
            for (var absent : List.of(SearchFocus.REPLACE, SearchFocus.REPLACE_BUTTON, SearchFocus.ALL_BUTTON, SearchFocus.ALL))
                assertTrue(bar.focusScope().target(absent).isEmpty());
            assertEquals(3, preview.searchMatches.size(), () -> "query=" + preview.searchQuery
                + ", field=" + preview.searchField.text() + ", ready=" + preview.ready()
                + ", width=" + preview.bounds.width() + ", requested=" + preview.requestedWidth
                + ", pending=" + (preview.pending != null) + ", focus=" + window.controller.activeSurface().focus()
                + ", options=" + window.selected().uiState().searchUi + ", error=" + preview.error);
            click(window, SearchFocus.CASE);
            assertTrue(window.selected().uiState().searchUi.caseSensitive());
            assertEquals(2, preview.searchMatches.size());
            click(window, SearchFocus.WORD);
            assertEquals(1, preview.searchMatches.size());
            click(window, SearchFocus.NEXT_BUTTON);
            assertEquals(0, preview.activeMatch);
            click(window, SearchFocus.PREV_BUTTON);
            assertEquals(0, preview.activeMatch);
            window.command(TuinkCommand.SEARCH_REPLACE_ALL);
            assertEquals("needle", preview.searchQuery);
            if (frozen) assertTrue(window.tabs.isEmpty(), "Readonly search must not create an editing source");
            else {
                assertEquals(lines, source.model().snapshotLines());
                assertEquals(revision, source.model().revision());
                assertFalse(source.state().isDirty());
            }
        }
    }

    private static int findRow(EditorWindowFixture window) {
        for (int row = 0; row < window.height; row++) if (window.screenRow(row).contains("Find:")) return row;
        throw new AssertionError("Find row is not visible");
    }

    private static void click(EditorWindowFixture window, SearchFocus id) {
        var bounds = window.preview().searchBar().targetBounds(id);
        window.pointer(PointerEvent.Kind.PRESS, bounds.y(), bounds.x() + 1);
        window.pointer(PointerEvent.Kind.RELEASE, bounds.y(), bounds.x() + 1);
    }
}
