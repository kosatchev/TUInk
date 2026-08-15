package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.BomKind;
import dev.tuink.core.DocumentFileMetadata;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.SaveDisposition;
import dev.tuink.core.ViewportState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
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

        Path meta = directory.resolve("session01.meta.properties");
        Path text = currentText(directory, "session01");
        assertEquals(PRIVATE_DIRECTORY, Files.getPosixFilePermissions(directory));
        assertEquals(PRIVATE_FILE, Files.getPosixFilePermissions(text));
        assertEquals(PRIVATE_FILE, Files.getPosixFilePermissions(meta));
        assertEquals(List.of(), temporaryFiles(directory));

        List<RecoveryStore.Entry> entries = store.loadAll().entries();
        assertEquals(1, entries.size());
        assertEquals("session01", entries.get(0).id());
        assertEquals(snapshot, entries.get(0).snapshot());
    }

    @Test
    void recoveryRoundTripRetainsExpectedAbsentSaveIntent() throws IOException {
        Path directory = tempDir.resolve("recovery");
        Path expectedAbsent = tempDir.resolve("new.txt");
        RecoverySnapshot snapshot = new RecoverySnapshot(
            List.of("unsaved"),
            new Position(0, 0),
            new ViewportState(0, 0, 0, 0),
            null,
            true,
            expectedAbsent,
            SaveDisposition.CREATE_NEW,
            DocumentFileMetadata.newDocument(1),
            Instant.parse("2026-07-24T00:00:00Z")
        );
        RecoveryStore store = new RecoveryStore(directory);

        store.save("expected-absent", snapshot);

        assertEquals(snapshot, store.loadAll().entries().get(0).snapshot());
    }

    @Test
    void generationMetadataIsTheAtomicCommitPoint() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = new RecoveryStore(directory);
        RecoverySnapshot original = snapshot();
        store.save("session", original);
        Path originalText = currentText(directory, "session");

        Path orphan = directory.resolve("session.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.txt");
        Files.writeString(orphan, "uncommitted", StandardCharsets.UTF_8);
        assertEquals(original, store.loadAll().entries().get(0).snapshot());

        RecoverySnapshot replacement = new RecoverySnapshot(
            List.of("replacement"),
            new Position(0, 3),
            new ViewportState(0, 0, 0, 0),
            null,
            true,
            null,
            DocumentFileMetadata.newDocument(1),
            Instant.parse("2026-07-25T00:00:00Z")
        );
        store.save("session", replacement);

        assertEquals(replacement, store.loadAll().entries().get(0).snapshot());
        assertFalse(Files.exists(originalText));
        assertTrue(Files.exists(orphan));
        assertFalse(currentText(directory, "session").equals(orphan));
    }

    @Test
    void deleteRemovesEveryOwnedGenerationWithoutFollowingLinks() throws IOException {
        assumePosix();
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = new RecoveryStore(directory);
        store.save("session", snapshot());
        Path current = currentText(directory, "session");
        Path orphan = directory.resolve("session.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.txt");
        Files.writeString(orphan, "orphan", StandardCharsets.UTF_8);
        Path external = tempDir.resolve("external.txt");
        Files.writeString(external, "keep", StandardCharsets.UTF_8);
        Path ownedLink = directory.resolve("session.cccccccccccccccccccccccccccccccc.txt");
        Files.createSymbolicLink(ownedLink, external);

        store.delete("session");

        assertFalse(Files.exists(current));
        assertFalse(Files.exists(orphan));
        assertFalse(Files.isSymbolicLink(ownedLink));
        assertFalse(Files.exists(directory.resolve("session.meta.properties")));
        assertEquals("keep", Files.readString(external));
    }

    @Test
    void partialDeleteLeavesARetryableMarkerInsteadOfDanglingLiveMetadata() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = new RecoveryStore(directory);
        store.save("session", snapshot());
        Path blocker = directory.resolve("session.dddddddddddddddddddddddddddddddd.txt");
        Files.createDirectory(blocker);
        Path child = blocker.resolve("keep-delete-pending");
        Files.writeString(child, "blocked", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> store.delete("session"));

        assertFalse(Files.exists(directory.resolve("session.meta.properties")));
        assertTrue(Files.exists(directory.resolve("session.delete.properties")));
        RecoveryStore.LoadResult pending = store.loadAll();
        assertEquals(List.of(), pending.entries());
        assertEquals(1, pending.skipped().size());
        assertTrue(pending.skipped().get(0).reason().contains("pending deletion cleanup failed"));

        Files.delete(child);
        Files.delete(blocker);
        store.delete("session");

        assertFalse(Files.exists(directory.resolve("session.delete.properties")));
        assertEquals(List.of(), store.loadAll().entries());
        assertEquals(List.of(), store.loadAll().skipped());
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

        assertEquals(List.of(), store.loadAll().entries());
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

    @Test
    void skipsMarkerlessSnapshotAndLeavesFilesUnchanged() throws IOException {
        Path directory = tempDir.resolve("recovery");
        Files.createDirectories(directory);
        Path text = directory.resolve("old.txt");
        Path meta = directory.resolve("old.meta.properties");
        String originalText = "unsaved work";
        String originalMeta = "dirty=true\n";
        Files.writeString(text, originalText);
        Files.writeString(meta, originalMeta);

        RecoveryStore.LoadResult result = new RecoveryStore(directory).loadAll();

        assertEquals(List.of(), result.entries());
        assertEquals(1, result.skipped().size());
        assertEquals("old", result.skipped().get(0).id());
        assertEquals(originalText, Files.readString(text));
        assertEquals(originalMeta, Files.readString(meta));
    }

    @Test
    void refusesToOverwriteIncompatibleSnapshot() throws IOException {
        Path directory = tempDir.resolve("recovery");
        Files.createDirectories(directory);
        Path text = directory.resolve("session.txt");
        Path meta = directory.resolve("session.meta.properties");
        Files.writeString(text, "old text");
        Files.writeString(meta, "format=1\n");

        RecoveryStore store = new RecoveryStore(directory);

        assertThrows(IOException.class, () -> store.save("session", snapshot()));
        assertEquals("old text", Files.readString(text));
        assertEquals("format=1\n", Files.readString(meta));
    }

    @Test
    void rejectsPreviousSchemaAndLeavesItUnchanged() throws IOException {
        Path directory = tempDir.resolve("recovery");
        Files.createDirectories(directory);
        Path text = directory.resolve("old.txt");
        Path meta = directory.resolve("old.meta.properties");
        String originalText = "unsaved work";
        String originalMeta = "format=1\n";
        Files.writeString(text, originalText);
        Files.writeString(meta, originalMeta);

        RecoveryStore.LoadResult result = new RecoveryStore(directory).loadAll();

        assertEquals(List.of(), result.entries());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).reason().contains("unsupported or missing format marker"));
        assertEquals(originalText, Files.readString(text));
        assertEquals(originalMeta, Files.readString(meta));
    }

    @Test
    void rejectsInvalidCurrentFormatValues() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = new RecoveryStore(directory);
        store.save("session", snapshot());
        Path meta = directory.resolve("session.meta.properties");
        String current = Files.readString(meta);
        Files.writeString(meta, current.replace("cursor.line=1", "cursor.line=-1"));

        RecoveryStore.LoadResult result = store.loadAll();

        assertEquals(List.of(), result.entries());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).reason().contains("must not be negative"));
    }

    @Test
    void rejectsCoordinatesOutsideRecoveryDocument() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = new RecoveryStore(directory);
        store.save("session", snapshot());
        Path meta = directory.resolve("session.meta.properties");
        String current = Files.readString(meta);
        Files.writeString(meta, current.replace("cursor.line=1", "cursor.line=2"));

        RecoveryStore.LoadResult result = store.loadAll();

        assertEquals(List.of(), result.entries());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).reason().contains("cursor is outside recovery document"));
    }

    @Test
    void rejectsPersistedCursorInsideASurrogatePair() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = new RecoveryStore(directory);
        RecoverySnapshot unicode = new RecoverySnapshot(
            List.of("A😀B"),
            new Position(0, 1),
            new ViewportState(0, 0, 0, 0),
            null,
            true,
            null,
            DocumentFileMetadata.newDocument(1),
            Instant.parse("2026-07-24T00:00:00Z")
        );
        store.save("unicode", unicode);
        Path meta = directory.resolve("unicode.meta.properties");
        String current = Files.readString(meta);
        Files.writeString(meta, current.replace("cursor.col=1", "cursor.col=2"));

        RecoveryStore.LoadResult result = store.loadAll();

        assertEquals(List.of(), result.entries());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).reason().contains("splits a UTF-16 surrogate pair"));
    }

    @Test
    void rejectsSeparatorMetadataThatDoesNotMatchRecoveryText() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = new RecoveryStore(directory);
        store.save("session", snapshot());
        Path meta = directory.resolve("session.meta.properties");
        String current = Files.readString(meta);
        Files.writeString(
            meta,
            current.replace("lineSeparators=CRLF,LF", "lineSeparators=CRLF,LF,CR")
        );

        RecoveryStore.LoadResult result = store.loadAll();

        assertEquals(List.of(), result.entries());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).reason().contains("does not match recovery text line count"));
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

    private static Path currentText(Path directory, String id) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(directory.resolve(id + ".meta.properties"))) {
            properties.load(in);
        }
        return directory.resolve(id + "." + properties.getProperty("textGeneration") + ".txt");
    }

    private static RecoverySnapshot snapshot() {
        return new RecoverySnapshot(
            List.of("first", "second", ""),
            new Position(1, 3),
            new ViewportState(1, 2, 3, 4),
            new Position(0, 2),
            true,
            Path.of("/tmp/example.txt"),
            new DocumentFileMetadata(
                StandardCharsets.UTF_8,
                BomKind.UTF_8,
                EditorState.LINE_SEPARATOR_CRLF,
                List.of(EditorState.LINE_SEPARATOR_CRLF, EditorState.LINE_SEPARATOR_LF)
            ),
            Instant.parse("2026-07-24T00:00:00Z")
        );
    }
}
