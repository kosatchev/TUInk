package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuink.core.*;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class LinuxFileAclPathTest {
    @TempDir Path directory;

    @Test
    void savesANonUtf8FilenameWithoutAReplacementCharacterNeighbour() throws Exception {
        Path target = rawChild(directory, "legacy-%FF.txt");
        Files.writeString(target, "original");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));

        save(target);

        assertEquals("edited original", Files.readString(target));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target));
        assertFalse(Files.exists(directory.resolve("legacy-\uFFFD.txt")));
    }

    @Test
    void retainsTheOriginalAclWhenALossyNameWouldIdentifyAnotherFile() throws Exception {
        Path target = Files.writeString(rawChild(directory, "legacy-%FF.txt"), "original");
        Path neighbour = Files.writeString(directory.resolve("legacy-\uFFFD.txt"), "original");
        Path alias = Files.createSymbolicLink(directory.resolve("original-alias"), target);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(neighbour, PosixFilePermissions.fromString("rw-------"));
        LinuxAclFixtures.setAccessAcl(alias, LinuxAclFixtures.namedAcl(alias, 4, 4));
        LinuxAclFixtures.setAccessAcl(neighbour, LinuxAclFixtures.namedAcl(neighbour, 0, 4));
        byte[] originalAcl = LinuxAclFixtures.accessAcl(alias);
        byte[] neighbourAcl = LinuxAclFixtures.accessAcl(neighbour);
        assertFalse(java.util.Arrays.equals(originalAcl, neighbourAcl));
        assertEquals(Files.getAttribute(target, "unix:mode"), Files.getAttribute(neighbour, "unix:mode"));

        save(target);

        assertEquals("edited original", Files.readString(target));
        assertEquals("original", Files.readString(neighbour));
        assertArrayEquals(originalAcl, LinuxAclFixtures.accessAcl(alias));
        assertArrayEquals(neighbourAcl, LinuxAclFixtures.accessAcl(neighbour));
    }

    @Test
    void savesAndVerifiesStagedFilesInsideANonUtf8Parent() throws Exception {
        Path parent = Files.createDirectory(rawChild(directory, "parent-%FE"));
        Path target = Files.writeString(parent.resolve("document.txt"), "original");
        Path otherParent = Files.createDirectory(directory.resolve("parent-\uFFFD"));
        Path other = Files.writeString(otherParent.resolve("document.txt"), "other");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));

        save(target);

        assertEquals("edited original", Files.readString(target));
        assertEquals("other", Files.readString(other));
        assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(target));
        try (var children = Files.list(parent)) {
            assertEquals(1, children.count(), "Staged files should be removed after publication");
        }
    }

    private static Path rawChild(Path parent, String encodedName) {
        return Path.of(URI.create(parent.toUri().toASCIIString() + encodedName));
    }

    private static void save(Path target) throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(JvmDocumentStorage.resource(target));
        model.insertText(new Position(0, 0), "edited ");
        model.saveIfUnchanged(JvmDocumentStorage.resource(target), model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);
        JvmDocumentStorage.baselineOf(model.fileBaseline()).requireUnchanged(target);
    }

}
