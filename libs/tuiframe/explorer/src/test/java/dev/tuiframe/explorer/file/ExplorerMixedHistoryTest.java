package dev.tuiframe.explorer.file;

import static dev.tuiframe.explorer.file.ExplorerFileService.Action.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.explorer.Explorer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Filesystem checkpoints across mixed operations and two complete history cycles. */
class ExplorerMixedHistoryTest {
    @TempDir Path directory;
    private final List<Path> otherStores = new ArrayList<>();
    private final ExplorerFileService files = new ExplorerFileService();
    private final ExplorerOperations operations = new ExplorerOperations(Explorer.Access.MANAGE_FILES, files);

    @AfterEach
    void removeOtherStoreFixtures() throws Exception {
        for (Path root : otherStores) try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createdParentsChildrenCopiesAndMovesSurviveRepeatedUndoRedo(boolean acrossStores) throws Exception {
        Path work = Files.createDirectory(directory.resolve("work"));
        Path copies = Files.createDirectory(work.resolve("copies"));
        Path remote = destination(acrossStores);
        Path folder = work.resolve("folder");
        Path nested = folder.resolve("nested");
        roundTrip(work, remote, List.of(
            () -> perform(CREATE_DIRECTORY, null, work, "folder"),
            () -> perform(CREATE_DIRECTORY, null, folder, "nested"),
            () -> perform(CREATE_FILE, null, nested, "empty.txt"),
            () -> perform(COPY, folder, copies, ""),
            () -> perform(RENAME, nested.resolve("empty.txt"), null, "renamed.txt"),
            () -> perform(MOVE, nested.resolve("renamed.txt"), folder, ""),
            () -> perform(MOVE, folder, remote, ""),
            () -> perform(RENAME, remote.resolve("folder"), null, "final"),
            () -> perform(COPY, remote.resolve("final"), work, "")
        ));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiedTreesAndNestedMutationsSurviveRepeatedUndoRedo(boolean acrossStores) throws Exception {
        Path work = Files.createDirectory(directory.resolve("work"));
        Path source = Files.createDirectory(work.resolve("source"));
        Path nested = Files.createDirectory(source.resolve("nested"));
        Files.writeString(nested.resolve("document.txt"), "original bytes");
        Files.createSymbolicLink(source.resolve("link"), Path.of("nested/document.txt"));
        Path copies = Files.createDirectory(work.resolve("copies"));
        Path clone = copies.resolve("source");
        Path renamed = copies.resolve("renamed");
        Path remote = destination(acrossStores);
        roundTrip(work, remote, List.of(
            () -> perform(COPY, source, copies, ""),
            () -> perform(RENAME, clone.resolve("nested/document.txt"), null, "renamed.txt"),
            () -> perform(MOVE, clone.resolve("nested/renamed.txt"), clone, ""),
            () -> perform(CREATE_DIRECTORY, null, clone, "new"),
            () -> perform(CREATE_FILE, null, clone.resolve("new"), "empty.txt"),
            () -> perform(RENAME, clone, null, "renamed"),
            () -> perform(MOVE, renamed, remote, ""),
            () -> perform(COPY, remote.resolve("renamed"), work, "")
        ));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void undoingAChildMutationAllowsUndoOfItsNowEmptyCreatedParent(boolean move) throws Exception {
        Path parent = directory.resolve("parent");
        perform(CREATE_DIRECTORY, null, directory, "parent");
        Path original = move ? Files.writeString(directory.resolve("original.txt"), "retained") : parent.resolve("child.txt");
        if (move) perform(MOVE, original, parent, "");
        else perform(CREATE_FILE, null, parent, "child.txt");
        succeeded(operations.undo());
        succeeded(operations.undo());
        assertFalse(Files.exists(parent));
        if (move) assertEquals("retained", Files.readString(original));
        for (int cycle = 0; cycle < 2; cycle++) {
            succeeded(operations.redo());
            succeeded(operations.redo());
            succeeded(operations.undo());
            succeeded(operations.undo());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownedSaveAndAncestorMovesNeverAuthorizeRemovingSavedCreatedText(boolean acrossStores) throws Exception {
        Path work = Files.createDirectory(directory.resolve("work"));
        Path remote = destination(acrossStores);
        Path folder = work.resolve("folder");
        perform(CREATE_DIRECTORY, null, work, "folder");
        perform(CREATE_FILE, null, folder, "document.txt");
        Path original = folder.resolve("document.txt");
        Path saved = Files.writeString(work.resolve("save.tmp"), "saved content");
        Files.move(saved, original, StandardCopyOption.REPLACE_EXISTING);
        operations.refreshIdentityAfterSave(original);
        perform(RENAME, original, null, "renamed.txt");
        perform(MOVE, folder, remote, "");
        for (int cycle = 0; cycle < 2; cycle++) {
            succeeded(operations.undo());
            succeeded(operations.undo());
            assertFalse(operations.undo().succeeded(), "Saved content must remain protected by inverse creation");
            assertEquals("saved content", Files.readString(original));
            succeeded(operations.redo());
            succeeded(operations.redo());
            assertEquals("saved content", Files.readString(remote.resolve("folder/renamed.txt")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ancestorTransfersDoNotBlessAnIdenticalExternalReplacementInsideACopy(boolean acrossStores) throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("child.txt"), "child");
        Files.writeString(source.resolve("sibling.txt"), "keep this replacement");
        Path copies = Files.createDirectory(directory.resolve("copies"));
        Path remote = destination(acrossStores);
        perform(COPY, source, copies, "");
        Path clone = copies.resolve("source");
        Path sibling = clone.resolve("sibling.txt");
        var expected = files.inspect(sibling);
        assumeTrue(expected.key() != null);
        Path replacement = Files.writeString(copies.resolve("replacement.tmp"), Files.readString(sibling));
        Files.setLastModifiedTime(replacement, expected.modified());
        Files.move(replacement, sibling, StandardCopyOption.REPLACE_EXISTING);
        perform(RENAME, clone.resolve("child.txt"), null, "renamed.txt");
        perform(MOVE, clone, remote, "");
        succeeded(operations.undo());
        succeeded(operations.undo());
        var refused = operations.undo();
        assertFalse(refused.succeeded(), "Ancestor relocation must preserve a copied sibling's original identity guard");
        assertTrue(refused.removedPaths().isEmpty());
        assertEquals("keep this replacement", Files.readString(sibling));
        assertEquals("child", Files.readString(clone.resolve("child.txt")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDestinationReplacementAfterTransferPublicationIsNotAcceptedAsItsOwnedIdentity(boolean replaceRoot) throws Exception {
        Path remote = destination(true);
        Path folder = Files.createDirectory(directory.resolve("folder"));
        Path child = Files.writeString(folder.resolve("child.txt"), "same bytes");
        var service = new ExplorerFileService(new ExplorerFileService.MoveObserver() {
            @Override public void published(Path source, Path destination) throws java.io.IOException {
                if (!destination.equals(remote.resolve("folder"))) return;
                Path target = destination.resolve("renamed.txt");
                var stamp = Files.getLastModifiedTime(target);
                if (replaceRoot) {
                    Files.move(destination, remote.resolve("retained"));
                    Files.createDirectory(destination);
                }
                Path replacement = Files.writeString(remote.resolve("replacement.tmp"), "same bytes");
                Files.setLastModifiedTime(replacement, stamp);
                Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
            }
        });
        var manager = new ExplorerOperations(Explorer.Access.MANAGE_FILES, service);
        succeeded(manager.perform(RENAME, service.inspect(child), null, "renamed.txt"));
        succeeded(manager.perform(MOVE, service.inspect(folder), remote, ""));
        if (replaceRoot) {
            assertFalse(manager.undo().succeeded(), "The relocated root itself must retain its publication identity");
            assertFalse(Files.exists(folder));
            assertEquals("same bytes", Files.readString(remote.resolve("folder/renamed.txt")));
        } else {
            succeeded(manager.undo());
            assertFalse(manager.undo().succeeded(), "A receipt must describe the privately verified copy, not a later replacement");
            assertEquals("same bytes", Files.readString(folder.resolve("renamed.txt")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCopyPublicationReplacementCompletesWithoutAuthorizingItsDeletion(boolean replaceRoot) throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path child = Files.writeString(source.resolve("document.txt"), "same bytes");
        var childTime = Files.getLastModifiedTime(child);
        var rootTime = Files.getLastModifiedTime(source);
        Path copies = Files.createDirectory(directory.resolve("copies"));
        var service = new ExplorerFileService(new ExplorerFileService.MoveObserver() {
            @Override public void published(Path original, Path destination) throws java.io.IOException {
                if (replaceRoot) {
                    Files.move(destination, copies.resolve("retained"));
                    Files.createDirectory(destination);
                }
                Path replacement = Files.writeString(copies.resolve("replacement.tmp"), "same bytes");
                Files.setLastModifiedTime(replacement, childTime);
                Files.move(replacement, destination.resolve("document.txt"), StandardCopyOption.REPLACE_EXISTING);
                Files.setLastModifiedTime(destination, rootTime);
            }
        });
        var manager = new ExplorerOperations(Explorer.Access.MANAGE_FILES, service);
        succeeded(manager.perform(COPY, service.inspect(source), copies, ""));
        assertFalse(manager.canUndo(), "A copy replaced after publication cannot enter reversible file history");
        assertNotNull(manager.historyBarrier());
        assertFalse(manager.undo().succeeded());
        assertEquals("same bytes", Files.readString(copies.resolve("source/document.txt")));
        assertEquals("same bytes", Files.readString(child));
    }

    private Path destination(boolean acrossStores) throws Exception {
        if (!acrossStores) return Files.createDirectory(directory.resolve("destination"));
        Path root = Path.of("/dev/shm");
        assumeTrue(Files.isDirectory(root) && Files.isWritable(root));
        assumeTrue(!Files.getFileStore(directory).equals(Files.getFileStore(root)));
        Path target = Files.createTempDirectory(root, "tuiframe-mixed-history-");
        otherStores.add(target);
        return target;
    }

    private void perform(ExplorerFileService.Action action, Path source, Path parent, String name) throws Exception {
        succeeded(operations.perform(action, source == null ? null : files.inspect(source), parent, name));
    }

    private static void succeeded(ExplorerFileService.Result result) {
        assertTrue(result.succeeded(), () -> result.action() + ": " + result.failure());
    }

    @FunctionalInterface private interface Operation { void run() throws Exception; }

    private void roundTrip(Path work, Path remote, List<Operation> actions) throws Exception {
        var checkpoints = new ArrayList<Map<String, String>>();
        checkpoints.add(snapshot(work, remote));
        for (Operation action : actions) {
            action.run();
            checkpoints.add(snapshot(work, remote));
        }
        for (int cycle = 0; cycle < 2; cycle++) {
            for (int index = actions.size() - 1; index >= 0; index--) {
                var result = operations.undo();
                assertTrue(result.succeeded(), "Undo checkpoint " + index + ", cycle " + cycle + ": " + result.failure());
                assertEquals(checkpoints.get(index), snapshot(work, remote), "Undo checkpoint " + index + ", cycle " + cycle);
            }
            assertFalse(operations.canUndo());
            for (int index = 1; index <= actions.size(); index++) {
                var result = operations.redo();
                assertTrue(result.succeeded(), "Redo checkpoint " + index + ", cycle " + cycle + ": " + result.failure());
                assertEquals(checkpoints.get(index), snapshot(work, remote), "Redo checkpoint " + index + ", cycle " + cycle);
            }
            assertFalse(operations.canRedo());
        }
    }

    private static Map<String, String> snapshot(Path... roots) throws Exception {
        var state = new LinkedHashMap<String, String>();
        for (int index = 0; index < roots.length; index++) {
            Path root = roots[index];
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted().toList()) {
                    String value = Files.isSymbolicLink(path) ? "link:" + Files.readSymbolicLink(path)
                        : Files.isDirectory(path) ? "directory" : "file:" + Files.readString(path);
                    state.put(index + ":" + root.relativize(path), value);
                }
            }
        }
        return state;
    }
}
