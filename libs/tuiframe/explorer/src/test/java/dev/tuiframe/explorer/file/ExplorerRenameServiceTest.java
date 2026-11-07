package dev.tuiframe.explorer.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExplorerRenameServiceTest {
    @TempDir Path directory;
    private final ExplorerFileService files = new ExplorerFileService();

    @Test
    void renamesAFileWithinItsParentWithoutChangingItsBytes() throws Exception {
        byte[] bytes = {0, 1, (byte) 0xFF, 13, 10};
        Path source = Files.write(directory.resolve("source.bin"), bytes);

        var result = files.rename(files.inspect(source), "renamed.bin");

        assertTrue(result.succeeded());
        assertTrue(result.destinationPublished());
        assertEquals(ExplorerFileService.Action.RENAME, result.action());
        assertEquals(source, result.source());
        assertEquals(directory.resolve("renamed.bin"), result.destination());
        assertArrayEquals(bytes, Files.readAllBytes(result.destination()));
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void renamesAFolderTogetherWithItsChildren() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path nested = Files.createDirectory(source.resolve("nested"));
        Files.writeString(nested.resolve("document.txt"), "original");

        var result = files.rename(files.inspect(source), "renamed");

        assertTrue(result.succeeded());
        assertEquals("original", Files.readString(directory.resolve("renamed/nested/document.txt")));
        assertFalse(Files.exists(source));
    }

    @Test
    void anExistingNameIsNeverReplaced() throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "source");
        Path existing = Files.writeString(directory.resolve("existing.txt"), "existing");

        var result = files.rename(files.inspect(source), "existing.txt");

        assertFalse(result.succeeded());
        assertFalse(result.destinationPublished());
        assertNotNull(result.failure());
        assertEquals("source", Files.readString(source));
        assertEquals("existing", Files.readString(existing));
    }

    @Test
    void anUnchangedNameIsASuccessfulNoOp() throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "original");
        var before = files.inspect(source);

        var result = files.rename(before, "source.txt");

        assertTrue(result.succeeded());
        assertFalse(result.destinationPublished());
        assertEquals(source, result.destination());
        assertEquals(before, files.inspect(source));
        assertEquals("original", Files.readString(source));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "../outside.txt", "nested/file.txt", "nested\\file.txt", "/absolute.txt", "bad\u0000name"})
    void aNewNameCannotLeaveTheParentOrRemoveTheOriginal(String name) throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "original");

        var result = files.rename(files.inspect(source), name);

        assertFalse(result.succeeded());
        assertFalse(result.destinationPublished());
        assertEquals("original", Files.readString(source));
        try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
    }

    @Test
    void aReplacedEntryCannotBeRenamedByAnOldDialog() throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "original");
        var observed = files.inspect(source);
        Path original = directory.resolve("retained.txt");
        Files.move(source, original);
        Files.writeString(source, "replacement");

        var result = files.rename(observed, "renamed.txt");

        assertFalse(result.succeeded());
        assertEquals("replacement", Files.readString(source));
        assertEquals("original", Files.readString(original));
        assertFalse(Files.exists(directory.resolve("renamed.txt")));
    }

    @Test
    void renamingARelativeLinkPreservesTheLinkAndItsPhysicalTarget() throws Exception {
        Path target = Files.writeString(directory.resolve("target.txt"), "target");
        Path source = Files.createSymbolicLink(directory.resolve("source-link.txt"), Path.of("target.txt"));

        var result = files.rename(files.inspect(source), "renamed-link.txt");

        assertTrue(result.succeeded());
        assertTrue(Files.isSymbolicLink(result.destination()));
        assertEquals(Path.of("target.txt"), Files.readSymbolicLink(result.destination()));
        assertEquals(target, result.destination().toRealPath());
        assertEquals("target", Files.readString(target));
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void renamingABrokenLinkDoesNotCreateItsMissingTarget() throws Exception {
        Path source = Files.createSymbolicLink(directory.resolve("broken-link"), Path.of("missing.txt"));

        var result = files.rename(files.inspect(source), "renamed-link");

        assertTrue(result.succeeded());
        assertTrue(Files.isSymbolicLink(result.destination()));
        assertEquals(Path.of("missing.txt"), Files.readSymbolicLink(result.destination()));
        assertFalse(Files.exists(directory.resolve("missing.txt"), LinkOption.NOFOLLOW_LINKS));
    }
}
