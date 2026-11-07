package dev.tuiframe.explorer.file;

import static dev.tuiframe.explorer.file.FileTreeTestSupport.awaitLoaded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreeLocalRefreshTest {
    @TempDir Path root;

    @Test
    void explorerRootIsAStableInitiallyExpandedNodeAndCanBeCollapsed() throws Exception {
        Path child = Files.createDirectory(root.resolve("child"));
        try (var model = new FileTreeModel(root, false, false, false, true)) {
            var initial = awaitLoaded(model);
            var entry = initial.entries().getFirst();
            assertEquals(root, entry.path());
            assertEquals(FileTreeModel.Kind.DIRECTORY, entry.kind());
            assertEquals(0, entry.depth());
            assertTrue(entry.expanded());
            assertEquals(child, initial.entries().get(1).path());
            assertEquals(1, initial.entries().get(1).depth());

            assertTrue(model.setDirectoryExpanded(entry.id(), false));
            var collapsed = awaitLoaded(model);
            assertEquals(List.of(root), collapsed.entries().stream().map(FileTreeModel.Entry::path).toList());
            assertEquals(entry.id(), collapsed.entries().getFirst().id());
            assertFalse(collapsed.entries().getFirst().expanded());

            assertTrue(model.setDirectoryExpanded(entry.id(), true));
            assertEquals(List.of(root, child), awaitLoaded(model).entries().stream().map(FileTreeModel.Entry::path).toList());
        }
    }

    @Test
    void localRefreshScansOnlyTheAffectedDirectoryAndRetainsExpandedSiblings() throws Exception {
        Path first = Files.createDirectory(root.resolve("first"));
        Path second = Files.createDirectory(root.resolve("second"));
        Path old = Files.createFile(first.resolve("old.txt"));
        Path sibling = Files.createFile(second.resolve("sibling.txt"));
        Map<Path, AtomicInteger> scans = new ConcurrentHashMap<>();
        try (var model = model(scans)) {
            awaitLoaded(model);
            expand(model, first);
            expand(model, second);
            scans.clear();
            Files.delete(old);
            Path created = Files.createFile(first.resolve("new.txt"));

            model.refreshDirectory(first);
            var updated = awaitLoaded(model);

            assertEquals(Map.of(first, 1), counts(scans));
            assertTrue(updated.entries().stream().anyMatch(entry -> entry.path().equals(created)));
            assertTrue(updated.entries().stream().anyMatch(entry -> entry.path().equals(sibling)));
            assertFalse(updated.entries().stream().anyMatch(entry -> entry.path().equals(old)));
            assertTrue(updated.entries().stream().filter(entry -> entry.path().equals(first) || entry.path().equals(second))
                .allMatch(FileTreeModel.Entry::expanded));

            scans.clear();
            model.refresh();
            awaitLoaded(model);
            assertEquals(Map.of(root, 1, first, 1, second, 1), counts(scans));
        }
    }

    @Test
    void parentRefreshDoesNotReuseChildrenOfAReplacedDirectoryWithTheSameName() throws Exception {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Files.createFile(folder.resolve("old.txt"));
        Map<Path, AtomicInteger> scans = new ConcurrentHashMap<>();
        try (var model = model(scans)) {
            awaitLoaded(model);
            expand(model, folder);
            scans.clear();
            Files.move(folder, root.resolve("retired"));
            Files.createDirectory(folder);
            Path replacement = Files.createFile(folder.resolve("new.txt"));

            model.refreshDirectory(root);
            var updated = awaitLoaded(model);

            assertEquals(Map.of(root, 1, folder, 1), counts(scans));
            assertTrue(updated.entries().stream().anyMatch(entry -> entry.path().equals(replacement)));
            assertFalse(updated.entries().stream().anyMatch(entry -> entry.path().equals(folder.resolve("old.txt"))));
        }
    }

    @Test
    void collapsedAndOutsideChangesDoNotReadUntilAnAffectedBranchIsExpanded() throws Exception {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Map<Path, AtomicInteger> scans = new ConcurrentHashMap<>();
        try (var model = model(scans)) {
            var before = awaitLoaded(model);
            scans.clear();
            Path created = Files.createFile(folder.resolve("new.txt"));
            model.refreshDirectory(folder);
            model.refreshDirectory(root.resolveSibling("outside"));
            assertSame(before, model.snapshot());
            assertTrue(scans.isEmpty());

            expand(model, folder);
            assertEquals(Map.of(folder, 1), counts(scans));
            assertTrue(model.snapshot().entries().stream().anyMatch(entry -> entry.path().equals(created)));
        }
    }

    @Test
    void revealLoadsTheAncestorChainWithoutRequiringIntermediateVisibleNodes() throws Exception {
        Path first = Files.createDirectory(root.resolve("first"));
        Path nested = Files.createDirectory(first.resolve("nested"));
        Path leaf = Files.createFile(nested.resolve("leaf.txt"));
        Map<Path, AtomicInteger> scans = new ConcurrentHashMap<>();
        try (var model = model(scans)) {
            awaitLoaded(model);
            scans.clear();
            assertTrue(model.reveal(leaf));
            var revealed = awaitLoaded(model);
            assertTrue(revealed.entries().stream().anyMatch(entry -> entry.path().equals(leaf)));
            assertEquals(Map.of(first, 1, nested, 1), counts(scans));
            assertFalse(model.reveal(root.resolveSibling("outside.txt")));
        }
    }

    private FileTreeModel model(Map<Path, AtomicInteger> scans) {
        return new FileTreeModel(root, false, false, (directory, hidden, consumer) -> {
            scans.computeIfAbsent(directory, ignored -> new AtomicInteger()).incrementAndGet();
            FileTreeModel.scanDirectory(directory, hidden, consumer);
        }, FileTreeLoadBudget.DEFAULT_LIMITS, Thread::new, false, true);
    }

    private static void expand(FileTreeModel model, Path directory) {
        var entry = model.snapshot().entries().stream().filter(candidate -> candidate.path().equals(directory)).findFirst().orElseThrow();
        assertTrue(model.setDirectoryExpanded(entry.id(), true));
        awaitLoaded(model);
    }

    private static Map<Path, Integer> counts(Map<Path, AtomicInteger> scans) {
        return scans.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    }
}
