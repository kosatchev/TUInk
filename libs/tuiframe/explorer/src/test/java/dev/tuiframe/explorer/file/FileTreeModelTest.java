package dev.tuiframe.explorer.file;

import static dev.tuiframe.explorer.file.FileTreeTestSupport.awaitLoaded;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreeModelTest {
    @TempDir
    Path root;

    @Test
    void fixedRootRefreshKeepsExpandedDirectoriesAndLoadsFilesystemChanges() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Path oldFile = Files.createFile(folder.resolve("old.txt"));
        try (FileTreeModel model = new FileTreeModel(root, false, false, false)) {
            FileTreeModel.Snapshot initial = awaitLoaded(model);
            assertEquals(List.of(folder), initial.entries().stream().map(FileTreeModel.Entry::path).toList());
            assertTrue(model.setDirectoryExpanded(entry(initial, folder, FileTreeModel.Kind.DIRECTORY).id(), true));
            assertTrue(containsPath(awaitLoaded(model), oldFile));

            Files.delete(oldFile);
            Path newFile = Files.createFile(folder.resolve("new.txt"));
            model.refresh();
            FileTreeModel.Snapshot refreshed = awaitLoaded(model);
            assertEquals(root, refreshed.root());
            assertTrue(entry(refreshed, folder, FileTreeModel.Kind.DIRECTORY).expanded());
            assertTrue(containsPath(refreshed, newFile));
            assertFalse(containsPath(refreshed, oldFile));
            assertTrue(refreshed.entries().stream().noneMatch(item -> item.kind() == FileTreeModel.Kind.PARENT));
            assertTrue(refreshed.entries().stream().allMatch(item -> item.sizeValue().isEmpty() && item.dateValue().isEmpty()));
        }
    }

    @Test
    void fixedRootOmitsParentFromLoadingAndFailureSnapshots() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (FileTreeModel model = new FileTreeModel(root, false, false, (directory, hidden, consumer) -> {
            entered.countDown();
            awaitIgnoringInterrupts(release);
            throw new java.nio.file.AccessDeniedException(directory.toString());
        }, FileTreeLoadBudget.DEFAULT_LIMITS, Thread::new, false)) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(model.snapshot().loading());
            assertTrue(model.snapshot().entries().isEmpty());
            release.countDown();
            FileTreeModel.Snapshot failed = awaitLoaded(model);
            assertEquals(FileTreeModel.LoadStatus.FAILED, failed.status());
            assertEquals(1, failed.entries().size());
            assertEquals(FileTreeModel.Kind.ERROR, failed.entries().getFirst().kind());
        } finally {
            release.countDown();
        }
    }

    @Test
    void failingToCreateTheWorkerPublishesAMemoryFailureAndTheNextRequestCanLoad() throws IOException {
        Path nextRoot = Files.createDirectory(root.resolve("next"));
        Path nextFile = Files.createFile(nextRoot.resolve("file"));
        AtomicInteger workers = new AtomicInteger();
        AtomicInteger scans = new AtomicInteger();
        try (FileTreeModel model = new FileTreeModel(root, false, false, (directory, hidden, consumer) -> {
            scans.incrementAndGet();
            FileTreeModel.scanDirectory(directory, hidden, consumer);
        }, FileTreeLoadBudget.DEFAULT_LIMITS, operation -> {
            if (workers.getAndIncrement() == 0) {
                throw new OutOfMemoryError("injected thread creation failure");
            }
            return new Thread(operation);
        })) {
            FileTreeModel.Snapshot failed = awaitLoaded(model);
            assertEquals(FileTreeModel.LoadStatus.FAILED, failed.status());
            assertEquals(FileTreeModel.FailureKind.MEMORY, failed.failures().getFirst().kind());
            assertEquals(0, scans.get());
            assertParentEntry(failed, root.getParent());

            model.setRoot(nextRoot);
            FileTreeModel.Snapshot loaded = awaitLoaded(model);
            assertEquals(FileTreeModel.LoadStatus.READY, loaded.status());
            assertTrue(containsPath(loaded, nextFile));
            assertEquals(2, workers.get());
            assertEquals(1, scans.get());
            assertTrue(loaded.revision() > failed.revision());
        }
    }

    @Test
    void failingToCreateAReplacementWorkerCancelsTheOldRequestAndRejectsItsLatePublication() throws Exception {
        Path nextRoot = Files.createDirectory(root.resolve("next"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger workers = new AtomicInteger();
        AtomicReference<Thread> oldWorker = new AtomicReference<>();
        FileTreeModel.DirectoryScanner scanner = (directory, hidden, consumer) -> {
            oldWorker.set(Thread.currentThread());
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                awaitIgnoringInterrupts(release);
            }
            FileTreeModel.scanDirectory(directory, hidden, consumer);
        };
        try (FileTreeModel model = new FileTreeModel(root, false, false, scanner,
            FileTreeLoadBudget.DEFAULT_LIMITS, operation -> {
                if (workers.getAndIncrement() > 0) {
                    throw new OutOfMemoryError("injected replacement worker failure");
                }
                return new Thread(operation);
            })) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            model.setRoot(nextRoot);
            FileTreeModel.Snapshot failed = awaitLoaded(model);
            assertEquals(nextRoot, failed.root());
            assertEquals(FileTreeModel.FailureKind.MEMORY, failed.failures().getFirst().kind());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            drainMetadataSignal(model);
            release.countDown();
            oldWorker.get().join(5_000L);
            assertFalse(oldWorker.get().isAlive());
            assertSame(failed, model.snapshot());
            assertFalse(model.consumeMetadataChanged());
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationOfTheCurrentScanPublishesATerminalSnapshot() {
        try (FileTreeModel model = new FileTreeModel(root, true, true,
            (directory, hidden, consumer) -> { throw new CancellationException("provider cancelled"); })) {
            FileTreeModel.Snapshot cancelled = awaitLoaded(model);
            assertEquals(FileTreeModel.LoadStatus.CANCELLED, cancelled.status());
            assertFalse(cancelled.loading());
            assertEquals(FileTreeModel.FailureKind.CANCELLED, cancelled.failures().getFirst().kind());
        }
    }

    @Test
    void entryBudgetStopsTheStreamingScanBeforeAccumulatingAnOversizedDirectory() {
        AtomicInteger visited = new AtomicInteger();
        FileTreeModel.DirectoryScanner scanner = (directory, hidden, consumer) -> {
            for (int index = 0; index < 10_000; index++) {
                visited.incrementAndGet();
                consumer.accept(directory.resolve("file-" + index), null);
            }
        };
        try (FileTreeModel model = new FileTreeModel(root, true, true, scanner,
            new FileTreeLoadBudget.Limits(3, Long.MAX_VALUE, 0))) {
            FileTreeModel.Snapshot loaded = awaitLoaded(model);
            assertEquals(4, visited.get());
            assertEquals(FileTreeModel.LoadStatus.LIMITED, loaded.status());
            assertFalse(loaded.loading());
            assertEquals(FileTreeModel.FailureKind.LIMIT, loaded.failures().getFirst().kind());
            assertTrue(loaded.failures().getFirst().reason().contains("entry limit"));
            assertEquals(1, contentEntries(loaded).size());
            assertEquals(FileTreeModel.Kind.ERROR, contentEntries(loaded).getFirst().kind());
        }
    }

    @Test
    void estimatedByteBudgetRejectsLongPathsBeforeKeepingTheirScanRecords() {
        AtomicInteger visited = new AtomicInteger();
        Path shortPath = root.resolve("short");
        long shortEntryBytes = 1024L + 8L * shortPath.toString().length();
        FileTreeModel.DirectoryScanner scanner = (directory, hidden, consumer) -> {
            visited.incrementAndGet();
            consumer.accept(shortPath, null);
            visited.incrementAndGet();
            consumer.accept(directory.resolve("long-name".repeat(100)), null);
            visited.incrementAndGet();
        };
        try (FileTreeModel model = new FileTreeModel(root, false, false, scanner,
            new FileTreeLoadBudget.Limits(100, shortEntryBytes + 1024L, 0))) {
            FileTreeModel.Snapshot loaded = awaitLoaded(model);
            assertEquals(2, visited.get());
            assertEquals(FileTreeModel.LoadStatus.LIMITED, loaded.status());
            assertTrue(loaded.failures().getFirst().reason().contains("memory budget"));
        }
    }

    @Test
    void allExpandedDirectoriesShareOneBudgetAndCollapsingReleasesTheAllowance() throws IOException {
        Path first = Files.createDirectory(root.resolve("a"));
        Path second = Files.createDirectory(root.resolve("b"));
        Path firstChild = Files.createFile(first.resolve("one"));
        Files.createFile(first.resolve("two"));
        Path secondChild = Files.createFile(second.resolve("one"));
        Files.createFile(second.resolve("two"));
        try (FileTreeModel model = new FileTreeModel(root, true, true, FileTreeModel::scanDirectory,
            new FileTreeLoadBudget.Limits(5, Long.MAX_VALUE, 100))) {
            FileTreeModel.EntryId firstId = entry(awaitLoaded(model), first, FileTreeModel.Kind.DIRECTORY).id();
            FileTreeModel.EntryId secondId = entry(model.snapshot(), second, FileTreeModel.Kind.DIRECTORY).id();
            assertTrue(model.setDirectoryExpanded(firstId, true));
            assertEquals(FileTreeModel.LoadStatus.READY, awaitLoaded(model).status());
            assertTrue(model.setDirectoryExpanded(secondId, true));
            FileTreeModel.Snapshot limited = awaitLoaded(model);
            assertEquals(FileTreeModel.LoadStatus.LIMITED, limited.status());
            assertEquals(second, limited.failures().getFirst().directory());
            assertTrue(containsPath(limited, firstChild));
            assertFalse(containsPath(limited, secondChild), "a partially scanned directory is not published");

            assertTrue(model.setDirectoryExpanded(firstId, false));
            FileTreeModel.Snapshot recovered = awaitLoaded(model);
            assertEquals(FileTreeModel.LoadStatus.READY, recovered.status());
            assertTrue(recovered.failures().isEmpty());
            assertFalse(containsPath(recovered, firstChild));
            assertTrue(containsPath(recovered, secondChild));
            assertTrue(recovered.revision() > limited.revision());
        }
    }

    @Test
    void childCountMetadataHasOneSharedInspectionBudgetAndShowsTruncation() throws IOException {
        Path first = Files.createDirectory(root.resolve("a"));
        Path second = Files.createDirectory(root.resolve("b"));
        Path empty = Files.createDirectory(root.resolve("c"));
        for (Path directory : List.of(first, second)) {
            Files.createFile(directory.resolve("one"));
            Files.createFile(directory.resolve("two"));
            Files.createFile(directory.resolve("three"));
        }
        try (FileTreeModel model = new FileTreeModel(root, true, false, FileTreeModel::scanDirectory,
            new FileTreeLoadBudget.Limits(10, Long.MAX_VALUE, 2))) {
            FileTreeModel.Snapshot loaded = awaitLoaded(model);
            assertEquals("2+ items", entry(loaded, first, FileTreeModel.Kind.DIRECTORY).sizeValue());
            assertEquals("? items", entry(loaded, second, FileTreeModel.Kind.DIRECTORY).sizeValue());
            assertEquals("0 items", entry(loaded, empty, FileTreeModel.Kind.DIRECTORY).sizeValue());
            assertEquals(FileTreeModel.LoadStatus.READY, loaded.status());
        }
    }

    @Test
    void outOfMemoryDuringScanningPublishesATerminalFailureAndTheNextRootCanLoad() throws IOException {
        Path nextRoot = Files.createDirectory(root.resolve("next"));
        Path nextFile = Files.createFile(nextRoot.resolve("file"));
        FileTreeModel.DirectoryScanner scanner = (directory, hidden, consumer) -> {
            if (directory.equals(root)) {
                throw new OutOfMemoryError("injected scan allocation failure");
            }
            FileTreeModel.scanDirectory(directory, hidden, consumer);
        };
        try (FileTreeModel model = new FileTreeModel(root, true, true, scanner)) {
            FileTreeModel.Snapshot failed = awaitLoaded(model);
            assertEquals(FileTreeModel.LoadStatus.FAILED, failed.status());
            assertEquals(FileTreeModel.FailureKind.MEMORY, failed.failures().getFirst().kind());
            assertParentEntry(failed, root.getParent());
            model.setRoot(nextRoot);
            FileTreeModel.Snapshot recovered = awaitLoaded(model);
            assertEquals(FileTreeModel.LoadStatus.READY, recovered.status());
            assertTrue(containsPath(recovered, nextFile));
            assertTrue(recovered.revision() > failed.revision());
        }
    }

    @Test
    void obsoleteWorkerCannotReplaceTheNewRootWithItsEmergencyFailure() throws Exception {
        Path nextRoot = Files.createDirectory(root.resolve("next"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> oldWorker = new AtomicReference<>();
        FileTreeModel.DirectoryScanner scanner = (directory, hidden, consumer) -> {
            if (directory.equals(root)) {
                oldWorker.set(Thread.currentThread());
                entered.countDown();
                awaitIgnoringInterrupts(release);
                throw new OutOfMemoryError("injected obsolete allocation failure");
            }
            FileTreeModel.scanDirectory(directory, hidden, consumer);
        };
        try (FileTreeModel model = new FileTreeModel(root, false, false, scanner)) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            model.setRoot(nextRoot);
            FileTreeModel.Snapshot latest = awaitLoaded(model);
            release.countDown();
            oldWorker.get().join(5_000);
            assertFalse(oldWorker.get().isAlive());
            assertSame(latest, model.snapshot());
        } finally {
            release.countDown();
        }
    }

    @Test
    void unexpectedAttributeFailureEndsLoadingWithoutLeavingPendingMetadata() throws IOException {
        Path file = Files.createFile(root.resolve("file"));
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        BasicFileAttributes broken = new BasicFileAttributes() {
            @Override public FileTime lastModifiedTime() { return attributes.lastModifiedTime(); }
            @Override public FileTime lastAccessTime() { return attributes.lastAccessTime(); }
            @Override public FileTime creationTime() { return attributes.creationTime(); }
            @Override public boolean isRegularFile() { return attributes.isRegularFile(); }
            @Override public boolean isDirectory() { return attributes.isDirectory(); }
            @Override public boolean isSymbolicLink() { return attributes.isSymbolicLink(); }
            @Override public boolean isOther() { return attributes.isOther(); }
            @Override public long size() { throw new IllegalStateException("injected metadata failure"); }
            @Override public Object fileKey() { return attributes.fileKey(); }
        };
        try (FileTreeModel model = new FileTreeModel(root, true, true,
            (directory, hidden, consumer) -> consumer.accept(file, broken))) {
            FileTreeModel.Snapshot failed = awaitLoaded(model);
            assertEquals(FileTreeModel.LoadStatus.FAILED, failed.status());
            assertEquals(FileTreeModel.FailureKind.READ, failed.failures().getFirst().kind());
            assertTrue(failed.failures().getFirst().reason().contains("listing failed"));
        }
    }

    @Test
    void deniedDirectoryExposesATypedReasonWithoutRelyingOnTheExceptionMessage() throws Exception {
        try (FileTreeModel model = new FileTreeModel(root, false, false,
            (directory, hidden, consumer) -> { throw new java.nio.file.AccessDeniedException(directory.toString()); })) {
            awaitLoaded(model);
            assertEquals(List.of(new FileTreeModel.DirectoryFailure(root, "permission denied")),
                model.snapshot().failures());
            assertTrue(model.snapshot().entries().stream()
                .anyMatch(entry -> entry.kind() == FileTreeModel.Kind.ERROR && entry.name().contains("permission denied")));
        }
    }

    @Test
    void directoryFailureReasonsKeepFallbacksAndTheirExistingLengthBound() throws Exception {
        String longReason = "r".repeat(300);
        List<Exception> failures = List.of(new java.nio.file.NoSuchFileException("permission denied.txt"),
            new java.nio.file.FileSystemException("no space.txt", null, "device unavailable"),
            new IOException(), new IllegalStateException(),
            new java.nio.file.FileSystemException("selected.txt", null, longReason));
        List<String> reasons = List.of("file no longer exists", "device unavailable", "IOException",
            "IllegalStateException", "r".repeat(240) + "…");
        for (int index = 0; index < failures.size(); index++) {
            Exception failure = failures.get(index);
            String reason = reasons.get(index);
            try (FileTreeModel model = new FileTreeModel(root, false, false, (directory, hidden, consumer) -> {
                if (failure instanceof IOException io) throw io;
                throw (RuntimeException) failure;
            })) {
                awaitLoaded(model);
                assertEquals(List.of(new FileTreeModel.DirectoryFailure(root, reason)),
                    model.snapshot().failures());
                assertTrue(model.snapshot().entries().stream()
                    .anyMatch(entry -> entry.kind() == FileTreeModel.Kind.ERROR && entry.name().contains(reason)));
            }
        }
    }

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
        FileTreeModel.DirectoryScanner scanner = (directory, hidden, consumer) -> {
            scannerThread.set(Thread.currentThread());
            if (Thread.currentThread() == owner) {
                throw new AssertionError("Directory scans must not run on the owning UI thread");
            }
            entered.countDown();
            awaitIgnoringInterrupts(release);
            FileTreeModel.scanDirectory(directory, hidden, consumer);
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
        FileTreeModel.DirectoryScanner scanner = (directory, hidden, consumer) -> {
            if (directory.equals(root)) {
                oldWorker.set(Thread.currentThread());
                entered.countDown();
                awaitIgnoringInterrupts(release);
            }
            FileTreeModel.scanDirectory(directory, hidden, consumer);
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
        try (FileTreeModel model = new FileTreeModel(root, false, false, (directory, hidden, consumer) -> {
            if (directory.equals(nextRoot)) {
                entered.countDown();
                awaitIgnoringInterrupts(release);
            }
            FileTreeModel.scanDirectory(directory, hidden, consumer);
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
        FileTreeModel.DirectoryScanner scanner = (directory, hidden, consumer) -> {
            if (directory.equals(folder)) {
                oldWorker.set(Thread.currentThread());
                entered.countDown();
                awaitIgnoringInterrupts(release);
            }
            FileTreeModel.scanDirectory(directory, hidden, consumer);
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
        FileTreeModel model = new FileTreeModel(root, true, true, (directory, hidden, consumer) -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            awaitIgnoringInterrupts(release);
            FileTreeModel.scanDirectory(directory, hidden, consumer);
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
        try (FileTreeModel model = new FileTreeModel(root, true, true, (path, hidden, consumer) -> {
            FileTreeModel.scanDirectory(path, hidden, consumer);
            Files.delete(directory);
            Files.delete(file);
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
