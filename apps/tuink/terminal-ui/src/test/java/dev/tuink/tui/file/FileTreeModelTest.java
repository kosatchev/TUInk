package dev.tuink.tui.file;

import static dev.tuink.tui.file.FileTreeTestSupport.awaitLoaded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreeModelTest {
    @TempDir
    Path root;

    @Test
    void snapshotSortsDirectoriesBeforeFilesAndAppliesHiddenFilter() throws IOException {
        Path alphaDirectory = Files.createDirectory(root.resolve("Alpha-dir"));
        Path zetaDirectory = Files.createDirectory(root.resolve("zeta-dir"));
        Path hiddenDirectory = Files.createDirectory(root.resolve(".secret-dir"));
        Path alphaFile = Files.createFile(root.resolve("alpha.txt"));
        Path zuluFile = Files.createFile(root.resolve("Zulu.txt"));
        Path hiddenFile = Files.createFile(root.resolve(".secret.txt"));

        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Snapshot initial = awaitLoaded(model);

            assertParentEntry(initial, root.getParent());
            assertEquals(
                List.of("Alpha-dir", "zeta-dir", "alpha.txt", "Zulu.txt"),
                contentEntries(initial).stream().map(FileTreeModel.Entry::name).toList()
            );
            assertEquals(
                List.of(
                    FileTreeModel.Kind.DIRECTORY,
                    FileTreeModel.Kind.DIRECTORY,
                    FileTreeModel.Kind.FILE,
                    FileTreeModel.Kind.FILE
                ),
                contentEntries(initial).stream().map(FileTreeModel.Entry::kind).toList()
            );
            assertFalse(containsPath(initial, hiddenDirectory));
            assertFalse(containsPath(initial, hiddenFile));
            assertEntryIdentity(initial, alphaDirectory, FileTreeModel.Kind.DIRECTORY);
            assertEntryIdentity(initial, zetaDirectory, FileTreeModel.Kind.DIRECTORY);
            assertEntryIdentity(initial, alphaFile, FileTreeModel.Kind.FILE);
            assertEntryIdentity(initial, zuluFile, FileTreeModel.Kind.FILE);

            long initialRevision = initial.revision();
            model.setShowHiddenFiles(true);
            FileTreeModel.Snapshot withHidden = awaitLoaded(model);

            assertTrue(withHidden.revision() > initialRevision);
            assertEquals(
                List.of(
                    ".secret-dir",
                    "Alpha-dir",
                    "zeta-dir",
                    ".secret.txt",
                    "alpha.txt",
                    "Zulu.txt"
                ),
                contentEntries(withHidden).stream().map(FileTreeModel.Entry::name).toList()
            );
            long visibleRevision = withHidden.revision();
            model.setShowHiddenFiles(true);
            assertEquals(visibleRevision, awaitLoaded(model).revision(), "an unchanged option must not rebuild");
        }
    }

    @Test
    void expansionPublishesFlatDepthFirstRowsAndSetRootClearsExpansion() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Path nestedDirectory = Files.createDirectory(folder.resolve("nested"));
        Path childFile = Files.createFile(folder.resolve("child.txt"));
        Path rootFile = Files.createFile(root.resolve("root.txt"));

        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Entry folderEntry = entry(awaitLoaded(model), folder, FileTreeModel.Kind.DIRECTORY);
            FileTreeModel.Entry rootFileEntry = entry(awaitLoaded(model), rootFile, FileTreeModel.Kind.FILE);
            long collapsedRevision = awaitLoaded(model).revision();

            assertTrue(model.toggleDirectory(folderEntry.id()));
            FileTreeModel.Snapshot expanded = awaitLoaded(model);
            assertTrue(expanded.revision() > collapsedRevision);
            assertEquals(
                List.of("folder", "nested", "child.txt", "root.txt"),
                contentEntries(expanded).stream().map(FileTreeModel.Entry::name).toList()
            );
            assertEquals(
                List.of(0, 1, 1, 0),
                contentEntries(expanded).stream().map(FileTreeModel.Entry::depth).toList()
            );
            assertTrue(entry(expanded, folder, FileTreeModel.Kind.DIRECTORY).expanded());
            assertFalse(entry(expanded, nestedDirectory, FileTreeModel.Kind.DIRECTORY).expanded());
            assertFalse(model.toggleDirectory(rootFileEntry.id()));
            assertEquals(expanded.revision(), awaitLoaded(model).revision());

            model.setRoot(folder.resolve("."));
            FileTreeModel.Snapshot nestedRoot = awaitLoaded(model);
            assertEquals(folder.toAbsolutePath().normalize(), model.root());
            assertEquals(model.root(), nestedRoot.root());
            assertParentEntry(nestedRoot, root);
            assertEquals(
                List.of("nested", "child.txt"),
                contentEntries(nestedRoot).stream().map(FileTreeModel.Entry::name).toList()
            );
            assertFalse(entry(nestedRoot, nestedDirectory, FileTreeModel.Kind.DIRECTORY).expanded());
            assertEquals(0, entry(nestedRoot, childFile, FileTreeModel.Kind.FILE).depth());

            model.setRoot(root);
            assertFalse(entry(awaitLoaded(model), folder, FileTreeModel.Kind.DIRECTORY).expanded());
            assertFalse(containsPath(awaitLoaded(model), childFile));
        }
    }

    @Test
    void loadingReturnsBeforeScanningAndPublishesAnImmutableSnapshotWithMetadata() throws Exception {
        Path target = Files.writeString(root.resolve("target.txt"), "target payload");
        Files.setLastModifiedTime(target, FileTime.from(Instant.parse("2024-03-02T10:15:30Z")));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread owner = Thread.currentThread();
        AtomicReference<Thread> scannerThread = new AtomicReference<>();
        FileTreeModel.DirectoryScanner scanner = (directory, hidden) -> {
            scannerThread.set(Thread.currentThread());
            if (Thread.currentThread() == owner) {
                throw new AssertionError("Directory scans must not run on the owning UI thread");
            }
            entered.countDown();
            awaitIgnoringInterrupts(release);
            return FileTreeModel.scanDirectory(directory, hidden);
        };

        try (FileTreeModel model = new FileTreeModel(root, true, true, scanner)) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertNotSame(owner, scannerThread.get());
            FileTreeModel.Snapshot pending = model.snapshot();
            assertTrue(pending.loading());
            assertParentEntry(pending, root.getParent());
            assertTrue(contentEntries(pending).isEmpty());
            assertThrows(UnsupportedOperationException.class, pending.entries()::clear);
            release.countDown();

            FileTreeModel.Snapshot enriched = awaitLoaded(model);
            FileTreeModel.Entry enrichedEntry = entry(enriched, target, FileTreeModel.Kind.FILE);
            assertNotSame(pending, enriched);
            assertTrue(enriched.revision() > pending.revision());
            assertFalse(enriched.loading());
            assertEquals(Files.size(target) + "B", enrichedEntry.sizeValue());
            assertTrue(enrichedEntry.dateValue().matches("\\d{2}\\.\\d{2}\\.\\d{2} \\d{2}:\\d{2}"));
            assertTrue(pending.loading(), "an older snapshot must stay immutable");
            assertTrue(contentEntries(pending).isEmpty());
            assertTrue(model.consumeMetadataChanged());
            assertFalse(model.consumeMetadataChanged());
        } finally {
            release.countDown();
        }
    }

    @Test
    void supersededRootCannotPublishEvenWhenItsScannerIgnoresCancellation() throws Exception {
        Path nextRoot = Files.createDirectory(root.resolve("next"));
        Path nextFile = Files.createFile(nextRoot.resolve("next.txt"));
        Files.createFile(root.resolve("old.txt"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> oldWorker = new AtomicReference<>();
        FileTreeModel.DirectoryScanner scanner = (directory, hidden) -> {
            if (directory.equals(root)) {
                oldWorker.set(Thread.currentThread());
                entered.countDown();
                awaitIgnoringInterrupts(release);
            }
            return FileTreeModel.scanDirectory(directory, hidden);
        };
        try (FileTreeModel model = new FileTreeModel(root, false, false, scanner)) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            model.setRoot(nextRoot);
            FileTreeModel.Snapshot latest = awaitLoaded(model);
            assertEquals(nextRoot, latest.root());
            assertParentEntry(latest, root);
            assertEquals(List.of(nextFile), contentEntries(latest).stream().map(FileTreeModel.Entry::path).toList());
            drainMetadataSignal(model);
            release.countDown();
            oldWorker.get().join(5_000);
            assertFalse(oldWorker.get().isAlive());
            assertSame(latest, model.snapshot());
            assertFalse(model.consumeMetadataChanged());
        } finally {
            release.countDown();
        }
    }

    @Test
    void changingRootImmediatelyRemovesOldEntriesWhileTheNewScanIsBlocked() throws Exception {
        Path oldFile = Files.createFile(root.resolve("old.txt"));
        Path nextRoot = Files.createDirectory(root.resolve("next"));
        Path nextFile = Files.createFile(nextRoot.resolve("next.txt"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (FileTreeModel model = new FileTreeModel(root, false, false, (directory, hidden) -> {
            if (directory.equals(nextRoot)) {
                entered.countDown();
                awaitIgnoringInterrupts(release);
            }
            return FileTreeModel.scanDirectory(directory, hidden);
        })) {
            assertTrue(containsPath(awaitLoaded(model), oldFile));
            model.setRoot(nextRoot);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            FileTreeModel.Snapshot loading = model.snapshot();
            assertTrue(loading.loading());
            assertEquals(nextRoot, loading.root());
            assertParentEntry(loading, root);
            assertTrue(contentEntries(loading).isEmpty());
            assertFalse(containsPath(loading, oldFile));
            release.countDown();
            assertTrue(containsPath(awaitLoaded(model), nextFile));
        } finally {
            release.countDown();
        }
    }

    @Test
    void pendingExpansionCanBeCollapsedBeforeItsDirectoryScanCompletes() throws Exception {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Path child = Files.createFile(folder.resolve("child.txt"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> oldWorker = new AtomicReference<>();
        FileTreeModel.DirectoryScanner scanner = (directory, hidden) -> {
            if (directory.equals(folder)) {
                oldWorker.set(Thread.currentThread());
                entered.countDown();
                awaitIgnoringInterrupts(release);
            }
            return FileTreeModel.scanDirectory(directory, hidden);
        };
        try (FileTreeModel model = new FileTreeModel(root, false, false, scanner)) {
            FileTreeModel.EntryId id = entry(awaitLoaded(model), folder, FileTreeModel.Kind.DIRECTORY).id();
            assertTrue(model.setDirectoryExpanded(id, true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(model.snapshot().loading());
            assertTrue(entry(model.snapshot(), folder, FileTreeModel.Kind.DIRECTORY).expanded());
            assertTrue(model.setDirectoryExpanded(id, false));
            FileTreeModel.Snapshot collapsed = awaitLoaded(model);
            assertFalse(entry(collapsed, folder, FileTreeModel.Kind.DIRECTORY).expanded());
            assertFalse(containsPath(collapsed, child));
            release.countDown();
            oldWorker.get().join(5_000);
            assertFalse(oldWorker.get().isAlive());
            assertSame(collapsed, model.snapshot());
        } finally {
            release.countDown();
        }
    }

    @Test
    void closeCancelsAnInFlightScanAndPreventsAnyLaterPublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        FileTreeModel model = new FileTreeModel(root, true, true, (directory, hidden) -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            awaitIgnoringInterrupts(release);
            return FileTreeModel.scanDirectory(directory, hidden);
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            FileTreeModel.Snapshot loading = model.snapshot();
            drainMetadataSignal(model);
            model.close();
            model.close();
            release.countDown();
            worker.get().join(5_000);
            assertFalse(worker.get().isAlive());
            assertSame(loading, model.snapshot());
            assertFalse(model.consumeMetadataChanged());
        } finally {
            release.countDown();
            model.close();
        }
    }

    @Test
    void typeSortingAndMetadataUseTheAttributesCapturedByTheScan() throws IOException {
        Path directory = Files.createDirectory(root.resolve("z-directory"));
        Path file = Files.writeString(root.resolve("a-file.txt"), "12345");
        try (FileTreeModel model = new FileTreeModel(root, true, true, (path, hidden) -> {
            List<FileTreeModel.ScannedEntry> scanned = FileTreeModel.scanDirectory(path, hidden);
            Files.delete(directory);
            Files.delete(file);
            return scanned;
        })) {
            FileTreeModel.Snapshot loaded = awaitLoaded(model);
            assertEquals(List.of("z-directory", "a-file.txt"), contentEntries(loaded).stream()
                .map(FileTreeModel.Entry::name).toList());
            assertEquals("5B", entry(loaded, file, FileTreeModel.Kind.FILE).sizeValue());
            assertTrue(entry(loaded, file, FileTreeModel.Kind.FILE).dateValue()
                .matches("\\d{2}\\.\\d{2}\\.\\d{2} \\d{2}:\\d{2}"));
            assertEquals("? items", entry(loaded, directory, FileTreeModel.Kind.DIRECTORY).sizeValue());
        }
    }

    @Test
    void closeIsIdempotentAndRejectsEveryMutation() throws IOException {
        Path directory = Files.createDirectory(root.resolve("directory"));
        FileTreeModel model = new FileTreeModel(root, false, false);
        FileTreeModel.EntryId directoryId = entry(
            awaitLoaded(model),
            directory,
            FileTreeModel.Kind.DIRECTORY
        ).id();
        FileTreeModel.Snapshot published = awaitLoaded(model);

        model.close();
        model.close();

        assertEquals(published.root(), awaitLoaded(model).root());
        assertEquals(published.revision(), awaitLoaded(model).revision());
        assertEquals(published.entries(), awaitLoaded(model).entries());
        assertThrows(IllegalStateException.class, () -> model.setRoot(root));
        assertThrows(IllegalStateException.class, () -> model.setShowHiddenFiles(true));
        assertThrows(IllegalStateException.class, () -> model.setShowSizeColumn(true));
        assertThrows(IllegalStateException.class, () -> model.setShowDateColumn(true));
        assertThrows(IllegalStateException.class, () -> model.toggleDirectory(directoryId));
    }

    @Test
    void nonDirectoryRootUsesAnExplicitErrorIdentityWithoutPendingMetadata() throws IOException {
        Path regularFile = Files.createFile(root.resolve("not-a-directory.txt"));

        try (FileTreeModel model = new FileTreeModel(regularFile, true, true)) {
            FileTreeModel.Snapshot snapshot = awaitLoaded(model);
            FileTreeModel.Entry error = entry(snapshot, regularFile, FileTreeModel.Kind.ERROR);

            assertEquals(FileTreeModel.Kind.ERROR, error.id().kind());
            assertFalse(error.directory());
            assertTrue(error.name().startsWith("<cannot read directory:"));
            assertEquals("", error.sizeValue());
            assertEquals("", error.dateValue());
            assertTrue(model.consumeMetadataChanged());
            assertFalse(model.consumeMetadataChanged());
        }
    }

    @Test
    void fifoEntriesAreUnavailableWhileRegularFilesAndSymlinksRemainOpenable() throws Exception {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        Path fifo = root.resolve("input.fifo");
        createFifo(fifo);
        Path fifoLink = Files.createSymbolicLink(root.resolve("alias.fifo"), fifo.getFileName());
        Path regular = Files.writeString(root.resolve("ordinary.txt"), "text");
        Path regularLink = Files.createSymbolicLink(root.resolve("alias.txt"), regular.getFileName());

        try (FileTreeModel model = new FileTreeModel(root, true, true)) {
            for (Path special : List.of(fifo, fifoLink)) {
                FileTreeModel.Entry unavailable = entry(awaitLoaded(model), special, FileTreeModel.Kind.ERROR);
                assertEquals(special.getFileName() + " <not a regular file>", unavailable.name());
                assertEquals("", unavailable.sizeValue());
                assertEquals("", unavailable.dateValue());
                assertFalse(model.toggleDirectory(unavailable.id()));
            }
            assertEntryIdentity(awaitLoaded(model), regular, FileTreeModel.Kind.FILE);
            assertEntryIdentity(awaitLoaded(model), regularLink, FileTreeModel.Kind.FILE);
        }
    }

    private static void createFifo(Path fifo) throws Exception {
        Process process;
        try {
            process = new ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "mkfifo is unavailable: " + unavailable.getMessage());
            return;
        }
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "mkfifo did not terminate");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assumeTrue(process.exitValue() == 0, "FIFO creation is unsupported: " + output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // Simulate a filesystem provider which cannot abort a blocked directory read.
            }
        }
    }

    private static void drainMetadataSignal(FileTreeModel model) {
        while (model.consumeMetadataChanged()) {
            Thread.onSpinWait();
        }
    }

    private static void assertParentEntry(FileTreeModel.Snapshot snapshot, Path expectedParent) {
        FileTreeModel.Entry parent = snapshot.entries().get(0);
        assertEquals(FileTreeModel.Kind.PARENT, parent.kind());
        assertEquals("..", parent.name());
        assertEquals(expectedParent.toAbsolutePath().normalize(), parent.id().path());
        assertEquals(0, parent.depth());
        assertEquals("", parent.sizeValue());
        assertEquals("", parent.dateValue());
    }

    private static void assertEntryIdentity(
        FileTreeModel.Snapshot snapshot,
        Path path,
        FileTreeModel.Kind kind
    ) {
        FileTreeModel.Entry found = entry(snapshot, path, kind);
        assertEquals(path.toAbsolutePath().normalize(), found.id().path());
        assertEquals(kind, found.id().kind());
        assertEquals(path, found.path());
        assertEquals(0, found.depth());
        assertFalse(found.expanded());
    }

    private static List<FileTreeModel.Entry> contentEntries(FileTreeModel.Snapshot snapshot) {
        return snapshot.entries().stream()
            .filter(entry -> entry.kind() != FileTreeModel.Kind.PARENT)
            .toList();
    }

    private static boolean containsPath(FileTreeModel.Snapshot snapshot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return snapshot.entries().stream().anyMatch(entry -> entry.id().path().equals(normalized));
    }

    private static FileTreeModel.Entry entry(
        FileTreeModel.Snapshot snapshot,
        Path path,
        FileTreeModel.Kind kind
    ) {
        FileTreeModel.EntryId id = new FileTreeModel.EntryId(path, kind);
        return snapshot.entries().stream()
            .filter(candidate -> candidate.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing " + kind + " entry for " + path));
    }
}
