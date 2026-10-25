package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextModelSaveTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesExistingPosixPermissions() throws IOException {
        assumePosix();
        Path target = tempDir.resolve("script.sh");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        Set<PosixFilePermission> permissions = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_EXECUTE
        );
        Files.setPosixFilePermissions(target, permissions);

        modelWith("#!/bin/sh\necho updated").save(JvmDocumentStorage.resource(target));

        assertEquals("#!/bin/sh\necho updated", Files.readString(target));
        assertEquals(permissions, Files.getPosixFilePermissions(target));
    }

    @ParameterizedTest
    @ValueSource(ints = {01750, 02750, 04750, 07750})
    void noEditSavePreservesSpecialUnixPermissionBits(int mode) throws IOException {
        Path target = fileWithUnixMode(mode);
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(JvmDocumentStorage.resource(target));

        model.saveIfUnchanged(JvmDocumentStorage.resource(target), model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);

        assertEquals("original", Files.readString(target));
        assertEquals(mode, (int) Files.getAttribute(target, "unix:mode") & 07777);
    }

    @ParameterizedTest
    @ValueSource(ints = {01750, 02750, 04750, 07750})
    void editedSaveThroughSymlinkPreservesSpecialUnixPermissionBits(int mode) throws IOException {
        Path target = fileWithUnixMode(mode);
        Path link = Files.createSymbolicLink(tempDir.resolve("alias.txt"), target.getFileName());
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(JvmDocumentStorage.resource(link));
        model.insertText(new Position(0, 0), "edited ");

        model.saveIfUnchanged(JvmDocumentStorage.resource(link), model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);

        assertEquals("edited original", Files.readString(target));
        assertEquals(mode, (int) Files.getAttribute(target, "unix:mode") & 07777);
        assertTrue(Files.isSymbolicLink(link));
    }

    private Path fileWithUnixMode(int mode) throws IOException {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("unix"));
        Path target = Files.writeString(tempDir.resolve("special.txt"), "original");
        Files.setAttribute(target, "unix:mode", mode);
        assumeTrue(((int) Files.getAttribute(target, "unix:mode") & 07777) == mode,
            "Filesystem cannot retain the requested special permission bits");
        return target;
    }

    @Test
    void savesThroughSymlinkWithoutReplacingTheLink() throws IOException {
        assumePosix();
        Path target = tempDir.resolve("target.sh");
        Path link = tempDir.resolve("alias.sh");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
            target,
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        );
        Files.createSymbolicLink(link, target.getFileName());

        modelWith("new").save(JvmDocumentStorage.resource(link));

        assertTrue(Files.isSymbolicLink(link));
        assertEquals(target.getFileName(), Files.readSymbolicLink(link));
        assertEquals("new", Files.readString(target));
        assertTrue(Files.isExecutable(target));
    }

    @Test
    void refusesToReplaceBrokenSymlink() throws IOException {
        assumePosix();
        Path missingTarget = tempDir.resolve("missing.txt");
        Path link = tempDir.resolve("broken.txt");
        Files.createSymbolicLink(link, missingTarget.getFileName());

        assertThrows(NoSuchFileException.class, () -> modelWith("new").save(JvmDocumentStorage.resource(link)));

        assertTrue(Files.isSymbolicLink(link));
        assertEquals(missingTarget.getFileName(), Files.readSymbolicLink(link));
        assertFalse(Files.exists(missingTarget));
    }

    @Test
    void createsNewFileWithoutExecutableBits() throws IOException {
        assumePosix();
        Path target = tempDir.resolve("new.txt");
        TextModel model = modelWith("new");

        model.save(JvmDocumentStorage.resource(target), model.fileMetadata(), SaveDisposition.CREATE_NEW);

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target);
        assertEquals("new", Files.readString(target));
        assertTrue(permissions.contains(PosixFilePermission.OWNER_READ));
        assertTrue(permissions.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(permissions.contains(PosixFilePermission.OWNER_EXECUTE));
        assertFalse(permissions.contains(PosixFilePermission.GROUP_EXECUTE));
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_EXECUTE));
    }

    @Test
    void atomicSaveBreaksHardlinkWithoutChangingTheOtherName() throws IOException {
        assumePosix();
        Path target = tempDir.resolve("target.txt");
        Path otherLink = tempDir.resolve("other.txt");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        Files.createLink(otherLink, target);
        assumeTrue(Files.isSameFile(target, otherLink), "hard links are not supported");

        modelWith("new").save(JvmDocumentStorage.resource(target));

        assertEquals("new", Files.readString(target));
        assertEquals("old", Files.readString(otherLink));
        assertFalse(Files.isSameFile(target, otherLink));
    }

    @Test
    void preservesBinaryAndEmptyUserAttributesWhenReplacingAnExistingFile() throws IOException {
        Path target = tempDir.resolve("attributes.txt");
        Files.writeString(target, "old");
        assumeTrue(Files.getFileStore(target).supportsFileAttributeView("user"));
        UserDefinedFileAttributeView view = Files.getFileAttributeView(target, UserDefinedFileAttributeView.class);
        byte[] value = {0, 1, (byte) 0xFF, 0, 42};
        view.write("audit.binary", ByteBuffer.wrap(value));
        view.write("audit.empty", ByteBuffer.allocate(0));

        modelWith("updated").save(JvmDocumentStorage.resource(target));

        assertEquals("updated", Files.readString(target));
        assertArrayEquals(value, readUserAttribute(target, "audit.binary"));
        assertArrayEquals(new byte[0], readUserAttribute(target, "audit.empty"));
    }

    @Test
    void saveThroughSymlinkPreservesTargetUserAttributesAndOwnership() throws IOException {
        assumePosix();
        Path target = tempDir.resolve("target.txt");
        Path link = tempDir.resolve("link.txt");
        Files.writeString(target, "old");
        assumeTrue(Files.getFileStore(target).supportsFileAttributeView("user"));
        var owner = Files.getOwner(target);
        var group = Files.readAttributes(target, java.nio.file.attribute.PosixFileAttributes.class).group();
        Files.getFileAttributeView(target, UserDefinedFileAttributeView.class)
            .write("audit", ByteBuffer.wrap(new byte[] {7, 8, 9}));
        Files.createSymbolicLink(link, target.getFileName());

        modelWith("updated").save(JvmDocumentStorage.resource(link));

        assertTrue(Files.isSymbolicLink(link));
        assertEquals(owner, Files.getOwner(target));
        assertEquals(group, Files.readAttributes(target, java.nio.file.attribute.PosixFileAttributes.class).group());
        assertArrayEquals(new byte[] {7, 8, 9}, readUserAttribute(target, "audit"));
    }

    private static byte[] readUserAttribute(Path target, String name) throws IOException {
        UserDefinedFileAttributeView view = Files.getFileAttributeView(target, UserDefinedFileAttributeView.class);
        ByteBuffer bytes = ByteBuffer.allocate(view.size(name));
        view.read(name, bytes);
        return bytes.array();
    }

    private void assumePosix() throws IOException {
        assumeTrue(
            Files.getFileStore(tempDir).supportsFileAttributeView("posix"),
            "POSIX file attributes are not supported"
        );
    }

    private static TextModel modelWith(String text) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), text);
        return model;
    }
}
