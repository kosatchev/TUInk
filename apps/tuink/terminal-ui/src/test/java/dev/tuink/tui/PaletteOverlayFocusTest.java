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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaletteOverlayFocusTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void menuRemovesPaletteFocusWithoutDisablingSearchOrLosingItsTarget(boolean preview, boolean resultsFocused) throws Exception {
        try (var window = new EditorWindowFixture(root, "document.md", false, List.of("needle original"))) {
            window.resize(40, 100);
            if (preview) {
                window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
                window.await(() -> window.preview().ready());
            }
            openSearch(window);
            window.event(new TextInput("needle"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> window.ui.sidebar.search.finished != null && (!preview || window.preview().ready()));
            if (resultsFocused) window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            var panel = window.ui.sidebar.search;
            var target = panel.focus.focused().orElseThrow();
            var selected = panel.listState;

            window.event(KeyStroke.of(KeyStroke.Key.F5));
            assertTrue(window.menu.isOpen());
            var search = window.ui.sidebar.placement.search();
            var list = search.list().contentBounds();
            var query = search.scope().target(ProjectSearchPanel.Target.QUERY).orElseThrow().placement().bounds();
            assertEquals(window.host.styles.windows().sidebarSelected(), window.screenStyle(list.y(), list.rightExclusive() - 1));
            assertEquals(window.host.styles.windows().sidebarBody(), window.screenStyle(list.y() + 1, list.rightExclusive() - 1));
            assertEquals(window.host.styles.windows().inputField(), window.screenStyle(query.y(), query.x() + 1));
            assertFalse(search.focused());
            assertEquals(target, panel.focus.focused().orElseThrow(), "The menu must retain the search target for its return");

            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertEquals("needle", panel.query.text(), "Menu input must not reach the query");
            assertEquals(selected, panel.listState, "Menu navigation must not move the search selection");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.menu.isOpen());
            assertEquals(target, panel.focus.focused().orElseThrow());
            assertEquals(resultsFocused ? window.host.styles.windows().sidebarSelectedFocused()
                    : window.host.styles.windows().sidebarSelected(),
                window.screenStyle(list.y(), list.rightExclusive() - 1));
            assertEquals(List.of("needle original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test void anEmptyQueryStillDisablesOnlyTheSearchActionUnderTheMenu() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.resize(40, 100);
            openSearch(window);
            window.event(KeyStroke.of(KeyStroke.Key.F5));
            assertTrue(window.menu.isOpen());
            var scope = window.ui.sidebar.placement.search().scope();
            assertTrue(scope.target(ProjectSearchPanel.Target.QUERY).orElseThrow().enabled());
            assertTrue(scope.target(ProjectSearchPanel.Target.MODE).orElseThrow().enabled());
            assertFalse(scope.target(ProjectSearchPanel.Target.FIND).orElseThrow().enabled());
        }
    }

    private void openSearch(EditorWindowFixture window) throws InterruptedException {
        window.controller.openFolder(root);
        window.await(() -> !window.ui.sidebar.tree.loading());
        window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
    }
}
