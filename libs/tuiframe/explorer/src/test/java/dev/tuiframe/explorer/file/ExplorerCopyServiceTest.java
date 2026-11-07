package dev.tuiframe.explorer.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerCopyServiceTest {
    @TempDir Path directory;
    private final ExplorerFileService files = new ExplorerFileService();

    @Test
    void filesAndDirectoryTreesAreCopiedWithoutChangingTheirSources() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path nested = Files.createDirectory(source.resolve("nested"));
        Path original = Files.writeString(nested.resolve("document.txt"), "source bytes");
        FileTime timestamp = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(original, timestamp);
        Files.setLastModifiedTime(nested, timestamp);
        Path destination = Files.createDirectory(directory.resolve("destination"));

        var copied = files.copy(files.inspect(source), destination);

        assertTrue(copied.succeeded(), () -> String.valueOf(copied.failure()));
        assertEquals(ExplorerFileService.Action.COPY, copied.action());
        assertFalse(copied.action().relocates());
        assertFalse(copied.action().removes());
        assertTrue(copied.removedPaths().isEmpty());
        assertTrue(copied.destinationPublished());
        Path clone = destination.resolve("source");
        assertEquals("source bytes", Files.readString(clone.resolve("nested/document.txt")));
        assertEquals(timestamp, Files.getLastModifiedTime(clone.resolve("nested/document.txt")));
        assertEquals(timestamp, Files.getLastModifiedTime(clone.resolve("nested")));
        Files.writeString(clone.resolve("nested/document.txt"), "independent copy");
        assertEquals("source bytes", Files.readString(original));

        var single = files.copy(files.inspect(original), destination);
        assertTrue(single.succeeded());
        assertEquals("source bytes", Files.readString(destination.resolve("document.txt")));
    }

    @Test
    void symlinksRemainLinksAndExternalTargetsAreNotCopiedOrChanged() throws Exception {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path outside = Files.writeString(directory.resolve("outside.txt"), "external");
        Path source = Files.createDirectory(directory.resolve("source"));
        Path target = Path.of("../../outside.txt");
        Files.createSymbolicLink(source.resolve("link"), target);
        Path destination = Files.createDirectory(directory.resolve("destination"));

        assertTrue(files.copy(files.inspect(source), destination).succeeded());
        Path link = destination.resolve("source/link");
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(target, Files.readSymbolicLink(link));
        assertEquals("external", Files.readString(outside));
        assertFalse(Files.exists(destination.resolve("outside.txt")));
    }

    @Test
    void existingEntriesAndPhysicalSelfOrDescendantDestinationsAreRejected() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("document.txt"), "kept");
        Path inside = Files.createDirectory(source.resolve("inside"));
        assertFalse(files.copy(files.inspect(source), directory).succeeded());
        assertFalse(files.copy(files.inspect(source), source).succeeded());
        assertFalse(files.copy(files.inspect(source), inside).succeeded());
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), inside);
        assertFalse(files.copy(files.inspect(source), alias).succeeded());
        assertEquals("kept", Files.readString(source.resolve("document.txt")));
        try (var children = Files.list(inside)) { assertEquals(0, children.count()); }
    }

    @Test
    void readonlyMetadataIsPreservedAndPrivateStagingDoesNotRemain() throws Exception {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path source = Files.createDirectory(directory.resolve("source"));
        Path document = Files.writeString(source.resolve("document.txt"), "readonly");
        Files.setPosixFilePermissions(document, PosixFilePermissions.fromString("r--r-----"));
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("r-xr-x---"));
        Path destination = Files.createDirectory(directory.resolve("destination"));
        try {
            var result = files.copy(files.inspect(source), destination);
            assertTrue(result.succeeded(), () -> String.valueOf(result.failure()));
            assertEquals(Files.getPosixFilePermissions(source), Files.getPosixFilePermissions(destination.resolve("source")));
            assertEquals(Files.getPosixFilePermissions(document), Files.getPosixFilePermissions(destination.resolve("source/document.txt")));
            try (var entries = Files.list(destination)) {
                assertEquals(List.of("source"), entries.map(path -> path.getFileName().toString()).toList());
            }
        } finally {
            Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rwx------"));
            if (Files.isDirectory(destination.resolve("source")))
                Files.setPosixFilePermissions(destination.resolve("source"), PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aNewSourceChildBeforePublicationRefusesAnIncompleteCopy() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("original.txt"), "original");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        var changing = new ExplorerFileService(new ExplorerFileService.MoveObserver() {
            @Override public void copied(Path ignored, Path staged) throws IOException {
                Files.writeString(source.resolve("added.txt"), "new child");
            }
        });

        var result = changing.copy(changing.inspect(source), destination);

        assertFalse(result.succeeded());
        assertFalse(result.destinationPublished());
        assertFalse(result.partiallyChanged());
        assertEquals("new child", Files.readString(source.resolve("added.txt")));
        assertEquals("original", Files.readString(source.resolve("original.txt")));
        try (var entries = Files.list(destination)) { assertEquals(0, entries.count()); }
    }

    @Test
    void aPublishedCopyFailureKeepsBothTheSourceAndTheActualReceipt() throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "source");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        var interrupted = new ExplorerFileService(new ExplorerFileService.MoveObserver() {
            @Override public void published(Path ignored, Path published) throws IOException {
                throw new IOException("injected post-publication failure");
            }
        });

        var result = interrupted.copy(interrupted.inspect(source), destination);

        assertEquals(ExplorerFileService.Action.COPY, result.action());
        assertFalse(result.succeeded());
        assertTrue(result.destinationPublished());
        assertTrue(result.partiallyChanged());
        assertTrue(result.removedPaths().isEmpty());
        assertEquals("source", Files.readString(source));
        assertEquals("source", Files.readString(result.destination()));
        try (var entries = Files.list(destination)) { assertEquals(1, entries.count()); }
    }

    @Test
    void requiredRedoContentsAreCheckedBeforePublishingEvenWhenSourceAndStagingChangeTogether() throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "before");
        Path destination = Files.createDirectory(directory.resolve("destination"));
        var original = files.inspect(source);
        var required = files.captureCopyGuard(source, 10);
        var changing = new ExplorerFileService(new ExplorerFileService.MoveObserver() {
            @Override public void copied(Path ignored, Path staging) throws IOException {
                Files.writeString(source, "edited");
                Files.writeString(staging, "edited");
                Files.setLastModifiedTime(source, original.modified());
                Files.setLastModifiedTime(staging, original.modified());
            }
        });

        var refused = changing.copy(original, destination, required);

        assertFalse(refused.succeeded());
        assertFalse(refused.destinationPublished());
        assertTrue(refused.failure().getMessage().contains("source changed"));
        assertEquals("edited", Files.readString(source));
        try (var entries = Files.list(destination)) { assertEquals(0, entries.count()); }
    }
}
