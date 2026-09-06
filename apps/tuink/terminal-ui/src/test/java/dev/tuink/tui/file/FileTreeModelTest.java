package dev.tuink.tui.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreeModelTest {
    private static final String PENDING_METADATA = "...";

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
            FileTreeModel.Snapshot initial = model.snapshot();

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
            FileTreeModel.Snapshot withHidden = model.snapshot();

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
            assertEquals(visibleRevision, model.snapshot().revision(), "an unchanged option must not rebuild");
        }
    }

    @Test
    void expansionPublishesFlatDepthFirstRowsAndSetRootClearsExpansion() throws IOException {
        Path folder = Files.createDirectory(root.resolve("folder"));
        Path nestedDirectory = Files.createDirectory(folder.resolve("nested"));
        Path childFile = Files.createFile(folder.resolve("child.txt"));
        Path rootFile = Files.createFile(root.resolve("root.txt"));

        try (FileTreeModel model = new FileTreeModel(root, false, false)) {
            FileTreeModel.Entry folderEntry = entry(model.snapshot(), folder, FileTreeModel.Kind.DIRECTORY);
            FileTreeModel.Entry rootFileEntry = entry(model.snapshot(), rootFile, FileTreeModel.Kind.FILE);
            long collapsedRevision = model.snapshot().revision();

            assertTrue(model.toggleDirectory(folderEntry.id()));
            FileTreeModel.Snapshot expanded = model.snapshot();
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
            assertEquals(expanded.revision(), model.snapshot().revision());

            model.setRoot(folder.resolve("."));
            FileTreeModel.Snapshot nestedRoot = model.snapshot();
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
            assertFalse(entry(model.snapshot(), folder, FileTreeModel.Kind.DIRECTORY).expanded());
            assertFalse(containsPath(model.snapshot(), childFile));
        }
    }

    @Test
    void metadataPublicationAdvancesRevisionWithoutMutatingCapturedSnapshot() throws IOException {
        for (int index = 0; index < 96; index++) {
            Files.writeString(root.resolve("aa-filler-%03d.txt".formatted(index)), "x");
        }
        Path target = root.resolve("zz-target.txt");
        Files.writeString(target, "target payload");
        Files.setLastModifiedTime(target, FileTime.from(Instant.parse("2024-03-02T10:15:30Z")));

        try (FileTreeModel model = new FileTreeModel(root, true, true)) {
            drainMetadataSignal(model);
            FileTreeModel.Snapshot pending = capturePendingGeneration(model, target);
            FileTreeModel.Entry pendingEntry = entry(pending, target, FileTreeModel.Kind.FILE);
            long pendingRevision = pending.revision();

            assertEquals(PENDING_METADATA, pendingEntry.sizeValue());
            assertEquals(PENDING_METADATA, pendingEntry.dateValue());
            assertThrows(UnsupportedOperationException.class, pending.entries()::clear);

            FileTreeModel.Snapshot enriched = awaitSnapshot(
                model,
                snapshot -> snapshot.revision() > pendingRevision
                    && metadataReady(entry(snapshot, target, FileTreeModel.Kind.FILE))
            );
            FileTreeModel.Entry enrichedEntry = entry(enriched, target, FileTreeModel.Kind.FILE);

            assertNotSame(pending, enriched);
            assertTrue(enriched.revision() > pendingRevision);
            assertEquals(Files.size(target) + "B", enrichedEntry.sizeValue());
            assertTrue(enrichedEntry.dateValue().matches("\\d{2}\\.\\d{2}\\.\\d{2} \\d{2}:\\d{2}"));
            assertEquals(PENDING_METADATA, pendingEntry.sizeValue(), "an older snapshot must stay immutable");
            assertEquals(PENDING_METADATA, pendingEntry.dateValue(), "metadata must publish with a new revision");
            assertTrue(model.consumeMetadataChanged());
            assertFalse(model.consumeMetadataChanged());
        }
    }

    @Test
    void closeIsIdempotentAndRejectsEveryMutation() throws IOException {
        Path directory = Files.createDirectory(root.resolve("directory"));
        FileTreeModel model = new FileTreeModel(root, false, false);
        FileTreeModel.EntryId directoryId = entry(
            model.snapshot(),
            directory,
            FileTreeModel.Kind.DIRECTORY
        ).id();
        FileTreeModel.Snapshot published = model.snapshot();

        model.close();
        model.close();

        assertEquals(published.root(), model.snapshot().root());
        assertEquals(published.revision(), model.snapshot().revision());
        assertEquals(published.entries(), model.snapshot().entries());
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
            FileTreeModel.Snapshot snapshot = model.snapshot();
            FileTreeModel.Entry error = entry(snapshot, regularFile, FileTreeModel.Kind.ERROR);

            assertEquals(FileTreeModel.Kind.ERROR, error.id().kind());
            assertFalse(error.directory());
            assertTrue(error.name().startsWith("<cannot read directory:"));
            assertEquals("", error.sizeValue());
            assertEquals("", error.dateValue());
            assertFalse(model.consumeMetadataChanged());
        }
    }

    private static FileTreeModel.Snapshot capturePendingGeneration(FileTreeModel model, Path target) {
        for (int attempt = 0; attempt < 100; attempt++) {
            model.setShowDateColumn(false);
            model.setShowDateColumn(true);
            FileTreeModel.Snapshot candidate = model.snapshot();
            FileTreeModel.Entry targetEntry = entry(candidate, target, FileTreeModel.Kind.FILE);
            if (PENDING_METADATA.equals(targetEntry.sizeValue())
                && PENDING_METADATA.equals(targetEntry.dateValue())) {
                return candidate;
            }
            Thread.onSpinWait();
        }
        return fail("could not observe the synchronously published pending metadata snapshot");
    }

    private static FileTreeModel.Snapshot awaitSnapshot(
        FileTreeModel model,
        Predicate<FileTreeModel.Snapshot> predicate
    ) {
        return org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> {
                while (!Thread.currentThread().isInterrupted()) {
                    FileTreeModel.Snapshot candidate = model.snapshot();
                    if (predicate.test(candidate)) {
                        return candidate;
                    }
                    Thread.onSpinWait();
                }
                throw new AssertionError("metadata wait was interrupted");
            }
        );
    }

    private static void drainMetadataSignal(FileTreeModel model) {
        while (model.consumeMetadataChanged()) {
            Thread.onSpinWait();
        }
    }

    private static boolean metadataReady(FileTreeModel.Entry entry) {
        return !PENDING_METADATA.equals(entry.sizeValue())
            && !PENDING_METADATA.equals(entry.dateValue());
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
