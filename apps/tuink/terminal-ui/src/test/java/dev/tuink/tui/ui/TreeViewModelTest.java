package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeViewModelTest {
    @TempDir
    Path root;

    @Test
    void facadePreservesSelectionWhileDelegatingHiddenAndExpansionState() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Path child = Files.createFile(folder.resolve("child.txt"));
        Files.createFile(root.resolve(".hidden.txt"));

        try (TreeView tree = new TreeView(root, false, false)) {
            tree.moveDown(10);
            assertEquals(folder, tree.selectedItem().path());

            tree.setShowHiddenFiles(true);
            assertEquals(folder, tree.selectedItem().path());

            assertTrue(tree.toggleSelectedAndRefresh());
            assertEquals(folder, tree.selectedItem().path());
            assertTrue(tree.selectedItem().expanded());

            tree.moveDown(10);
            assertEquals(child, tree.selectedItem().path());
            assertEquals(1, tree.selectedItem().depth());

            tree.setRoot(folder.resolve("."));
            assertEquals(folder.toAbsolutePath().normalize(), tree.root());
            assertTrue(tree.selectedItem().parentLink());
            assertEquals(root.toAbsolutePath().normalize(), tree.selectedItem().path());
        }
    }

    @Test
    void metadataSignalReplacesTheFacadeSnapshotWithoutMutatingTheOldItem() throws IOException {
        for (int index = 0; index < 256; index++) {
            Files.writeString(root.resolve("aa-filler-%03d.txt".formatted(index)), "x");
        }
        Path target = root.resolve("zz-target.txt");
        Files.writeString(target, "payload");

        try (TreeView tree = new TreeView(root, true, true)) {
            tree.moveEnd(300);
            tree.setShowDateColumn(false);
            tree.setShowDateColumn(true);
            TreeView.Item pending = tree.selectedItem();

            assertEquals(target, pending.path());
            assertEquals("...", pending.sizeValue());
            assertEquals("...", pending.dateValue());

            TreeView.Item enriched = awaitMetadata(tree);

            assertEquals(target, enriched.path());
            assertEquals(Files.size(target) + "B", enriched.sizeValue());
            assertEquals("...", pending.sizeValue());
            assertEquals("...", pending.dateValue());
        }
    }

    private static TreeView.Item awaitMetadata(TreeView tree) {
        long startedAt = System.nanoTime();
        long timeoutNanos = Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() - startedAt < timeoutNanos) {
            if (tree.consumeMetadataChanged()) {
                TreeView.Item current = tree.selectedItem();
                if (!"...".equals(current.sizeValue()) && !"...".equals(current.dateValue())) {
                    return current;
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("metadata was not published before the deadline");
    }
}
