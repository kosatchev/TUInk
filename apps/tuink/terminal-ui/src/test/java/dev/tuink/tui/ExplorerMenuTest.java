package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.widget.MenuBar;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerMenuTest {
    @TempDir Path directory;

    @Test
    void explorerHasItsOwnMenuAndKeepsExistingMenuShortcuts() {
        var ui = UiSessionState.from(AppSettings.defaults());
        var router = new CommandRouter();
        var menu = menu(ui);
        assertEquals(List.of(FOLDER_OPEN, FOLDER_CLOSE, PROJECT_SEARCH_IN_FOLDER, EXPLORER_NEW_FILE, EXPLORER_NEW_DIRECTORY,
            EXPLORER_RENAME, EXPLORER_COPY, EXPLORER_MOVE, EXPLORER_TRASH, EXPLORER_DELETE, EXPLORER_UNDO,
            EXPLORER_REDO, EXPLORER_REFRESH, EXPLORER_TOGGLE, EXPLORER_FOCUS,
            EXPLORER_CYCLE_WIDTH), commands(menu, TopMenuController.EXPLORER_INDEX));
        assertEquals(MENU_EXPLORER.id(), router.invocationFor(KeyStroke.of(KeyStroke.Key.F7)).orElseThrow().id());
        assertEquals(MENU_HELP.id(), router.invocationFor(KeyStroke.of(KeyStroke.Key.F6)).orElseThrow().id());
        for (int index : List.of(TopMenuController.FILE_INDEX, TopMenuController.VIEW_INDEX)) {
            var other = TopMenuView.build(ui, List.of(), -1, "Cyan", router,
                MenuBar.State.open(index), TestSurfaces.source(ui));
            assertFalse(commands(other, index).contains(FOLDER_OPEN));
            assertFalse(commands(other, index).contains(EXPLORER_REFRESH));
        }
    }

    @Test
    void rootAllowsCreationAndCopyWhileOnlyChildrenCanBeRenamedMovedOrDeleted() throws Exception {
        Path file = Files.writeString(directory.resolve("file.txt"), "text");
        var ui = UiSessionState.from(AppSettings.defaults());
        try (var explorer = ui.sidebar) {
            explorer.open(directory);
            awaitTree(explorer);
            assertTrue(explorer.tree.select(directory));
            var root = menu(ui);
            assertTrue(item(root, EXPLORER_NEW_FILE).enabled());
            assertTrue(item(root, EXPLORER_NEW_DIRECTORY).enabled());
            assertTrue(item(root, EXPLORER_COPY).enabled());
            assertFalse(item(root, EXPLORER_RENAME).enabled());
            assertFalse(item(root, EXPLORER_MOVE).enabled());
            assertFalse(item(root, EXPLORER_TRASH).enabled());
            assertFalse(item(root, EXPLORER_DELETE).enabled());

            assertTrue(explorer.tree.select(file));
            var child = menu(ui);
            assertTrue(item(child, EXPLORER_NEW_FILE).enabled());
            assertTrue(item(child, EXPLORER_RENAME).enabled());
            assertTrue(item(child, EXPLORER_COPY).enabled());
            assertTrue(item(child, EXPLORER_MOVE).enabled());
            assertTrue(item(child, EXPLORER_TRASH).enabled());
            assertTrue(item(child, EXPLORER_DELETE).enabled());

            explorer.mutating = true;
            var busy = menu(ui);
            for (var command : List.of(FOLDER_OPEN, FOLDER_CLOSE, EXPLORER_NEW_FILE,
                EXPLORER_NEW_DIRECTORY, EXPLORER_RENAME, EXPLORER_COPY, EXPLORER_MOVE, EXPLORER_TRASH,
                EXPLORER_DELETE, EXPLORER_UNDO, EXPLORER_REDO, EXPLORER_REFRESH)) {
                assertFalse(item(busy, command).enabled(), command.title());
            }
        }
    }

    @Test
    void fileActionsRequireAnOpenTreeAndWidthShowsPresetsOrDraggedValue() {
        var ui = UiSessionState.from(AppSettings.defaults());
        var empty = menu(ui);
        assertTrue(item(empty, FOLDER_OPEN).enabled());
        for (var command : List.of(FOLDER_CLOSE, EXPLORER_NEW_FILE, EXPLORER_NEW_DIRECTORY,
            EXPLORER_RENAME, EXPLORER_COPY, EXPLORER_MOVE, EXPLORER_TRASH, EXPLORER_DELETE,
            EXPLORER_UNDO, EXPLORER_REDO, EXPLORER_REFRESH)) {
            assertFalse(item(empty, command).enabled(), command.title());
        }
        for (var expected : List.of("Narrow (24)", "Normal (30)", "Wide (40)", "Custom (37)")) {
            ui.sidebar.preferredWidth = Integer.parseInt(expected.substring(expected.indexOf('(') + 1,
                expected.indexOf(')')));
            assertEquals("[>] Width: " + expected, item(menu(ui), EXPLORER_CYCLE_WIDTH).label());
        }
    }

    private static void awaitTree(SidebarState explorer) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (explorer.tree.loading() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(explorer.tree.loading(), "The small fixture directory must finish loading");
    }

    private static TopMenuView menu(UiSessionState ui) {
        return TopMenuView.build(ui, List.of(), -1, "Cyan", new CommandRouter(),
            MenuBar.State.open(TopMenuController.EXPLORER_INDEX), TestSurfaces.source(ui));
    }

    private static List<TuinkCommand> commands(TopMenuView menu, int index) {
        return menu.menus().get(index).entries().stream().filter(MenuBar.Item.class::isInstance)
            .map(entry -> TuinkCommand.fromId(((CommandInvocation) ((MenuBar.Item<?>) entry).value()).id()).orElseThrow())
            .toList();
    }

    private static MenuBar.Item<?> item(TopMenuView menu, TuinkCommand command) {
        return menu.menus().get(TopMenuController.EXPLORER_INDEX).entries().stream()
            .filter(entry -> entry instanceof MenuBar.Item<?> item
                && ((CommandInvocation) item.value()).id().equals(command.id()))
            .map(entry -> (MenuBar.Item<?>) entry).findFirst().orElseThrow();
    }
}
