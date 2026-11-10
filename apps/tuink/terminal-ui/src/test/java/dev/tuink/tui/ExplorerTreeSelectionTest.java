package dev.tuink.tui;

import dev.tuiframe.explorer.Explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerTreeSelectionTest {
    @TempDir Path root;

    @Test
    void rootAndChildShareTheSameSelectionAndTheTreeLeavesSpaceForOptionalPaletteHelp() throws Exception {
        Path child = Files.createFile(root.resolve("child.txt"));
        try (var explorer = new SidebarState()) {
            explorer.open(root);
            awaitLoaded(explorer.tree);
            var body = new Rect(0, 1, 80, 12);
            var placement = explorer.place(explorer.prepareLayout(body), body, true);
            assertEquals(body.y() + 1, placement.tree().bounds().y());
            assertEquals(body.height() - 3, placement.tree().bounds().height());
            assertEquals(placement.filesBody().contentBounds(), placement.tree().bounds());
            assertEquals(1, placement.filesBody().hintBounds().height());
            assertEquals(1, placement.dock().slot(SidebarState.Content.SEARCH).bounds().height());
            assertEquals(root, explorer.selectedEntry().path());
            assertTrue(explorer.selectedRoot());
            assertTrue(explorer.selectedDirectory());

            assertTrue(explorer.tree.select(child));
            explorer.place(explorer.prepareLayout(body), body, true);
            assertEquals(child, explorer.selectedEntry().path());
            assertFalse(explorer.selectedRoot());
            assertFalse(explorer.selectedDirectory());
            explorer.filesHints = false;
            placement = explorer.place(explorer.prepareLayout(body), body, true);
            assertEquals(placement.dock().slot(SidebarState.Content.FOLDERS).body(), placement.tree().bounds());
            assertEquals(child, explorer.selectedEntry().path());
        }
    }

    @Test
    void revealSelectsANestedFileAndBringsItsRowIntoTheViewport() throws Exception {
        Path first = Files.createDirectory(root.resolve("first"));
        Path nested = Files.createDirectory(first.resolve("nested"));
        Path file = Files.createFile(nested.resolve("file.txt"));
        try (var tree = Explorer.sidebar(root)) {
            awaitLoaded(tree);
            var bounds = new Rect(0, 0, 30, 2);
            tree.place(bounds, bounds, true, true);
            assertTrue(tree.reveal(file));
            awaitLoaded(tree);
            var placed = tree.place(bounds, bounds, true, true);
            assertEquals(file, placed.selectedEntry().orElseThrow().path());
            assertTrue(placed.cursorPosition().isPresent(), "The revealed selection must be visible");
        }
    }

    private static void awaitLoaded(Explorer tree) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (tree.loading() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000L);
        assertFalse(tree.loading());
    }
}
