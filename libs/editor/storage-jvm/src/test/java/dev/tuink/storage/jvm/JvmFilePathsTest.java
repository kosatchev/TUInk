package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmFilePathsTest {
    @TempDir Path directory;

    @Test
    void parentLinksResolveBeforeDotSegmentsAndEveryLeafRemainsUnresolved() throws Exception {
        assumePosix();
        Path deeper = Files.createDirectories(directory.resolve("real/deep"));
        Path physicalParent = deeper.getParent().toRealPath();
        Path parentAlias = Files.createSymbolicLink(directory.resolve("parent"), directory.relativize(deeper));
        Path relativeParentAlias = Path.of("").toAbsolutePath().relativize(parentAlias);
        Files.writeString(physicalParent.resolve("regular.txt"), "physical entry");
        Files.writeString(directory.resolve("regular.txt"), "lexical neighbor");
        Files.writeString(physicalParent.resolve("destination.txt"), "symlink destination");
        Path liveLink = Files.createSymbolicLink(physicalParent.resolve("live.txt"), Path.of("destination.txt"));
        Path brokenLink = Files.createSymbolicLink(physicalParent.resolve("broken.txt"), Path.of("absent.txt"));
        Files.createDirectory(physicalParent.resolve("folder"));

        for (String leaf : List.of("regular.txt", "missing.txt", "live.txt", "broken.txt", "folder")) {
            Path requested = parentAlias.resolve("../" + leaf);
            Path expected = physicalParent.resolve(leaf);
            assertNotEquals(requested.normalize(), expected);
            assertEquals(expected, JvmFilePaths.resolveEntry(requested), leaf);
            Path relative = relativeParentAlias.resolve("../" + leaf);
            assertEquals(expected, JvmFilePaths.resolveEntry(relative), "relative " + leaf);
        }

        assertEquals("physical entry", Files.readString(JvmFilePaths.resolveEntry(parentAlias.resolve("../regular.txt"))));
        assertEquals("lexical neighbor", Files.readString(directory.resolve("regular.txt")));
        assertTrue(Files.isSymbolicLink(liveLink));
        assertTrue(Files.isSymbolicLink(brokenLink));
        assertFalse(Files.exists(physicalParent.resolve("missing.txt"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(physicalParent.resolve("absent.txt"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void finalDotComponentsRemainAfterResolvingTheirParentLink() throws Exception {
        assumePosix();
        Path deeper = Files.createDirectories(directory.resolve("real/deep"));
        Path alias = Files.createSymbolicLink(directory.resolve("parent"), directory.relativize(deeper));

        for (String leaf : List.of(".", "..")) {
            Path requested = alias.resolve(leaf);
            Path resolved = JvmFilePaths.resolveEntry(requested);

            assertEquals(deeper.toRealPath().resolve(leaf), resolved);
            assertNotEquals(resolved.normalize(), resolved);
            assertEquals(requested.toRealPath(), resolved.toRealPath());
        }
    }

    @Test
    void unresolvedParentsFailWithoutCreatingAnythingOrAddingTypeChecks() throws Exception {
        assumePosix();
        Path missingParent = directory.resolve("missing-parent");
        Path brokenParent = Files.createSymbolicLink(directory.resolve("broken-parent"), Path.of("missing-parent"));
        Path fileParent = Files.writeString(directory.resolve("file-parent"), "keep parent bytes");

        for (Path parent : List.of(missingParent, brokenParent, fileParent.resolve("child"))) {
            assertThrows(IOException.class, () -> JvmFilePaths.resolveEntry(parent.resolve("new.txt")), parent.toString());
        }

        // Resolving the parent itself is an address calculation; file/directory
        // validation stays with the consumers, as in the original expression.
        assertEquals(fileParent.toRealPath().resolve("new.txt"),
            JvmFilePaths.resolveEntry(fileParent.resolve("new.txt")));

        assertFalse(Files.exists(missingParent, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isSymbolicLink(brokenParent));
        assertEquals(Path.of("missing-parent"), Files.readSymbolicLink(brokenParent));
        assertEquals("keep parent bytes", Files.readString(fileParent));
        try (var children = Files.list(directory)) {
            assertEquals(2, children.count());
        }
    }

    @Test
    void aFilesystemRootCannotBeResolvedAsAnEntryInItsParent() {
        assertThrows(IOException.class, () -> JvmFilePaths.resolveEntry(directory.toAbsolutePath().getRoot()));
    }

    private void assumePosix() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    }
}
