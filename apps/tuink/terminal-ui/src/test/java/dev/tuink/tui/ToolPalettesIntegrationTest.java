package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettingsStoreFixtures;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolPalettesIntegrationTest {
    @TempDir Path root;
    private void open(EditorWindowFixture window) throws Exception {
        window.resize(40, 100); window.controller.openFolder(root);
        window.await(() -> !window.ui.sidebar.tree.loading());
        window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
    }
    private void clickArrow(EditorWindowFixture window, SidebarState.Content id) {
        var header = window.ui.sidebar.placement.header(id).view().bounds();
        window.pointer(PointerEvent.Kind.PRESS, header.y(), header.x());
        window.pointer(PointerEvent.Kind.RELEASE, header.y(), header.x());
    }
    private void toggleHints(EditorWindowFixture window, SidebarState.Content id) {
        var header = window.ui.sidebar.placement.header(id).view().bounds();
        window.pointer(PointerEvent.Kind.PRESS, header.y(), header.rightExclusive() - 2);
        window.pointer(PointerEvent.Kind.RELEASE, header.y(), header.rightExclusive() - 2);
        window.event(KeyStroke.of(KeyStroke.Key.END));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }
    private void holdForReorder(EditorWindowFixture window, SidebarState.Content id) {
        var dock = window.ui.sidebar;
        var press = dock.placement.header(id).view().state().press();
        assertNotNull(press);
        assertFalse(dock.tick(press.startedAt() + 499_999_999L));
        assertTrue(dock.tick(press.startedAt() + 500_000_000L));
        window.frame();
    }
    @Test void shortHeaderMotionDoesNotReorderOrCollapseEitherPalette() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            open(window); var dock = window.ui.sidebar;
            var search = dock.placement.header(SidebarState.Content.SEARCH).view().bounds();
            var files = dock.placement.header(SidebarState.Content.FOLDERS).view().bounds();
            window.pointer(PointerEvent.Kind.PRESS, search.y(), search.x() + 5);
            window.pointer(PointerEvent.Kind.DRAG, files.y(), files.x() + 5);
            assertEquals(-1, dock.insertionRow);
            window.pointer(PointerEvent.Kind.RELEASE, files.y(), files.x() + 5);
            assertTrue(dock.filesExpanded); assertTrue(dock.searchExpanded); assertFalse(dock.searchFirst);
            assertEquals(-1L, dock.nanosUntilWakeup(System.nanoTime()));
        }
    }
    @Test void eachPaletteOffersItsOwnPersistentHintToggleWithoutChangingSearchOrSelection() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("needle original"))) {
            open(window); var dock = window.ui.sidebar;
            window.event(new TextInput("needle")); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> dock.search.finished != null);
            var results = dock.search.rows; var selected = dock.selectedEntry();
            assertTrue(dock.filesHints); assertTrue(dock.search.hintsVisible);
            var hint = dock.placement.filesBody().hintBounds();
            assertEquals(window.host.styles.windows().notifyInfo(), window.screenStyle(hint.y(), hint.x()));
            toggleHints(window, SidebarState.Content.FOLDERS);
            assertFalse(dock.filesHints); assertTrue(dock.search.hintsVisible);
            assertTrue(dock.placement.filesBody().hintBounds().isEmpty());
            assertEquals(dock.placement.dock().slot(SidebarState.Content.FOLDERS).body().bottomExclusive(), dock.placement.tree().bounds().bottomExclusive());
            toggleHints(window, SidebarState.Content.SEARCH);
            assertFalse(dock.search.hintsVisible); assertNull(dock.placement.search().footer());
            assertSame(results, dock.search.rows); assertEquals(selected, dock.selectedEntry());
            assertEquals("needle", dock.search.query.text());
            Path path = root.resolve("hints.properties");
            AppSettingsStoreFixtures.save(path, window.ui.settingsSnapshot(window.host.theme));
            var restored = UiSessionState.from(AppSettingsStoreFixtures.loadSnapshot(path).settings());
            assertEquals(dock.preferences(), restored.sidebar.preferences());
            assertFalse(restored.sidebar.filesHints); assertFalse(restored.sidebar.search.hintsVisible);
            toggleHints(window, SidebarState.Content.FOLDERS);
            toggleHints(window, SidebarState.Content.SEARCH);
            assertFalse(dock.placement.filesBody().hintBounds().isEmpty()); assertNotNull(dock.placement.search().footer());
            assertEquals(List.of("needle original"), window.active().tabSession().model().snapshotLines());
        }
    }
    @Test void listAccentRequiresListFocusAndRetainsSelectionOnHeadersInputsAndTheDocument() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("needle original"))) {
            open(window); var dock = window.ui.sidebar;
            window.event(new TextInput("needle")); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> dock.search.finished != null);
            window.command(TuinkCommand.EXPLORER_FOCUS);
            var tree = dock.placement.tree().contentBounds();
            var accent = window.host.styles.windows().sidebarSelectedFocused();
            var inactive = window.host.styles.windows().sidebarSelected();
            assertEquals(accent, window.screenStyle(tree.y(), tree.rightExclusive() - 1));
            window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
            assertTrue(dock.headerFocused);
            assertEquals(inactive, window.screenStyle(tree.y(), tree.rightExclusive() - 1));
            window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
            var list = dock.placement.search().list().contentBounds();
            assertEquals(inactive, window.screenStyle(list.y(), list.x()));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertEquals(ProjectSearchPanel.Target.RESULTS, dock.search.focus.focused().orElseThrow());
            assertEquals(accent, window.screenStyle(list.y(), list.x()));
            window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
            assertEquals(inactive, window.screenStyle(list.y(), list.x()));
            window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
            assertTrue(dock.headerFocused); assertEquals(inactive, window.screenStyle(list.y(), list.x()));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(dock.focused); assertEquals(inactive, window.screenStyle(list.y(), list.x()));
        }
    }
    @Test void paletteListsUseSelectionForFocusWhileQueryAndDocumentKeepTheirCaret() throws Exception {
        try (var window = new EditorWindowFixture(root, "document.md", false, List.of("needle original"))) {
            window.resize(40, 100);
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            open(window);
            var dock = window.ui.sidebar;
            window.event(new TextInput("needle")); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> dock.search.finished != null && window.preview().ready());
            assertTrue(window.controller.previewFrame().cursor().visible(), "The search query has an editing caret");
            window.command(TuinkCommand.EXPLORER_FOCUS);
            assertFalse(window.controller.previewFrame().cursor().visible(), "A cursor must not invert the folder disclosure mark");
            assertTrue(dock.tree.select(window.document)); window.frame();
            assertFalse(window.controller.previewFrame().cursor().visible(), "File labels also use the row selection alone");
            window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
            assertTrue(window.controller.previewFrame().cursor().visible());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertFalse(window.controller.previewFrame().cursor().visible(), "Search groups follow the same rule as folders");
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_LEFT));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            assertFalse(window.controller.previewFrame().cursor().visible());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.controller.previewFrame().cursor().visible(), "Returning to the document restores its caret");
        }
    }
    @Test void titleClickCollapsesAndExpandsAndDotsMenuPrefersThePaletteBody() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            open(window); var dock = window.ui.sidebar;
            for (boolean expanded : List.of(false, true)) {
                var header = dock.placement.header(SidebarState.Content.SEARCH).view().bounds();
                window.pointer(PointerEvent.Kind.PRESS, header.y(), header.x() + 5);
                window.pointer(PointerEvent.Kind.RELEASE, header.y(), header.x() + 5);
                assertEquals(expanded, dock.searchExpanded);
            }
            var header = dock.placement.header(SidebarState.Content.SEARCH).view().bounds();
            int dots = header.rightExclusive() - 2;
            window.pointer(PointerEvent.Kind.PRESS, header.y(), dots);
            window.pointer(PointerEvent.Kind.RELEASE, header.y(), dots);
            var menu = dock.placement.menu().view().menuBounds();
            var owner = dock.placement.dock().slot(SidebarState.Content.SEARCH).body();
            assertTrue(owner.contains(menu));
            assertTrue(menu.intersection(header).isEmpty());
            assertTrue(window.screenRow(menu.y()).contains("Collapse"));
            int column = menu.x() + 4;
            window.pointer(PointerEvent.Kind.PRESS, menu.y() + 1, column);
            window.pointer(PointerEvent.Kind.RELEASE, menu.y() + 1, column);
            assertEquals(SidebarState.Content.SEARCH, dock.maximized);
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }
    private void maximizeFromHeaderMenu(EditorWindowFixture window) {
        window.event(new TextInput(" "));
        assertNotNull(window.ui.sidebar.placement.menu());
        window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }
    @Test void bothBodiesWorkAndCollapsingSearchRetainsItsQueryResultsAndFiles() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("needle original"))) {
            open(window); var dock = window.ui.sidebar; var tree = dock.tree;
            assertNotNull(dock.placement.tree()); assertNotNull(dock.placement.search());
            window.event(new TextInput("needle")); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> dock.search.finished != null);
            assertTrue(dock.search.matches() > 0);
            var results = dock.search.rows;
            clickArrow(window, SidebarState.Content.SEARCH);
            assertFalse(dock.searchExpanded); assertNull(dock.placement.search());
            assertSame(tree, dock.tree); assertSame(results, dock.search.rows);
            clickArrow(window, SidebarState.Content.SEARCH);
            assertNotNull(dock.placement.search()); assertEquals("needle", dock.search.query.text());
            assertSame(results, dock.search.rows);
            window.command(TuinkCommand.EXPLORER_FOCUS);
            assertNotNull(dock.placement.search()); assertNotNull(dock.placement.tree());
            assertEquals(List.of("needle original"), window.active().tabSession().model().snapshotLines());
        }
    }
    @Test void capturedHeightResizeAndTitleReorderNeverMoveTheDocumentCursor() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            open(window); var dock = window.ui.sidebar; var cursor = window.active().state().getCursor();
            var divider = dock.placement.heights().getFirst().view().bounds();
            int height = dock.placement.dock().slot(SidebarState.Content.FOLDERS).body().height();
            window.pointer(PointerEvent.Kind.PRESS, divider.y(), divider.x() + 2);
            assertTrue(dock.heightState.dragging());
            window.pointer(PointerEvent.Kind.DRAG, divider.y() + 3, divider.x() + 2);
            assertEquals(height + 3, dock.placement.dock().slot(SidebarState.Content.FOLDERS).body().height());
            window.pointer(PointerEvent.Kind.RELEASE, 0, 90);
            assertFalse(dock.heightState.dragging());
            var search = dock.placement.header(SidebarState.Content.SEARCH).view().bounds();
            var files = dock.placement.header(SidebarState.Content.FOLDERS).view().bounds();
            window.pointer(PointerEvent.Kind.PRESS, search.y(), search.x() + 5);
            holdForReorder(window, SidebarState.Content.SEARCH);
            window.pointer(PointerEvent.Kind.DRAG, files.y(), files.x() + 5);
            assertEquals(files.y(), dock.insertionRow);
            window.pointer(PointerEvent.Kind.RELEASE, files.y(), files.x() + 5);
            assertTrue(dock.searchFirst);
            assertEquals(SidebarState.Content.SEARCH, dock.placement.dock().slots().getFirst().id());
            assertEquals(-1, dock.insertionRow); assertEquals(cursor, window.active().state().getCursor());
            assertFalse(window.editorView.isMouseSelecting());
        }
    }
    @Test void resizingOrHidingDuringAReorderCancelsTheGestureWithoutChangingOrder() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            open(window); var dock = window.ui.sidebar;
            var header = dock.placement.header(SidebarState.Content.SEARCH).view().bounds();
            window.pointer(PointerEvent.Kind.PRESS, header.y(), header.x() + 5);
            holdForReorder(window, SidebarState.Content.SEARCH);
            window.pointer(PointerEvent.Kind.DRAG, header.y() - 2, header.x() + 5);
            assertTrue(dock.insertionRow >= 0);
            window.resize(30, 100); assertEquals(-1, dock.insertionRow);
            window.pointer(PointerEvent.Kind.RELEASE, 1, 4);
            assertFalse(dock.searchFirst);
            header = dock.placement.header(SidebarState.Content.SEARCH).view().bounds();
            window.pointer(PointerEvent.Kind.PRESS, header.y(), header.x() + 5);
            window.pointer(PointerEvent.Kind.DRAG, header.y() - 2, header.x() + 5);
            window.command(TuinkCommand.EXPLORER_TOGGLE);
            assertFalse(dock.visible); assertEquals(-1, dock.insertionRow);
            window.pointer(PointerEvent.Kind.RELEASE, 1, 4);
            window.command(TuinkCommand.EXPLORER_TOGGLE);
            assertFalse(dock.searchFirst); assertNotNull(dock.placement);
        }
    }
    @Test void keyboardMenuMaximizesRestoresAndSmallTerminalsKeepTheSavedLayout() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            open(window); var dock = window.ui.sidebar;
            window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
            assertTrue(dock.headerFocused);
            var before = dock.preferences();
            maximizeFromHeaderMenu(window);
            assertEquals(SidebarState.Content.SEARCH, dock.maximized);
            assertNull(dock.placement.tree());
            assertEquals(dock.placement.bounds().height() - 1, dock.placement.dock().slot(SidebarState.Content.SEARCH).body().height());
            maximizeFromHeaderMenu(window);
            assertNull(dock.maximized); assertEquals(before, dock.preferences());
            window.resize(9, 100);
            assertTrue(dock.placement.dock().slot(SidebarState.Content.FOLDERS).body().isEmpty());
            assertFalse(dock.placement.dock().slot(SidebarState.Content.SEARCH).body().isEmpty());
            assertEquals(before, dock.preferences());
            window.resize(40, 100); assertNotNull(dock.placement.tree()); assertEquals(before, dock.preferences());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_UP, KeyStroke.Modifier.ALT));
            assertTrue(dock.searchFirst);
            int width = dock.preferredWidth;
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.CTRL));
            assertEquals(width + 2, dock.preferredWidth);
            assertFalse(window.active().state().isDirty());
        }
    }
    @Test void runtimeLayoutSurvivesSettingsSaveLoadAndLaunchOverrides() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            open(window); var dock = window.ui.sidebar;
            window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_UP, KeyStroke.Modifier.ALT));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.CTRL));
            clickArrow(window, SidebarState.Content.FOLDERS);
            var expected = dock.preferences();
            Path path = root.resolve("settings.properties");
            AppSettingsStoreFixtures.save(path, window.ui.settingsSnapshot(window.host.theme));
            var session = new AppSettingsSession(AppSettingsStoreFixtures.loadSnapshot(path).settings(), TuiCliOptions.parse(new String[]{"--no-mouse"}));
            var restored = UiSessionState.from(session.initialSettings());
            assertEquals(expected, restored.sidebar.preferences());
            assertEquals(expected, session.preferences(restored.settingsSnapshot(window.host.theme)).palettes());
            assertFalse(restored.sidebar.filesExpanded); assertTrue(restored.sidebar.searchExpanded);
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }
    @Test void maximizingACollapsedPaletteRestoresItsPreviousCollapsedState() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            open(window); var dock = window.ui.sidebar;
            clickArrow(window, SidebarState.Content.SEARCH);
            var before = dock.preferences(); assertFalse(dock.searchExpanded);
            maximizeFromHeaderMenu(window);
            assertNotNull(dock.placement.search()); assertFalse(dock.searchExpanded);
            maximizeFromHeaderMenu(window);
            assertEquals(before, dock.preferences()); assertNull(dock.placement.search());
        }
    }
}
