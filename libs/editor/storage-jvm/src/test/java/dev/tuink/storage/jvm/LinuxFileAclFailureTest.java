package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuink.core.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class LinuxFileAclFailureTest {
    @TempDir Path directory;

    @Test
    void rejectsReadPermissionErrorsInsteadOfTreatingTheAclAsAbsent() throws Exception {
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        LinuxFileAcl.Access denied = new NativeAccess() {
            @Override
            public int get(byte[] path, byte[] name, byte[] value) {
                return -13; // EACCES
            }
        };

        assertThrows(IOException.class, () -> LinuxFileAcl.read(target, denied));

        assertEquals("original", Files.readString(target));
    }

    @Test
    void keepsStagedBytesPrivateWhenCopyingTheSourceAclFails() throws Exception {
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        LinuxAclFixtures.setAccessAcl(target, LinuxAclFixtures.namedAcl(target, 4, 4));
        byte[] before = LinuxAclFixtures.accessAcl(target);
        Path staged = privateStage(directory);
        AtomicInteger attempts = new AtomicInteger();
        LinuxFileAcl.Access denied = new NativeAccess() {
            @Override
            public int set(byte[] path, byte[] name, byte[] value) throws IOException {
                assertArrayEquals(LinuxFileAcl.nativePath(staged), path);
                assertArrayEquals(before, value);
                assertPrivate(staged);
                attempts.incrementAndGet();
                return -13; // EACCES
            }
        };
        LinuxFileAcl original = LinuxFileAcl.read(target, denied);

        assertThrows(IOException.class, () -> original.apply(staged));

        assertEquals(1, attempts.get());
        assertPrivate(staged);
        assertArrayEquals(before, LinuxAclFixtures.accessAcl(target));
        assertEquals("original", Files.readString(target));
    }

    @Test
    void keepsStagedBytesPrivateWhenRemovingAnInheritedAclFails() throws Exception {
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        LinuxAclFixtures.removeAccessAcl(target);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));
        LinuxAclFixtures.setDefaultAcl(directory, LinuxAclFixtures.namedAcl(directory, 4, 4));
        Path staged = privateStage(directory);
        assertNotNull(LinuxAclFixtures.accessAcl(staged));
        AtomicInteger attempts = new AtomicInteger();
        LinuxFileAcl.Access denied = new NativeAccess() {
            @Override
            public int remove(byte[] path, byte[] name) throws IOException {
                assertArrayEquals(LinuxFileAcl.nativePath(staged), path);
                assertPrivate(staged);
                attempts.incrementAndGet();
                return -13; // EACCES
            }
        };
        LinuxFileAcl original = LinuxFileAcl.read(target, denied);

        assertThrows(IOException.class, () -> original.apply(staged));

        assertEquals(1, attempts.get());
        assertPrivate(staged);
        assertEquals("original", Files.readString(target));
    }

    @Test
    void verifiesTheCopiedAclEvenWhenTheWriteReportedSuccess() throws Exception {
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        LinuxAclFixtures.setAccessAcl(target, LinuxAclFixtures.namedAcl(target, 4, 4));
        byte[] before = LinuxAclFixtures.accessAcl(target);
        Path staged = privateStage(directory);
        LinuxFileAcl.Access ineffective = new NativeAccess() {
            @Override
            public int set(byte[] path, byte[] name, byte[] value) {
                return 0;
            }
        };
        LinuxFileAcl original = LinuxFileAcl.read(target, ineffective);

        assertThrows(IOException.class, () -> original.apply(staged));

        assertArrayEquals(before, LinuxAclFixtures.accessAcl(target));
        assertEquals("original", Files.readString(target));
    }

    @Test
    void verifiesThatAnInheritedAclWasRemovedEvenWhenRemovalReportedSuccess() throws Exception {
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        LinuxAclFixtures.removeAccessAcl(target);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));
        LinuxAclFixtures.setDefaultAcl(directory, LinuxAclFixtures.namedAcl(directory, 4, 4));
        Path staged = privateStage(directory);
        assertNotNull(LinuxAclFixtures.accessAcl(staged));
        LinuxFileAcl.Access ineffective = new NativeAccess() {
            @Override
            public int remove(byte[] path, byte[] name) {
                return 0;
            }
        };
        LinuxFileAcl original = LinuxFileAcl.read(target, ineffective);

        assertThrows(IOException.class, () -> original.apply(staged));

        assertPrivate(staged);
        assertEquals("original", Files.readString(target));
    }

    private static Path privateStage(Path directory) throws IOException {
        Path staged = Files.createTempFile(directory, ".staged-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.writeString(staged, "edited original");
    }

    private static void assertPrivate(Path staged) {
        try {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(staged));
            assertEquals("edited original", Files.readString(staged));
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static class NativeAccess implements LinuxFileAcl.Access {
        @Override
        public int get(byte[] path, byte[] name, byte[] value) throws IOException {
            return LinuxXattrServices.get(path, name, value);
        }

        @Override
        public int set(byte[] path, byte[] name, byte[] value) throws IOException {
            return LinuxXattrServices.set(path, name, value);
        }

        @Override
        public int remove(byte[] path, byte[] name) throws IOException {
            return LinuxXattrServices.remove(path, name);
        }
    }
}
