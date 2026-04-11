package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.ViewportState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryStoreTest {
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsSnapshotWithPrivatePermissions() throws IOException {
        assumePosix();
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = new RecoveryStore(directory);
        RecoverySnapshot snapshot = snapshot();

        store.save("Session 01", snapshot);

        Path text = directory.resolve("session01.txt");
        Path meta = directory.resolve("session01.meta.properties");
        assertEquals(PRIVATE_DIRECTORY, Files.getPosixFilePermissions(directory));
        assertEquals(PRIVATE_FILE, Files.getPosixFilePermissions(text));
        assertEquals(PRIVATE_FILE, Files.getPosixFilePermissions(meta));
        assertEquals(List.of(), temporaryFiles(directory));

        List<RecoveryStore.Entry> entries = store.loadAll();
        assertEquals(1, entries.size());
        assertEquals("session01", entries.get(0).id());
        assertEquals(snapshot, entries.get(0).snapshot());
    }

    @Test
    void loadHardensPermissionsFromOlderInstallation() throws IOException {
        assumePosix();
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = new RecoveryStore(directory);
        store.save("legacy", snapshot());
        Path text = directory.resolve("legacy.txt");
        Path meta = directory.resolve("legacy.meta.properties");
        Files.setPosixFilePermissions(
            directory,
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            )
        );
        Files.setPosixFilePermissions(
            text,
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
            )
        );
        Files.setPosixFilePermissions(meta, Files.getPosixFilePermissions(text));

        assertEquals(1, store.loadAll().size());

        assertEquals(PRIVATE_DIRECTORY, Files.getPosixFilePermissions(directory));
        assertEquals(PRIVATE_FILE, Files.getPosixFilePermissions(text));
        assertEquals(PRIVATE_FILE, Files.getPosixFilePermissions(meta));
    }

    @Test
    void ignoresSnapshotSymlinksWithoutFollowingOrChangingTheirTargets() throws IOException {
        assumePosix();
        Path directory = tempDir.resolve("recovery");
        Files.createDirectories(directory);
        Path externalText = tempDir.resolve("external.txt");
        Path externalMeta = tempDir.resolve("external.properties");
        Files.writeString(externalText, "secret", StandardCharsets.UTF_8);
        Files.writeString(externalMeta, "dirty=true", StandardCharsets.UTF_8);
        Set<PosixFilePermission> externalPermissions = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ
        );
        Files.setPosixFilePermissions(externalText, externalPermissions);
        Files.setPosixFilePermissions(externalMeta, externalPermissions);
        Files.createSymbolicLink(directory.resolve("evil.txt"), externalText);
        Files.createSymbolicLink(directory.resolve("evil.meta.properties"), externalMeta);

        RecoveryStore store = new RecoveryStore(directory);

        assertEquals(List.of(), store.loadAll());
        assertEquals(externalPermissions, Files.getPosixFilePermissions(externalText));
        assertEquals(externalPermissions, Files.getPosixFilePermissions(externalMeta));
    }

    @Test
    void refusesSymbolicLinkAsRecoveryDirectory() throws IOException {
        assumePosix();
        Path targetDirectory = tempDir.resolve("external");
        Path recoveryLink = tempDir.resolve("recovery");
        Files.createDirectories(targetDirectory);
        Files.createSymbolicLink(recoveryLink, targetDirectory);

        RecoveryStore store = new RecoveryStore(recoveryLink);

        assertThrows(IOException.class, () -> store.save("entry", snapshot()));
        assertTrue(Files.isSymbolicLink(recoveryLink));
        try (var entries = Files.list(targetDirectory)) {
            assertFalse(entries.findAny().isPresent());
        }
    }

    private void assumePosix() throws IOException {
        assumeTrue(
            Files.getFileStore(tempDir).supportsFileAttributeView("posix"),
            "POSIX file attributes are not supported"
        );
    }

    private static List<Path> temporaryFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files
                .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                .toList();
        }
    }

    private static RecoverySnapshot snapshot() {
        return new RecoverySnapshot(
            List.of("first", "second"),
            new Position(1, 3),
            new ViewportState(1, 2, 3, 4),
            new Position(0, 2),
            true,
            Path.of("/tmp/example.txt"),
            StandardCharsets.UTF_8,
            EditorState.LINE_SEPARATOR_CRLF,
            Instant.parse("2026-07-24T00:00:00Z")
        );
    }
}
