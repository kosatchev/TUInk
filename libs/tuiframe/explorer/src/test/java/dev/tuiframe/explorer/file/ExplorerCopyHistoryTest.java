package dev.tuiframe.explorer.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.explorer.Explorer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerCopyHistoryTest {
    @TempDir Path directory;
    private final ExplorerFileService files = new ExplorerFileService();
    private final ExplorerOperations operations = new ExplorerOperations(Explorer.Access.MANAGE_FILES, files);

    @Test
    void unchangedFileAndTreeCopiesCanBeUndoneAndRedone() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("original.txt"), "original");
        Files.createDirectory(source.resolve("empty"));
        Path destination = Files.createDirectory(directory.resolve("destination"));
        assertTrue(operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), destination, "").succeeded());
        Path clone = destination.resolve("source");
        assertEquals("Undo Copy", operations.undoLabel());
        assertEquals(ExplorerFileService.Action.DELETE, operations.undoEntry().action());
        assertEquals(clone, operations.undoEntry().source());
        var undone = operations.undo();
        assertTrue(undone.succeeded(), () -> String.valueOf(undone.failure()));
        assertTrue(undone.removedPaths().contains(clone.resolve("original.txt")));
        assertFalse(Files.exists(clone));
        assertEquals("original", Files.readString(source.resolve("original.txt")));
        assertEquals("Redo Copy", operations.redoLabel());
        assertTrue(operations.redo().succeeded());
        assertEquals("original", Files.readString(clone.resolve("original.txt")));
        assertTrue(operations.undo().succeeded(), "Redo must capture the new copy's identities");
    }

    @Test
    void changedCopyBytesWithUnchangedSizeAndTimestampCannotBeDeletedByUndo() throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "before");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), destination, "");
        Path clone = destination.resolve("source.txt");
        var observed = files.inspect(clone);
        Files.writeString(clone, "edited");
        Files.setLastModifiedTime(clone, observed.modified());

        var refused = operations.undo();

        assertFalse(refused.succeeded());
        assertTrue(refused.failure().getMessage().contains("changed"));
        assertTrue(refused.removedPaths().isEmpty());
        assertEquals("edited", Files.readString(clone));
        assertEquals("before", Files.readString(source));
        assertTrue(operations.canUndo());
    }

    @Test
    void newChildrenAndReplacedEntriesRefuseTheWholeTreeInverseBeforeDeletion() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("original.txt"), "original");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), destination, "");
        Path clone = destination.resolve("source");
        var timestamp = Files.getLastModifiedTime(clone);
        Files.writeString(clone.resolve("added.txt"), "external");
        Files.setLastModifiedTime(clone, timestamp);
        assertFalse(operations.undo().succeeded());
        assertEquals("original", Files.readString(clone.resolve("original.txt")));
        assertEquals("external", Files.readString(clone.resolve("added.txt")));
        assertTrue(operations.canUndo());
    }

    @Test
    void anIdenticalExternalReplacementStillFailsTheIdentityGuard() throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "original");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), destination, "");
        Path clone = destination.resolve("source.txt");
        var old = files.inspect(clone);
        assumeTrue(old.key() != null);
        Path replacement = Files.writeString(destination.resolve("replacement.tmp"), "original");
        Files.setLastModifiedTime(replacement, old.modified());
        Files.move(replacement, clone, StandardCopyOption.REPLACE_EXISTING);

        assertFalse(operations.undo().succeeded());
        assertEquals("original", Files.readString(clone));
    }

    @Test
    void ownedSavesCannotAuthorizeDeletingAnEditedCopyAndSourceEditsRefuseRedo() throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "original");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), destination, "");
        Path clone = destination.resolve("source.txt");
        Path saved = Files.writeString(destination.resolve("save.tmp"), "saved edits");
        Files.move(saved, clone, StandardCopyOption.REPLACE_EXISTING);
        assertFalse(operations.refreshIdentityAfterSave(clone));
        assertFalse(operations.undo().succeeded());
        assertEquals("saved edits", Files.readString(clone));

        var other = new ExplorerOperations(Explorer.Access.MANAGE_FILES, files);
        Path second = Files.createDirectory(directory.resolve("second"));
        other.perform(ExplorerFileService.Action.COPY, files.inspect(source), second, "");
        Files.writeString(source, "new source contents");
        other.refreshIdentityAfterSave(source);
        assertTrue(other.undo().succeeded(), "Editing the original must not protect an unchanged independent copy");
        assertFalse(other.redo().succeeded());
        assertFalse(Files.exists(second.resolve("source.txt")));
        assertEquals("new source contents", Files.readString(source));
        assertTrue(other.canRedo());
    }

    @Test
    void copyMoveRenameAndTheirInversesKeepTheGuardForTheOriginalCopy() throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "original");
        Path copies = Files.createDirectory(directory.resolve("copies"));
        Path moved = Files.createDirectory(directory.resolve("moved"));
        operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), copies, "");
        Path clone = copies.resolve("source.txt");
        operations.perform(ExplorerFileService.Action.MOVE, files.inspect(clone), moved, "");
        operations.perform(ExplorerFileService.Action.RENAME, files.inspect(moved.resolve("source.txt")), null, "renamed.txt");
        assertTrue(operations.undo().succeeded());
        assertTrue(operations.undo().succeeded());
        assertEquals("Undo Copy", operations.undoLabel());
        assertTrue(operations.undo().succeeded());
        assertFalse(Files.exists(clone));
        assertEquals("original", Files.readString(source));
        assertTrue(operations.redo().succeeded());
        assertTrue(operations.redo().succeeded());
        assertTrue(operations.redo().succeeded());
        assertEquals("original", Files.readString(moved.resolve("renamed.txt")));
    }

    @Test
    void restoredNestedNamesAndLocationsRefreshOnlyTheirOwnedDirectoryTimes() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("original.txt"), "original");
        Files.createDirectory(source.resolve("nested"));
        Path copies = Files.createDirectory(directory.resolve("copies"));
        operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), copies, "");
        Path clone = copies.resolve("source");
        operations.perform(ExplorerFileService.Action.RENAME, files.inspect(clone.resolve("original.txt")), null, "renamed.txt");
        assertTrue(operations.undo().succeeded());
        operations.perform(ExplorerFileService.Action.MOVE, files.inspect(clone.resolve("original.txt")), clone.resolve("nested"), "");
        assertTrue(operations.undo().succeeded());
        assertTrue(operations.undo().succeeded(), "Restored descendants must allow the original copy to be undone");
        assertFalse(Files.exists(clone));
        assertEquals("original", Files.readString(source.resolve("original.txt")));
        assertTrue(operations.redo().succeeded());
        assertTrue(operations.redo().succeeded(), "Recreated subtree identities must reach the pending child move");
        assertEquals("original", Files.readString(clone.resolve("nested/original.txt")));
    }

    @Test
    void redoCopyRefreshesTheIdentityExpectedByAPendingChildRename() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("original.txt"), "original");
        Path copies = Files.createDirectory(directory.resolve("copies"));
        operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), copies, "");
        Path clone = copies.resolve("source");
        operations.perform(ExplorerFileService.Action.RENAME, files.inspect(clone.resolve("original.txt")), null, "renamed.txt");
        assertTrue(operations.undo().succeeded());
        assertTrue(operations.undo().succeeded());
        assertTrue(operations.redo().succeeded());
        assertTrue(operations.redo().succeeded());
        assertEquals("original", Files.readString(clone.resolve("renamed.txt")));
    }

    @Test
    void inverseOwnedCreationRestoresAnOuterCopyGuardAcrossBothHistoryDirections() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("original.txt"), "original");
        Path copies = Files.createDirectory(directory.resolve("copies"));
        operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), copies, "");
        Path clone = copies.resolve("source");
        operations.perform(ExplorerFileService.Action.CREATE_FILE, null, clone, "empty.txt");
        assertTrue(operations.undo().succeeded());
        assertTrue(operations.undo().succeeded(), "Removing our empty child must restore the parent copy guard");
        assertTrue(operations.redo().succeeded());
        assertTrue(operations.redo().succeeded());
        assertTrue(Files.exists(clone.resolve("empty.txt")));
        assertTrue(operations.undo().succeeded());
        assertTrue(operations.undo().succeeded());
    }

    @Test
    void nestedCopiesRestoreOuterGuardsAndTheirCapturedSourceIdentities() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("original.txt"), "original");
        Files.createDirectory(source.resolve("nested"));
        Path copies = Files.createDirectory(directory.resolve("copies"));
        operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), copies, "");
        Path clone = copies.resolve("source");
        operations.perform(ExplorerFileService.Action.COPY, files.inspect(clone.resolve("original.txt")), clone.resolve("nested"), "");
        assertTrue(operations.undo().succeeded());
        assertTrue(operations.undo().succeeded());
        assertTrue(operations.redo().succeeded());
        assertTrue(operations.redo().succeeded(), "The inner copy's unchanged source was recreated by the outer redo");
        assertEquals("original", Files.readString(clone.resolve("nested/original.txt")));
        assertTrue(operations.undo().succeeded());
        assertTrue(operations.undo().succeeded());
    }

    @Test
    void inverseChildRelocationCannotAuthorizeAReplacedUnrelatedSibling() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("child.txt"), "child");
        Files.writeString(source.resolve("sibling.txt"), "sibling");
        Path copies = Files.createDirectory(directory.resolve("copies"));
        operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), copies, "");
        Path clone = copies.resolve("source");
        Path sibling = clone.resolve("sibling.txt");
        var observed = files.inspect(sibling);
        assumeTrue(observed.key() != null);
        operations.perform(ExplorerFileService.Action.RENAME, files.inspect(clone.resolve("child.txt")), null, "renamed.txt");
        Path replacement = Files.writeString(copies.resolve("replacement.tmp"), "sibling");
        Files.setLastModifiedTime(replacement, observed.modified());
        Files.move(replacement, sibling, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(operations.undo().succeeded());

        assertFalse(operations.undo().succeeded());
        assertEquals("sibling", Files.readString(sibling));
        assertEquals("child", Files.readString(clone.resolve("child.txt")));
    }

    @Test
    void inverseCrossFilesystemMoveRefreshesOnlyTheProvenCopiedIdentity() throws Exception {
        Path otherStore = Path.of("/dev/shm");
        assumeTrue(Files.isDirectory(otherStore) && Files.isWritable(otherStore));
        assumeTrue(!Files.getFileStore(directory).equals(Files.getFileStore(otherStore)));
        Path moved = Files.createTempDirectory(otherStore, "tuiframe-copy-history-");
        try {
            Path source = Files.writeString(directory.resolve("source.txt"), "original");
            Path copies = Files.createDirectory(directory.resolve("copies"));
            assertTrue(operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), copies, "").succeeded());
            Path clone = copies.resolve("source.txt");
            assertTrue(operations.perform(ExplorerFileService.Action.MOVE, files.inspect(clone), moved, "").succeeded());
            assertTrue(operations.undo().succeeded());
            assertTrue(operations.undo().succeeded(), "Returning from another filesystem creates a new, proved copy identity");
            assertFalse(Files.exists(clone));
            assertEquals("original", Files.readString(source));
        } finally {
            try (var paths = Files.walk(moved)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void copyRootOutsideIsAllowedWhileReadonlyAndPhysicalDescendantsAreRejected() throws Exception {
        Path source = Files.createDirectory(directory.resolve("root"));
        Files.writeString(source.resolve("document.txt"), "source");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        operations.setProtectedRoot(source);
        assertTrue(operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), destination, "").succeeded());
        assertTrue(operations.undo().succeeded());
        var readonly = new ExplorerOperations(Explorer.Access.READ_ONLY, files);
        assertFalse(readonly.perform(ExplorerFileService.Action.COPY, files.inspect(source), destination, "").succeeded());
        assertFalse(operations.perform(ExplorerFileService.Action.COPY, files.inspect(source), source, "").succeeded());
        assertTrue(Files.exists(source.resolve("document.txt")));
        assertFalse(Files.exists(destination.resolve("root")));
    }

    @Test
    void anOversizedHistoryGuardDoesNotTurnACommittedCopyIntoAFailure() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("one.txt"), "one");
        Files.writeString(source.resolve("two.txt"), "two");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        var limited = new ExplorerOperations(Explorer.Access.MANAGE_FILES, files, 2, 4);
        limited.perform(ExplorerFileService.Action.CREATE_FILE, null, directory, "previous.txt");

        var copied = limited.perform(ExplorerFileService.Action.COPY, files.inspect(source), destination, "");

        assertTrue(copied.succeeded());
        assertTrue(copied.destinationPublished());
        assertEquals("two", Files.readString(destination.resolve("source/two.txt")));
        assertFalse(limited.canUndo());
        assertTrue(limited.historyBarrier().contains("limit"));
        assertTrue(Files.exists(directory.resolve("previous.txt")));
    }
}
