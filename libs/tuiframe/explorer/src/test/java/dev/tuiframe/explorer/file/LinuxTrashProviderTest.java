package dev.tuiframe.explorer.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class LinuxTrashProviderTest {
    @TempDir Path directory;

    private LinuxTrashProvider provider(Path dataHome) throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        long uid = ((Number) Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
        return new LinuxTrashProvider(dataHome, uid);
    }

    @Test
    void availabilityProbeDoesNotCreateDirectoriesOrMoveAnything() throws Exception {
        Path data = directory.resolve("data-home/not-created");
        Path source = Files.writeString(directory.resolve("source.txt"), "retained");
        var provider = provider(data);

        assertTrue(provider.availability(source).available());
        assertFalse(Files.exists(directory.resolve("data-home")));
        assertEquals("retained", Files.readString(source));
    }

    @Test
    void trashedEntryUsesPrivateSystemStorageAndLosslessOriginalPathMetadata() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data"));
        var files = new ExplorerFileService(provider(data));
        Path source = Files.writeString(directory.resolve("line\npercent% and ü.txt"), "exact bytes");
        String encodedPath = source.toUri().getRawPath();

        var result = files.trash(files.inspect(source));

        assertTrue(result.succeeded(), () -> String.valueOf(result.failure()));
        assertEquals(ExplorerFileService.Action.TRASH, result.action());
        assertEquals(ExplorerFileService.RemovalDisposition.TRASHED, result.removalDisposition());
        assertNull(result.destination(), "A trashed document must never bind to a location inside the trash");
        assertEquals(Set.of(source), result.removedPaths());
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
        Path root = data.resolve("Trash");
        Path entry = onlyEntry(root.resolve("files"));
        Path info = onlyEntry(root.resolve("info"));
        assertEquals(entry.getFileName() + ".trashinfo", info.getFileName().toString());
        assertEquals("exact bytes", Files.readString(entry));
        var lines = Files.readAllLines(info);
        assertEquals("[Trash Info]", lines.get(0));
        assertEquals("Path=" + encodedPath, lines.get(1));
        assertTrue(lines.get(1).contains("%0A") && lines.get(1).contains("%25"));
        assertTrue(lines.get(2).matches("DeletionDate=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"));
        for (Path privateDirectory : new Path[] {root, root.resolve("files"), root.resolve("info")})
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(privateDirectory));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(info));
    }

    @Test
    void duplicateBasenamesReceiveSeparateAtomicMetadataReservations() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data"));
        var files = new ExplorerFileService(provider(data));
        Path first = Files.writeString(Files.createDirectory(directory.resolve("first")).resolve("same.txt"), "first");
        Path second = Files.writeString(Files.createDirectory(directory.resolve("second")).resolve("same.txt"), "second");
        assertTrue(files.trash(files.inspect(first)).succeeded());
        assertTrue(files.trash(files.inspect(second)).succeeded());

        try (var entries = Files.list(data.resolve("Trash/files"))) {
            assertEquals(Set.of("first", "second"), entries.map(path -> {
                try { return Files.readString(path); } catch (IOException failure) { throw new IllegalStateException(failure); }
            }).collect(java.util.stream.Collectors.toSet()));
        }
        try (var entries = Files.list(data.resolve("Trash/info"))) { assertEquals(2, entries.count()); }
    }

    @Test
    void trashingADirectoryOrLinkKeepsExternalLinkTargets() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data"));
        var files = new ExplorerFileService(provider(data));
        Path external = Files.writeString(directory.resolve("external.txt"), "outside");
        Path folder = Files.createDirectory(directory.resolve("folder"));
        Files.writeString(folder.resolve("inside.txt"), "inside");
        Files.createSymbolicLink(folder.resolve("link"), external);
        assertTrue(files.trash(files.inspect(folder)).succeeded());
        Path entry = onlyEntry(data.resolve("Trash/files"));
        assertEquals("inside", Files.readString(entry.resolve("inside.txt")));
        assertTrue(Files.isSymbolicLink(entry.resolve("link")));
        assertEquals("outside", Files.readString(external));

        Path link = Files.createSymbolicLink(directory.resolve("broken-link"), Path.of("missing"));
        assertTrue(files.trash(files.inspect(link)).succeeded());
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(directory.resolve("missing")));
    }

    @Test
    void unsafeTrashDirectoryCannotCausePermanentDeletionOrFollowItsSymlink() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data"));
        Path external = Files.createDirectory(directory.resolve("external"));
        Path keep = Files.writeString(external.resolve("keep.txt"), "external");
        Files.createSymbolicLink(data.resolve("Trash"), external);
        var provider = provider(data);
        var files = new ExplorerFileService(provider);
        Path source = Files.writeString(directory.resolve("source.txt"), "retained");

        assertFalse(provider.availability(source).available());
        assertFalse(files.trash(files.inspect(source)).succeeded());
        assertEquals("retained", Files.readString(source));
        assertEquals("external", Files.readString(keep));
        assertFalse(Files.exists(external.resolve("files")));
        assertFalse(Files.exists(external.resolve("info")));
    }

    @Test
    void nonprivateTrashPermissionsAreRefusedWithoutChangingTheSelectedFile() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data"));
        Path root = Files.createDirectory(data.resolve("Trash"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        var provider = provider(data);
        var files = new ExplorerFileService(provider);
        Path source = Files.writeString(directory.resolve("source.txt"), "retained");
        assertFalse(provider.availability(source).available());
        assertFalse(files.trash(files.inspect(source)).succeeded());
        assertEquals("retained", Files.readString(source));
    }

    @Test
    void failedTransferDiscardsOnlyItsMetadataReservation() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data"));
        var provider = provider(data);
        var service = new ExplorerFileService(provider);
        Path source = Files.writeString(directory.resolve("source.txt"), "retained");
        var target = service.inspect(source);
        var result = provider.trash(target, (selected, destination) ->
            new ExplorerFileService.Result(ExplorerFileService.Action.MOVE, selected.path(), destination,
                false, false, new IOException("injected refusal")));

        assertFalse(result.succeeded());
        assertFalse(result.destinationPublished());
        assertTrue(result.removedPaths().isEmpty());
        assertEquals("retained", Files.readString(source));
        try (var entries = Files.list(data.resolve("Trash/info"))) { assertEquals(0, entries.count()); }
        try (var entries = Files.list(data.resolve("Trash/files"))) { assertEquals(0, entries.count()); }
    }

    @Test
    void partialPublishedTransferRetainsItsMetadataAndActualRemovalReceipt() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data"));
        var provider = provider(data);
        var service = new ExplorerFileService(provider);
        Path source = Files.writeString(directory.resolve("source.txt"), "source bytes");
        var target = service.inspect(source);
        var result = provider.trash(target, (selected, destination) -> {
            try { Files.copy(selected.path(), destination); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
            return new ExplorerFileService.Result(ExplorerFileService.Action.MOVE, selected.path(), destination,
                false, true, new IOException("source cleanup incomplete"), Set.of());
        });
        assertFalse(result.succeeded());
        assertTrue(result.partiallyChanged());
        assertEquals(ExplorerFileService.RemovalDisposition.TRASHED, result.removalDisposition());
        assertEquals("source bytes", Files.readString(source));
        assertEquals("source bytes", Files.readString(onlyEntry(data.resolve("Trash/files"))));
        assertTrue(Files.exists(onlyEntry(data.resolve("Trash/info"))));
        assertTrue(result.removedPaths().isEmpty(), "Retained source documents must remain bound");
    }

    @Test
    void trashingTheTrashOrItsAncestorIsRefusedBeforeAnyMutation() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path data = Files.createDirectory(source.resolve("data"));
        var provider = provider(data);
        var files = new ExplorerFileService(provider);
        assertFalse(provider.availability(source).available());
        assertFalse(files.trash(files.inspect(source)).succeeded());
        assertFalse(Files.exists(data.resolve("Trash")));

        Path file = Files.writeString(directory.resolve("file.txt"), "bytes");
        assertTrue(files.trash(files.inspect(file)).succeeded());
        Path entry = onlyEntry(data.resolve("Trash/files"));
        assertFalse(provider.availability(entry).available());
        assertFalse(files.trash(files.inspect(entry)).succeeded());
        assertEquals("bytes", Files.readString(entry));
    }

    @Test
    void aNonUnixProviderIsUnavailableWithoutCreatingTrashInsideItsFilesystem() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data"));
        var provider = provider(data);
        URI archive = URI.create("jar:" + directory.resolve("archive.zip").toUri());
        try (var zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            Path source = Files.writeString(zip.getPath("/source.txt"), "retained");
            assertFalse(provider.availability(source).available());
            try (var entries = Files.list(zip.getPath("/"))) { assertEquals(1, entries.count()); }
            assertEquals("retained", Files.readString(source));
        }
    }

    private static Path onlyEntry(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            var all = entries.toList();
            assertEquals(1, all.size());
            return all.getFirst();
        }
    }
}
