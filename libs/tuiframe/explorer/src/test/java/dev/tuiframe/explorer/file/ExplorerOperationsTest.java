package dev.tuiframe.explorer.file;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.explorer.Explorer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerOperationsTest {
    @TempDir Path directory;
    private final ExplorerFileService files = new ExplorerFileService();
    private final ExplorerOperations operations = new ExplorerOperations(Explorer.Access.MANAGE_FILES, files);

    @Test
    void readOnlyAdmissionRejectsEveryMutationIncludingHistory() throws Exception {
        var readonly = new ExplorerOperations(Explorer.Access.READ_ONLY, files);
        Path source = Files.writeString(directory.resolve("source.txt"), "retained");
        Path parent = Files.createDirectory(directory.resolve("destination"));
        var target = files.inspect(source);
        for (var action : ExplorerFileService.Action.values()) {
            var result = readonly.perform(action, target, parent, "new.txt");
            assertFalse(result.succeeded(), action.toString());
            assertTrue(result.failure().getMessage().contains("read-only"));
        }
        assertFalse(readonly.undo().succeeded());
        assertFalse(readonly.redo().succeeded());
        assertFalse(readonly.canUndo());
        assertFalse(readonly.canRedo());
        assertEquals("retained", Files.readString(source));
        try (var entries = Files.list(parent)) { assertEquals(0, entries.count()); }
    }

    @Test
    void moveAndRenameHistoryPreserveTheLatestHostSavedBytesThroughBothDirections() throws Exception {
        Path original = Files.writeString(directory.resolve("source.txt"), "original");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        assertTrue(operations.perform(ExplorerFileService.Action.MOVE, files.inspect(original), destination, null).succeeded());
        Path moved = destination.resolve("source.txt");
        assertTrue(operations.perform(ExplorerFileService.Action.RENAME, files.inspect(moved), null, "renamed.txt").succeeded());
        Path renamed = destination.resolve("renamed.txt");
        Path saved = Files.writeString(destination.resolve("save.tmp"), "latest saved bytes");
        Files.move(saved, renamed, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(operations.refreshIdentityAfterSave(renamed));

        assertEquals(renamed, operations.undoEntry().source());
        assertTrue(operations.undo().succeeded());
        assertEquals("latest saved bytes", Files.readString(moved));
        assertTrue(operations.undo().succeeded());
        assertEquals("latest saved bytes", Files.readString(original));
        assertFalse(operations.canUndo());
        assertTrue(operations.redo().succeeded());
        assertTrue(operations.redo().succeeded());
        assertEquals("latest saved bytes", Files.readString(renamed));
        assertFalse(Files.exists(original));
        assertFalse(Files.exists(moved));
    }

    @Test
    void emptyCreatedEntriesCanBeRemovedAndRecreatedWithoutAnArchive() throws Exception {
        for (var action : List.of(ExplorerFileService.Action.CREATE_FILE, ExplorerFileService.Action.CREATE_DIRECTORY)) {
            String name = action == ExplorerFileService.Action.CREATE_FILE ? "empty.txt" : "empty-directory";
            Path entry = directory.resolve(name);
            assertTrue(operations.perform(action, null, directory, name).succeeded());
            assertEquals(ExplorerFileService.Action.DELETE, operations.undoEntry().action());
            var undone = operations.undo();
            assertTrue(undone.succeeded(), () -> String.valueOf(undone.failure()));
            assertEquals(Set.of(entry), undone.removedPaths());
            assertFalse(Files.exists(entry));
            assertTrue(operations.redo().succeeded());
            assertTrue(Files.exists(entry));
            assertEquals(action == ExplorerFileService.Action.CREATE_DIRECTORY, Files.isDirectory(entry));
            assertTrue(operations.undo().succeeded());
        }
    }

    @Test
    void inverseCreationNeverDeletesAddedContentEvenWithRestoredTimestamp() throws Exception {
        assertTrue(operations.perform(ExplorerFileService.Action.CREATE_FILE, null, directory, "created.txt").succeeded());
        Path file = directory.resolve("created.txt");
        var observed = files.inspect(file);
        Files.writeString(file, "new contents");
        Files.setLastModifiedTime(file, observed.modified());
        assertFalse(operations.undo().succeeded());
        assertEquals("new contents", Files.readString(file));
        assertTrue(operations.canUndo(), "A refused inverse must not consume the history entry");

        var folders = new ExplorerOperations(Explorer.Access.MANAGE_FILES, files);
        assertTrue(folders.perform(ExplorerFileService.Action.CREATE_DIRECTORY, null, directory, "folder").succeeded());
        Path folder = directory.resolve("folder");
        var before = files.inspect(folder);
        Files.writeString(folder.resolve("added.txt"), "retained");
        Files.setLastModifiedTime(folder, before.modified());
        assertFalse(folders.undo().succeeded());
        assertEquals("retained", Files.readString(folder.resolve("added.txt")));
    }

    @Test
    void aHostSavedNonemptyCreatedFileIsStillProtectedAgainstInverseDeletion() throws Exception {
        operations.perform(ExplorerFileService.Action.CREATE_FILE, null, directory, "created.txt");
        Path file = directory.resolve("created.txt");
        Path saved = Files.writeString(directory.resolve("save.tmp"), "owned saved text");
        Files.move(saved, file, StandardCopyOption.REPLACE_EXISTING);
        operations.refreshIdentityAfterSave(file);
        assertFalse(operations.undo().succeeded());
        assertEquals("owned saved text", Files.readString(file));
    }

    @Test
    void undoDoesNotOverwriteAConflictingPathAndCanBeRetried() throws Exception {
        Path original = Files.writeString(directory.resolve("source.txt"), "original");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        operations.perform(ExplorerFileService.Action.MOVE, files.inspect(original), destination, null);
        Files.writeString(original, "conflict");

        assertFalse(operations.undo().succeeded());
        assertEquals("conflict", Files.readString(original));
        assertEquals("original", Files.readString(destination.resolve("source.txt")));
        assertTrue(operations.canUndo());
        assertFalse(operations.canRedo());
        Files.delete(original);
        assertTrue(operations.undo().succeeded());
        assertEquals("original", Files.readString(original));
    }

    @Test
    void anExternallyReplacedMovedEntryCannotBeMovedByUndo() throws Exception {
        Path original = Files.writeString(directory.resolve("source.txt"), "original");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        operations.perform(ExplorerFileService.Action.MOVE, files.inspect(original), destination, null);
        Path moved = destination.resolve("source.txt");
        Path retained = destination.resolve("retained.txt");
        Files.move(moved, retained);
        Files.writeString(moved, "external replacement");

        assertFalse(operations.undo().succeeded());
        assertEquals("external replacement", Files.readString(moved));
        assertEquals("original", Files.readString(retained));
        assertFalse(Files.exists(original));
    }

    @Test
    void newSuccessClearsRedoButNoOpActionsDoNot() throws Exception {
        Path original = Files.writeString(directory.resolve("source.txt"), "original");
        operations.perform(ExplorerFileService.Action.RENAME, files.inspect(original), null, "renamed.txt");
        operations.undo();
        assertTrue(operations.canRedo());
        assertTrue(operations.perform(ExplorerFileService.Action.RENAME, files.inspect(original), null, "source.txt").succeeded());
        assertTrue(operations.canRedo());
        assertTrue(operations.perform(ExplorerFileService.Action.MOVE, files.inspect(original), directory, null).succeeded());
        assertTrue(operations.canRedo());
        operations.perform(ExplorerFileService.Action.RENAME, files.inspect(original), null, "different.txt");
        assertFalse(operations.canRedo());
        assertEquals("Undo Rename", operations.undoLabel());
    }

    @Test
    void permanentDeletionFormsAnExplicitHistoryBoundary() throws Exception {
        operations.perform(ExplorerFileService.Action.CREATE_FILE, null, directory, "created.txt");
        Path file = directory.resolve("created.txt");
        assertTrue(operations.perform(ExplorerFileService.Action.DELETE, files.inspect(file), null, null).succeeded());
        assertFalse(operations.canUndo());
        assertFalse(operations.canRedo());
        assertEquals("Deletion cannot be undone", operations.undo().failure().getMessage());
        assertFalse(Files.exists(file));
    }

    @Test
    void rootProtectionAlsoAppliesToDirectMutationAndHistoryCommands() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        operations.setProtectedRoot(root);
        for (var action : List.of(ExplorerFileService.Action.MOVE, ExplorerFileService.Action.RENAME,
            ExplorerFileService.Action.DELETE, ExplorerFileService.Action.TRASH)) {
            assertFalse(operations.perform(action, files.inspect(root), directory, "different").succeeded());
            assertTrue(Files.isDirectory(root));
        }
        operations.setProtectedRoot(directory);
        operations.perform(ExplorerFileService.Action.RENAME, files.inspect(root), null, "renamed");
        operations.setProtectedRoot(directory.resolve("renamed"));
        assertFalse(operations.undo().succeeded());
        assertTrue(Files.isDirectory(directory.resolve("renamed")));
    }

    @Test
    void menuQueriesDoNotWaitForAWorkerAndConcurrentCommandsAreRejected() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        TrashProvider provider = new TrashProvider() {
            @Override public Availability availability(Path source) { return Availability.supported(); }
            @Override public ExplorerFileService.Result trash(ExplorerFileService.Target source, Transfer transfer) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return new ExplorerFileService.Result(ExplorerFileService.Action.TRASH, source.path(), null,
                    false, false, new IOException("injected refusal"));
            }
        };
        var service = new ExplorerFileService(provider);
        var manager = new ExplorerOperations(Explorer.Access.MANAGE_FILES, service);
        Path file = Files.writeString(directory.resolve("source.txt"), "retained");
        var target = service.inspect(file);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> manager.perform(ExplorerFileService.Action.TRASH, target, null, null));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                    assertFalse(manager.canUndo());
                    assertFalse(manager.canRedo());
                    assertEquals("Undo", manager.undoLabel());
                    assertFalse(manager.undo().succeeded());
                });
            } finally { release.countDown(); }
            assertFalse(pending.get(5, TimeUnit.SECONDS).succeeded());
        }
        assertEquals("retained", Files.readString(file));
    }

    @Test
    void aPartialReceiptEndsTheReversibleHistoryWithoutHidingRemovedEntries() throws Exception {
        TrashProvider provider = new TrashProvider() {
            @Override public Availability availability(Path source) { return Availability.supported(); }
            @Override public ExplorerFileService.Result trash(ExplorerFileService.Target source, Transfer transfer) {
                try { Files.delete(source.path()); }
                catch (IOException failure) { throw new IllegalStateException(failure); }
                return new ExplorerFileService.Result(ExplorerFileService.Action.TRASH, source.path(), null,
                    false, true, new IOException("cleanup incomplete"), Set.of(source.path()));
            }
        };
        var manager = new ExplorerOperations(Explorer.Access.MANAGE_FILES, new ExplorerFileService(provider));
        manager.perform(ExplorerFileService.Action.CREATE_FILE, null, directory, "created.txt");
        Path file = directory.resolve("created.txt");
        var result = manager.perform(ExplorerFileService.Action.TRASH, files.inspect(file), null, null);
        assertFalse(result.succeeded());
        assertTrue(result.partiallyChanged());
        assertEquals(Set.of(file), result.removedPaths());
        assertFalse(manager.canUndo());
        assertFalse(manager.canRedo());
    }
}
