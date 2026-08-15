package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

        modelWith("#!/bin/sh\necho updated").save(target);

        assertEquals("#!/bin/sh\necho updated", Files.readString(target));
        assertEquals(permissions, Files.getPosixFilePermissions(target));
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

        modelWith("new").save(link);

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

        assertThrows(NoSuchFileException.class, () -> modelWith("new").save(link));

        assertTrue(Files.isSymbolicLink(link));
        assertEquals(missingTarget.getFileName(), Files.readSymbolicLink(link));
        assertFalse(Files.exists(missingTarget));
    }

    @Test
    void createsNewFileWithoutExecutableBits() throws IOException {
        assumePosix();
        Path target = tempDir.resolve("new.txt");

        modelWith("new").save(target);

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

        modelWith("new").save(target);

        assertEquals("new", Files.readString(target));
        assertEquals("old", Files.readString(otherLink));
        assertFalse(Files.isSameFile(target, otherLink));
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
