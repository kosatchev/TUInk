package dev.tuiframe.explorer.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExplorerFileServiceTest {
    @TempDir Path directory;
    private final ExplorerFileService files = new ExplorerFileService();

    @Test
    void createsAnEmptyFileAndAFolderInTheSelectedParent() throws Exception {
        Path parent = Files.createDirectory(directory.resolve("parent"));

        var file = files.create(parent, "notes.txt", false);
        var folder = files.create(parent, "nested", true);

        assertTrue(file.succeeded());
        assertTrue(file.destinationPublished());
        assertEquals(ExplorerFileService.Action.CREATE_FILE, file.action());
        assertEquals(parent.resolve("notes.txt"), file.destination());
        assertEquals("", Files.readString(file.destination()));
        assertTrue(folder.succeeded());
        assertTrue(Files.isDirectory(folder.destination()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "../outside.txt", "nested/file.txt", "nested\\file.txt", "/absolute.txt", "bad\u0000name"})
    void aCreationNameCannotTraverseOrReplaceTheParent(String name) throws Exception {
        var result = files.create(directory, name, false);

        assertFalse(result.succeeded());
        assertFalse(result.destinationPublished());
        assertNotNull(result.failure());
        try (var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
    }

    @Test
    void anExistingCreationTargetIsNeverTruncatedOrReplaced() throws Exception {
        Path existing = Files.writeString(directory.resolve("existing.txt"), "retain existing bytes");

        var result = files.create(directory, existing.getFileName().toString(), false);

        assertFalse(result.succeeded());
        assertFalse(result.destinationPublished());
        assertEquals("retain existing bytes", Files.readString(existing));
    }

    @Test
    void movesAFileWithItsExactBytesAndName() throws Exception {
        byte[] bytes = new byte[] {0, 1, (byte) 0xFF, 13, 10};
        Path source = Files.write(directory.resolve("source.bin"), bytes);
        Path parent = Files.createDirectory(directory.resolve("destination"));

        var result = files.move(files.inspect(source), parent);

        assertTrue(result.succeeded());
        assertTrue(result.destinationPublished());
        assertEquals(source, result.source());
        assertEquals(parent.resolve("source.bin"), result.destination());
        assertArrayEquals(bytes, Files.readAllBytes(result.destination()));
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void aMoveCollisionRetainsBothOriginalFiles() throws Exception {
        Path source = Files.writeString(directory.resolve("same.txt"), "source");
        Path parent = Files.createDirectory(directory.resolve("destination"));
        Path existing = Files.writeString(parent.resolve("same.txt"), "destination");

        var result = files.move(files.inspect(source), parent);

        assertFalse(result.succeeded());
        assertFalse(result.destinationPublished());
        assertEquals("source", Files.readString(source));
        assertEquals("destination", Files.readString(existing));
    }

    @Test
    void movingAFolderIntoItsOwnDescendantIsRejectedWithoutChangingEitherTree() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path child = Files.createDirectory(source.resolve("child"));
        Path content = Files.writeString(source.resolve("content.txt"), "original");

        var result = files.move(files.inspect(source), child);

        assertFalse(result.succeeded());
        assertFalse(result.destinationPublished());
        assertEquals("original", Files.readString(content));
        assertTrue(Files.isDirectory(child));
        assertFalse(Files.exists(child.resolve("source")));
    }

    @Test
    void aReplacedSelectionCannotBeMovedOrDeletedByAnOldConfirmation() throws Exception {
        Path source = Files.writeString(directory.resolve("selected.txt"), "selected version");
        var inspected = files.inspect(source);
        Path retained = directory.resolve("original-renamed.txt");
        Files.move(source, retained);
        Files.writeString(source, "replacement selected after confirmation");
        Path parent = Files.createDirectory(directory.resolve("destination"));

        var move = files.move(inspected, parent);
        var delete = files.delete(inspected);

        assertFalse(move.succeeded());
        assertFalse(delete.succeeded());
        assertEquals("replacement selected after confirmation", Files.readString(source));
        assertEquals("selected version", Files.readString(retained));
        assertFalse(Files.exists(parent.resolve("selected.txt")));
    }

    @Test
    void movingAFolderPreservesItsChildrenAndLeavesSiblingFilesAlone() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("child.txt"), "child");
        Path nested = Files.createDirectory(source.resolve("nested"));
        Files.writeString(nested.resolve("leaf.txt"), "leaf");
        Path sibling = Files.writeString(directory.resolve("sibling.txt"), "sibling");
        Path parent = Files.createDirectory(directory.resolve("destination"));

        var result = files.move(files.inspect(source), parent);

        assertTrue(result.succeeded());
        assertEquals("child", Files.readString(result.destination().resolve("child.txt")));
        assertEquals("leaf", Files.readString(result.destination().resolve("nested/leaf.txt")));
        assertEquals("sibling", Files.readString(sibling));
        assertFalse(Files.exists(source));
    }

    @Test
    void deletingAFolderRemovesItsContentsButNeverFollowsAnExternalDirectoryLink() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path external = Files.createDirectory(directory.resolve("external"));
        Path retained = Files.writeString(external.resolve("keep.txt"), "external");
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("inside.txt"), "inside");
        Files.createSymbolicLink(source.resolve("external-link"), external);

        var result = files.delete(files.inspect(source));

        assertTrue(result.succeeded());
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
        assertEquals("external", Files.readString(retained));
    }

    @Test
    void movingASymbolicLinkMovesOnlyTheLinkObject() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path target = Files.writeString(directory.resolve("target.txt"), "target");
        Path source = Files.createSymbolicLink(directory.resolve("link.txt"), target);
        var inspected = files.inspect(source);
        Path parent = Files.createDirectory(directory.resolve("destination"));

        var result = files.move(inspected, parent);

        assertTrue(inspected.link());
        assertFalse(inspected.directory());
        assertTrue(result.succeeded());
        assertTrue(Files.isSymbolicLink(result.destination()));
        assertEquals(target, Files.readSymbolicLink(result.destination()));
        assertEquals("target", Files.readString(target));
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void aDanglingLinkCanBeDeletedWithoutRequiringItsTargetToExist() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path source = Files.createSymbolicLink(directory.resolve("dangling"), directory.resolve("missing"));

        var result = files.delete(files.inspect(source));

        assertTrue(result.succeeded());
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void copyProtocolPreservesDirectoryPermissionsTimesAndFileBytes() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path source = Files.createDirectory(directory.resolve("private-source"));
        Path nested = Files.createDirectory(source.resolve("nested"));
        Path content = Files.writeString(nested.resolve("content.txt"), "copied bytes");
        var rootPermissions = PosixFilePermissions.fromString("rwx------");
        var nestedPermissions = PosixFilePermissions.fromString("rwxr-x---");
        var filePermissions = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(source, rootPermissions);
        Files.setPosixFilePermissions(nested, nestedPermissions);
        Files.setPosixFilePermissions(content, filePermissions);
        FileTime modified = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(nested, modified);
        Files.setLastModifiedTime(source, modified);
        Path destination = directory.resolve("destination");

        var result = files.moveAcrossFileStores(files.inspect(source), destination);

        assertTrue(result.succeeded(), () -> String.valueOf(result.failure()));
        assertTrue(result.destinationPublished());
        assertFalse(Files.exists(source));
        assertEquals(rootPermissions, Files.getPosixFilePermissions(destination));
        assertEquals(nestedPermissions, Files.getPosixFilePermissions(destination.resolve("nested")));
        assertEquals(filePermissions, Files.getPosixFilePermissions(destination.resolve("nested/content.txt")));
        assertEquals(modified, Files.getLastModifiedTime(destination));
        assertEquals(modified, Files.getLastModifiedTime(destination.resolve("nested")));
        assertEquals("copied bytes", Files.readString(destination.resolve("nested/content.txt")));
        assertNoStagingDirectories();
    }

    @Test
    void aSameSizeRewriteWithRestoredTimestampIsRetainedBeforePublication() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path content = Files.writeString(source.resolve("content.txt"), "original");
        FileTime modified = Files.getLastModifiedTime(content);
        var service = new ExplorerFileService(new ExplorerFileService.MoveObserver() {
            @Override public void copied(Path ignored, Path staging) throws IOException {
                Files.writeString(content, "external");
                Files.setLastModifiedTime(content, modified);
            }
        });
        Path destination = directory.resolve("destination");

        var result = service.moveAcrossFileStores(service.inspect(source), destination);

        assertFalse(result.succeeded());
        assertFalse(result.destinationPublished());
        assertEquals("external", Files.readString(content));
        assertFalse(Files.exists(destination));
        assertNoStagingDirectories();
    }

    @Test
    void aSameSizeRewriteAfterPublicationIsRetainedInsteadOfDeletingNewBytes() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path content = Files.writeString(source.resolve("content.txt"), "original");
        FileTime modified = Files.getLastModifiedTime(content);
        var service = new ExplorerFileService(new ExplorerFileService.MoveObserver() {
            @Override public void published(Path ignored, Path destination) throws IOException {
                Files.writeString(content, "external");
                Files.setLastModifiedTime(content, modified);
            }
        });
        Path destination = directory.resolve("destination");

        var result = service.moveAcrossFileStores(service.inspect(source), destination);

        assertFalse(result.succeeded());
        assertTrue(result.destinationPublished());
        assertEquals("external", Files.readString(content));
        assertEquals("original", Files.readString(destination.resolve("content.txt")));
        assertNoStagingDirectories();
    }

    @Test
    void anUnexpectedFailureAfterPublicationRetainsThePublishedOutcome() throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "original");
        Path destination = directory.resolve("destination.txt");
        var service = new ExplorerFileService(new ExplorerFileService.MoveObserver() {
            @Override public void published(Path ignored, Path published) {
                throw new SecurityException("injected after publication");
            }
        });

        var result = service.moveAcrossFileStores(service.inspect(source), destination);

        assertFalse(result.succeeded());
        assertTrue(result.destinationPublished(), "late failures must not hide the destination publication");
        assertEquals("original", Files.readString(source));
        assertEquals("original", Files.readString(destination));
        assertNotNull(result.failure());
        assertNoStagingDirectories();
    }

    @Test
    void aSourceFileCreatedAfterPublicationIsNeverIncludedInDeletion() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("copied.txt"), "original");
        var service = new ExplorerFileService(new ExplorerFileService.MoveObserver() {
            @Override public void published(Path ignored, Path destination) throws IOException {
                Files.writeString(source.resolve("new.txt"), "new external file");
            }
        });
        Path destination = directory.resolve("destination");

        var result = service.moveAcrossFileStores(service.inspect(source), destination);

        assertFalse(result.succeeded());
        assertTrue(result.destinationPublished());
        assertEquals("new external file", Files.readString(source.resolve("new.txt")));
        assertEquals("original", Files.readString(destination.resolve("copied.txt")));
        assertFalse(Files.exists(destination.resolve("new.txt")));
        assertNoStagingDirectories();
    }

    @Test
    void copyProtocolPreservesLinksWithoutCopyingOrDeletingTheirTargets() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path external = Files.createDirectory(directory.resolve("external"));
        Path retained = Files.writeString(external.resolve("retain.txt"), "external bytes");
        Path source = Files.createDirectory(directory.resolve("source"));
        Path link = Files.createSymbolicLink(source.resolve("external-link"), external);
        Path destination = directory.resolve("destination");

        var result = files.moveAcrossFileStores(files.inspect(source), destination);

        assertTrue(result.succeeded(), () -> String.valueOf(result.failure()));
        assertTrue(Files.isSymbolicLink(destination.resolve(link.getFileName())));
        assertEquals(external, Files.readSymbolicLink(destination.resolve(link.getFileName())));
        assertEquals("external bytes", Files.readString(retained));
        assertFalse(Files.exists(source));
        assertNoStagingDirectories();
    }

    private void assertNoStagingDirectories() throws IOException {
        try (var entries = Files.list(directory)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".tuiframe-move-")));
        }
    }
}
