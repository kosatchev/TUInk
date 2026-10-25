package dev.tuink.tui.flow;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorState;
import dev.tuink.storage.jvm.FileBaseline;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.core.TabSession;
import dev.tuink.core.ViewportState;
import dev.tuink.render.TerminalEditorTextLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
    private final List<RecoveryStore> stores = new ArrayList<>();

    @AfterEach
    void closeStores() throws IOException {
        IOException failure = null;
        for (RecoveryStore store : stores) {
            try {
                store.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private RecoveryStore newStore(Path directory) {
        RecoveryStore store = new RecoveryStore(directory);
        stores.add(store);
        return store;
    }

    @Test
    void savesAndLoadsSnapshotWithPrivatePermissions() throws IOException {
        assumePosix();
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
        RecoverySnapshot snapshot = snapshot();

        store.save("Session 01", snapshot);

        Path meta = directory.resolve("session01.meta.properties");
        Path text = currentText(directory, "session01");
        assertEquals(PRIVATE_DIRECTORY, Files.getPosixFilePermissions(directory));
        assertEquals(PRIVATE_FILE, Files.getPosixFilePermissions(text));
        assertEquals(PRIVATE_FILE, Files.getPosixFilePermissions(meta));
        assertEquals(PRIVATE_FILE, Files.getPosixFilePermissions(directory.resolve("session01.lock")));
        assertEquals(List.of(), temporaryFiles(directory));

        List<RecoveryStore.Entry> entries = store.loadAll().entries();
        assertEquals(1, entries.size());
        assertEquals("session01", entries.get(0).id());
        assertEquals(snapshot, entries.get(0).snapshot());
    }

    @Test
    void recoveryDirectoryResolvesParentSymlinksBeforeDotDotAndSharesCanonicalClaims() throws IOException {
        assumePosix();
        Path real = Files.createDirectories(tempDir.resolve("real/deep")).getParent();
        Path link = Files.createSymbolicLink(tempDir.resolve("parentlink"), real.resolve("deep"));
        Path raw = link.resolve("../recovery");
        Path physical = real.resolve("recovery");
        Path lexical = Files.createDirectory(tempDir.resolve("recovery"));
        Path untouched = Files.writeString(lexical.resolve("keep"), "leave this directory untouched");
        RecoveryStore owner = newStore(raw);
        RecoverySnapshot expected = snapshot();

        owner.save("session", expected);

        assertTrue(Files.exists(physical.resolve("session.meta.properties")));
        assertTrue(Files.isSameFile(raw, physical));
        RecoveryStore peer = newStore(physical);
        assertEquals(List.of(), peer.loadAll().entries());
        assertThrows(IOException.class, () -> peer.save("session", expected));
        owner.close();
        assertEquals(List.of(new RecoveryStore.Entry("session", expected)), peer.loadAll().entries());
        assertEquals("leave this directory untouched", Files.readString(untouched));
        assertFalse(Files.exists(lexical.resolve("session.meta.properties")));
        assertFalse(Files.exists(lexical.resolve("session.lock")));
    }

    @Test
    void absentDirectoryStaysLazyUntilTheFirstSavePinsItsCurrentPhysicalParent() throws IOException {
        assumePosix();
        Path firstParent = Files.createDirectory(tempDir.resolve("first"));
        Path nextParent = Files.createDirectory(tempDir.resolve("next"));
        Path alias = Files.createSymbolicLink(tempDir.resolve("parent"), firstParent.getFileName());
        Path configured = alias.resolve("recovery");
        RecoveryStore store = newStore(configured);

        assertEquals(configured, store.directory());
        assertEquals(new RecoveryStore.LoadResult(List.of(), List.of()), store.loadAll());
        store.delete("absent");
        store.release("absent");
        assertEquals(configured, store.directory());
        assertFalse(Files.exists(firstParent.resolve("recovery")));

        Files.delete(alias);
        Files.createSymbolicLink(alias, nextParent.getFileName());
        store.save("session", snapshot());

        assertEquals(nextParent.resolve("recovery").toRealPath(), store.directory());
        assertTrue(Files.exists(nextParent.resolve("recovery/session.meta.properties")));
        assertFalse(Files.exists(firstParent.resolve("recovery")));
    }

    @Test
    void savesNewClaimsAndDeletionStayWithThePhysicalDirectoryAfterParentRetarget() throws IOException {
        assumePosix();
        Path firstParent = Files.createDirectory(tempDir.resolve("first"));
        Path nextParent = Files.createDirectory(tempDir.resolve("next"));
        Path alias = Files.createSymbolicLink(tempDir.resolve("parent"), firstParent.getFileName());
        Path configured = alias.resolve("recovery");
        Path physical = firstParent.resolve("recovery");
        RecoveryStore owner = newStore(configured);
        owner.save("session", snapshot());
        RecoveryStore contender = newStore(configured);
        assertTrue(contender.loadAll().entries().isEmpty());
        assertEquals(physical.toRealPath(), contender.directory(), "a busy entry must not delay directory pinning");
        Path previousText = currentText(physical, "session");

        Files.delete(alias);
        Files.createSymbolicLink(alias, nextParent.getFileName());
        RecoveryStore otherLocation = newStore(configured);
        otherLocation.save("session", snapshot());
        Path otherMetadata = nextParent.resolve("recovery/session.meta.properties");
        byte[] otherBytes = Files.readAllBytes(otherMetadata);

        owner.save("session", snapshot());
        contender.save("new-entry", snapshot());

        assertEquals(physical.toRealPath(), owner.directory());
        assertFalse(previousText.equals(currentText(physical, "session")));
        assertFalse(Files.exists(previousText));
        assertTrue(Files.exists(physical.resolve("new-entry.meta.properties")));
        assertFalse(Files.exists(nextParent.resolve("recovery/new-entry.meta.properties")));
        assertArrayEquals(otherBytes, Files.readAllBytes(otherMetadata));
        RecoveryStore physicalPeer = newStore(physical);
        assertTrue(physicalPeer.loadAll().entries().isEmpty(), "both old and new claims must remain exclusive");
        assertThrows(IOException.class, () -> physicalPeer.save("session", snapshot()));
        assertThrows(IOException.class, () -> physicalPeer.delete("new-entry"));
        assertEquals(List.of("session"), owner.loadAll().entries().stream().map(RecoveryStore.Entry::id).toList());

        owner.delete("session");

        assertFalse(Files.exists(physical.resolve("session.meta.properties")));
        assertArrayEquals(otherBytes, Files.readAllBytes(otherMetadata));
        physicalPeer.save("session", snapshot());
        contender.close();
        assertEquals(List.of("new-entry"), owner.loadAll().entries().stream().map(RecoveryStore.Entry::id).toList());
    }

    @Test
    void startupLoadAndReleaseKeepTheOriginalPhysicalClaimsAfterParentRetarget() throws IOException {
        assumePosix();
        Path firstParent = Files.createDirectory(tempDir.resolve("first"));
        Path nextParent = Files.createDirectory(tempDir.resolve("next"));
        Path physical = firstParent.resolve("recovery");
        RecoveryStore writer = newStore(physical);
        writer.save("session", snapshot());
        writer.close();
        Path alias = Files.createSymbolicLink(tempDir.resolve("parent"), firstParent.getFileName());
        RecoveryStore startup = newStore(alias.resolve("recovery"));
        List<RecoveryStore.Entry> entries = startup.loadAll().entries();
        assertEquals(List.of(new RecoveryStore.Entry("session", snapshot())), entries);
        assertEquals(physical.toRealPath(), startup.directory());
        RecoveryStore physicalPeer = newStore(physical);

        Files.delete(alias);
        Files.createSymbolicLink(alias, nextParent.getFileName());
        RecoveryStore otherLocation = newStore(alias.resolve("recovery"));
        otherLocation.save("session", snapshot());
        Path otherMetadata = nextParent.resolve("recovery/session.meta.properties");
        byte[] otherBytes = Files.readAllBytes(otherMetadata);
        assertTrue(physicalPeer.loadAll().entries().isEmpty());

        startup.release("session");

        assertEquals(entries, physicalPeer.loadAll().entries());
        assertThrows(IOException.class, () -> startup.save("session", snapshot()));
        assertThrows(IOException.class, () -> startup.delete("session"));
        assertArrayEquals(otherBytes, Files.readAllBytes(otherMetadata));
        startup.close();
        assertEquals(List.of(new RecoveryStore.Entry("session", snapshot())), otherLocation.loadAll().entries());
    }

    @Test
    void rejectedFinalDirectorySymlinkDoesNotPinTheStoreBeforeAValidInitialization() throws IOException {
        assumePosix();
        Path firstParent = Files.createDirectory(tempDir.resolve("first"));
        Path nextParent = Files.createDirectory(tempDir.resolve("next"));
        Path external = Files.createDirectory(tempDir.resolve("external"));
        Path untouched = Files.writeString(external.resolve("keep"), "untouched");
        Files.createSymbolicLink(firstParent.resolve("recovery"), external);
        Path alias = Files.createSymbolicLink(tempDir.resolve("parent"), firstParent.getFileName());
        Path configured = alias.resolve("recovery");
        RecoveryStore store = newStore(configured);

        assertThrows(IOException.class, store::loadAll);
        assertThrows(IOException.class, () -> store.save("session", snapshot()));
        assertThrows(IOException.class, () -> store.delete("session"));
        assertEquals(configured, store.directory());
        assertEquals("untouched", Files.readString(untouched));
        try (var contents = Files.list(external)) {
            assertEquals(List.of(untouched), contents.toList());
        }

        Files.delete(alias);
        Files.createSymbolicLink(alias, nextParent.getFileName());
        store.save("session", snapshot());

        assertEquals(nextParent.resolve("recovery").toRealPath(), store.directory());
        assertTrue(Files.isSymbolicLink(firstParent.resolve("recovery")));
        assertEquals("untouched", Files.readString(untouched));
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
            JvmDocumentStorage.resource(expectedAbsent),
            SaveDisposition.CREATE_NEW,
            DocumentFileMetadata.newDocument(1),
            null,
            Instant.parse("2026-07-24T00:00:00Z")
        );
        RecoveryStore store = newStore(directory);

        store.save("expected-absent", snapshot);

        assertEquals(snapshot, store.loadAll().entries().get(0).snapshot());
        Properties metadata = readMetadata(directory.resolve("expected-absent.meta.properties"));
        assertEquals("false", metadata.getProperty("fileBaseline.present"));
        assertEquals("", metadata.getProperty("fileBaseline.target"));
        assertEquals("", metadata.getProperty("fileBaseline.sha256"));
        assertEquals("", metadata.getProperty("fileBaseline.byteSize"));
    }

    @Test
    void recoveryRoundTripRetainsFileBaselineAndRejectsExternalChanges() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoverySnapshot snapshot = openedFileSnapshot();
        assertNotNull(JvmDocumentStorage.baselineOf(snapshot.fileBaseline()));
        RecoveryStore store = newStore(directory);
        store.save("session", snapshot);
        Properties metadata = readMetadata(directory.resolve("session.meta.properties"));
        assertEquals("8", metadata.getProperty("format"));
        assertEquals("true", metadata.getProperty("fileBaseline.present"));
        assertEquals(JvmDocumentStorage.baselineOf(snapshot.fileBaseline()).target().toUri().toASCIIString(), metadata.getProperty("fileBaseline.target"));
        assertEquals(JvmDocumentStorage.baselineOf(snapshot.fileBaseline()).sha256(), metadata.getProperty("fileBaseline.sha256"));
        assertEquals(Long.toString(JvmDocumentStorage.baselineOf(snapshot.fileBaseline()).byteSize()), metadata.getProperty("fileBaseline.byteSize"));

        Files.writeString(JvmDocumentStorage.pathOf(snapshot.currentResource()), "external edit", StandardCharsets.UTF_8);
        RecoverySnapshot recovered = store.loadAll().entries().get(0).snapshot();
        assertEquals(snapshot, recovered);
        TabSession restored = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        restored.engine().restoreRecoverySnapshot(recovered);

        assertThrows(IOException.class, restored.engine()::save);
        assertEquals("external edit", Files.readString(JvmDocumentStorage.pathOf(snapshot.currentResource())));
        assertTrue(restored.state().isDirty());
    }

    @Test
    void formatSevenWithoutAByteCountStillRestoresAndCanSaveAnUnchangedFile() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoverySnapshot snapshot = openedFileSnapshot();
        RecoveryStore store = newStore(directory);
        store.save("session", snapshot);
        Path metadata = directory.resolve("session.meta.properties");
        Properties fields = readMetadata(metadata);
        fields.setProperty("format", "7");
        fields.remove("fileBaseline.byteSize");
        writeMetadata(metadata, fields);
        byte[] before = Files.readAllBytes(metadata);

        RecoverySnapshot recovered = store.loadAll().entries().getFirst().snapshot();

        assertEquals(new FileBaseline(JvmDocumentStorage.baselineOf(snapshot.fileBaseline()).target(), JvmDocumentStorage.baselineOf(snapshot.fileBaseline()).sha256()),
            JvmDocumentStorage.baselineOf(recovered.fileBaseline()));
        assertEquals(snapshot.lines(), recovered.lines());
        assertArrayEquals(before, Files.readAllBytes(metadata), "discovery must preserve old metadata");
        TabSession restored = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        restored.engine().restoreRecoverySnapshot(recovered);
        restored.engine().save();
        assertFalse(restored.state().isDirty());
        assertTrue(JvmDocumentStorage.baselineOf(restored.model().fileBaseline()).byteSize() >= 0);
    }

    @Test
    void malformedBaselineByteCountsAreRejectedWithoutReplacingRecovery() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
        store.save("session", openedFileSnapshot());
        Path metadata = directory.resolve("session.meta.properties");
        Path text = currentText(directory, "session");
        Properties valid = readMetadata(metadata);
        for (String size : List.of("", "-2", "+1", "01", "1.0", "9223372036854775808")) {
            Properties malformed = new Properties();
            malformed.putAll(valid);
            malformed.setProperty("fileBaseline.byteSize", size);
            writeMetadata(metadata, malformed);
            assertTrue(assertRejectedAndPreserved(store, metadata, text).contains("fileBaseline.byteSize"));
        }
    }

    @Test
    void restoredExistingFileWithoutKnownBaselineCannotOverwriteTheCurrentDiskContent() throws IOException {
        RecoverySnapshot original = openedFileSnapshot();
        RecoverySnapshot withoutBaseline = new RecoverySnapshot(
            original.lines(),
            original.cursor(),
            original.viewport(),
            original.selectionAnchor(),
            original.dirty(),
            original.currentResource(),
            original.saveDisposition(),
            original.fileMetadata(),
            null,
            original.createdAt()
        );
        RecoveryStore store = newStore(tempDir.resolve("recovery"));
        store.save("session", withoutBaseline);
        RecoverySnapshot recovered = store.loadAll().entries().get(0).snapshot();
        assertEquals(withoutBaseline, recovered);
        TabSession restored = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        restored.engine().restoreRecoverySnapshot(recovered);

        assertThrows(IOException.class, restored.engine()::save);
        assertEquals("original", Files.readString(JvmDocumentStorage.pathOf(original.currentResource())));
        assertTrue(restored.state().isDirty());
    }

    @Test
    void rejectsNoncanonicalFileBaselineDigestsAndPreservesSnapshots() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
        store.save("session", openedFileSnapshot());
        Path metadata = directory.resolve("session.meta.properties");
        Path text = currentText(directory, "session");
        Properties valid = readMetadata(metadata);

        for (String digest : List.of("", "a".repeat(63), "f".repeat(65), "A".repeat(64), "g".repeat(64))) {
            Properties malformed = new Properties();
            malformed.putAll(valid);
            malformed.setProperty("fileBaseline.sha256", digest);
            writeMetadata(metadata, malformed);

            assertTrue(assertRejectedAndPreserved(store, metadata, text).contains("fileBaseline.sha256"));
        }
    }

    @Test
    void rejectsRelativeAndUnnormalizedFileBaselineTargets() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
        store.save("session", openedFileSnapshot());
        Path metadata = directory.resolve("session.meta.properties");
        Path text = currentText(directory, "session");
        Properties valid = readMetadata(metadata);

        for (String target : List.of("relative.txt", tempDir.resolve(".").resolve("document.txt").toString())) {
            Properties malformed = new Properties();
            malformed.putAll(valid);
            malformed.setProperty("fileBaseline.target", target);
            writeMetadata(metadata, malformed);

            assertTrue(assertRejectedAndPreserved(store, metadata, text).contains("canonical ASCII file URI"));
        }
    }

    @Test
    void rejectsInconsistentFileBaselinePresenceAndSaveIntent() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
        store.save("session", openedFileSnapshot());
        Path metadata = directory.resolve("session.meta.properties");
        Path text = currentText(directory, "session");
        Properties valid = readMetadata(metadata);
        List<Map<String, String>> changes = List.of(
            Map.of("fileBaseline.present", "false"),
            Map.of("fileBaseline.present", "false", "fileBaseline.sha256", ""),
            Map.of("fileBaseline.present", "false", "fileBaseline.target", ""),
            Map.of("fileBaseline.target", ""),
            Map.of("fileBaseline.present", "TRUE"),
            Map.of("saveDisposition", "CREATE_NEW"),
            Map.of("currentFile.present", "false", "currentFile", "", "saveDisposition", "CREATE_NEW")
        );

        for (Map<String, String> change : changes) {
            Properties malformed = new Properties();
            malformed.putAll(valid);
            malformed.putAll(change);
            writeMetadata(metadata, malformed);

            assertRejectedAndPreserved(store, metadata, text);
        }
    }

    @Test
    void rejectsMissingAndUnknownFileBaselineFields() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
        store.save("session", openedFileSnapshot());
        Path metadata = directory.resolve("session.meta.properties");
        Path text = currentText(directory, "session");
        Properties valid = readMetadata(metadata);

        for (String key : List.of("fileBaseline.present", "fileBaseline.target", "fileBaseline.sha256", "fileBaseline.byteSize")) {
            Properties malformed = new Properties();
            malformed.putAll(valid);
            malformed.remove(key);
            writeMetadata(metadata, malformed);

            assertTrue(assertRejectedAndPreserved(store, metadata, text).contains("schema mismatch"));
        }
        valid.setProperty("fileBaseline.extra", "unknown");
        writeMetadata(metadata, valid);
        assertTrue(assertRejectedAndPreserved(store, metadata, text).contains("schema mismatch"));
    }

    @Test
    void generationMetadataIsTheAtomicCommitPoint() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
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
            SaveDisposition.CREATE_NEW,
            DocumentFileMetadata.newDocument(1),
            null,
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
        RecoveryStore store = newStore(directory);
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
    void ignoresUnownedFilesWhenSavingAndDeletingSnapshots() throws IOException {
        Path directory = tempDir.resolve("recovery");
        Files.createDirectories(directory);
        Path unownedText = directory.resolve("session.txt");
        Path unrelated = directory.resolve("notes.txt");
        Files.writeString(unownedText, "unowned text", StandardCharsets.UTF_8);
        Files.writeString(unrelated, "unrelated text", StandardCharsets.UTF_8);
        RecoveryStore store = newStore(directory);

        store.save("session", snapshot());

        assertEquals(snapshot(), store.loadAll().entries().get(0).snapshot());
        store.delete("session");

        assertEquals("unowned text", Files.readString(unownedText));
        assertEquals("unrelated text", Files.readString(unrelated));
        assertEquals(List.of(), store.loadAll().entries());
    }

    @Test
    void partialDeleteLeavesARetryableMarkerInsteadOfDanglingLiveMetadata() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
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
        RecoveryStore store = newStore(directory);
        store.save("evil", snapshot());
        Path snapshotText = currentText(directory, "evil");
        Path snapshotMeta = directory.resolve("evil.meta.properties");
        Path externalText = tempDir.resolve("external.txt");
        Path externalMeta = tempDir.resolve("external.properties");
        Files.writeString(externalText, "secret", StandardCharsets.UTF_8);
        Files.copy(snapshotMeta, externalMeta);
        String originalMetadata = Files.readString(externalMeta);
        Set<PosixFilePermission> externalPermissions = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ
        );
        Files.setPosixFilePermissions(externalText, externalPermissions);
        Files.setPosixFilePermissions(externalMeta, externalPermissions);
        Files.delete(snapshotText);
        Files.delete(snapshotMeta);
        Files.createSymbolicLink(snapshotText, externalText);
        Files.createSymbolicLink(snapshotMeta, externalMeta);

        assertEquals(List.of(), store.loadAll().entries());
        assertEquals("secret", Files.readString(externalText));
        assertEquals(originalMetadata, Files.readString(externalMeta));
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

        RecoveryStore store = newStore(recoveryLink);

        assertThrows(IOException.class, () -> store.save("entry", snapshot()));
        assertTrue(Files.isSymbolicLink(recoveryLink));
        try (var entries = Files.list(targetDirectory)) {
            assertFalse(entries.findAny().isPresent());
        }
    }

    @Test
    void skipsMarkerlessSnapshotAndLeavesFilesUnchanged() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
        store.save("session", snapshot());
        Path text = currentText(directory, "session");
        Path metadata = directory.resolve("session.meta.properties");
        Properties markerless = readMetadata(metadata);
        markerless.remove("format");
        writeMetadata(metadata, markerless);

        assertTrue(assertRejectedAndPreserved(store, metadata, text).contains("unsupported or missing format marker"));
    }

    @Test
    void refusesToOverwriteIncompatibleSnapshot() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
        store.save("session", snapshot());
        Path text = currentText(directory, "session");
        Path metadata = directory.resolve("session.meta.properties");
        Properties invalid = readMetadata(metadata);
        invalid.setProperty("dirty", "invalid");
        writeMetadata(metadata, invalid);

        assertTrue(assertRejectedAndPreserved(store, metadata, text).contains("dirty must be true or false"));
    }

    @Test
    void rejectsMalformedCurrentMetadataAsAnIoErrorWithoutOverwritingSnapshotBytes() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
        store.save("session", snapshot());
        Path text = currentText(directory, "session");
        byte[] originalText = Files.readAllBytes(text);
        Path metadata = directory.resolve("session.meta.properties");
        String current = Files.readString(metadata, StandardCharsets.ISO_8859_1);
        assertTrue(current.contains("dirty=true"));
        byte[] malformedMetadata = current.replace("dirty=true", "dirty=\\u12xz")
            .getBytes(StandardCharsets.ISO_8859_1);
        Files.write(metadata, malformedMetadata);

        IOException failure = assertThrows(IOException.class, () -> store.save("session", snapshot()));

        assertTrue(failure.getMessage().contains(metadata.toString()));
        assertTrue(failure.getMessage().contains("Malformed recovery metadata"));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        RecoveryStore.LoadResult result = store.loadAll();
        assertEquals(List.of(), result.entries());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).reason().contains("Malformed recovery metadata"));
        assertArrayEquals(malformedMetadata, Files.readAllBytes(metadata));
        assertArrayEquals(originalText, Files.readAllBytes(text));
    }

    @Test
    void rejectsUnknownFormatMarkerAndLeavesSnapshotUnchanged() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
        store.save("session", snapshot());
        Path text = currentText(directory, "session");
        Path metadata = directory.resolve("session.meta.properties");
        Properties unsupported = readMetadata(metadata);
        unsupported.setProperty("format", "unknown");
        writeMetadata(metadata, unsupported);

        assertTrue(assertRejectedAndPreserved(store, metadata, text).contains("unsupported or missing format marker"));
    }

    @Test
    void rejectsInvalidCurrentFormatValues() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore store = newStore(directory);
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
        RecoveryStore store = newStore(directory);
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
        RecoveryStore store = newStore(directory);
        RecoverySnapshot unicode = new RecoverySnapshot(
            List.of("A😀B"),
            new Position(0, 1),
            new ViewportState(0, 0, 0, 0),
            null,
            true,
            null,
            SaveDisposition.CREATE_NEW,
            DocumentFileMetadata.newDocument(1),
            null,
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
        RecoveryStore store = newStore(directory);
        store.save("session", snapshot());
        Path meta = directory.resolve("session.meta.properties");
        Properties properties = new Properties();
        try (var input = Files.newInputStream(meta)) {
            properties.load(input);
        }
        properties.setProperty("lineSeparators", RecoverySeparators.encode(List.of("\r\n", "\n", "\r")));
        try (var output = Files.newOutputStream(meta)) {
            properties.store(output, "mismatched separator count");
        }

        RecoveryStore.LoadResult result = store.loadAll();

        assertEquals(List.of(), result.entries());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).reason().contains("does not match recovery text line count"));
    }

    @Test
    void activeSnapshotCannotBeLoadedOverwrittenOrDeletedByAnotherStore() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore owner = newStore(directory);
        RecoveryStore peer = newStore(directory);
        RecoverySnapshot original = snapshot();
        owner.save("session", original);
        Path text = currentText(directory, "session");
        String metadata = Files.readString(directory.resolve("session.meta.properties"));

        assertEquals(new RecoveryStore.LoadResult(List.of(), List.of()), peer.loadAll());
        assertThrows(IOException.class, () -> peer.save("session", original));
        assertThrows(IOException.class, () -> peer.delete("session"));
        assertEquals(metadata, Files.readString(directory.resolve("session.meta.properties")));
        assertEquals(text, currentText(directory, "session"));
        assertEquals(original, owner.loadAll().entries().get(0).snapshot());

        owner.close();

        assertTrue(Files.exists(directory.resolve("session.lock")));
        assertEquals(original, peer.loadAll().entries().get(0).snapshot());
        assertEquals(List.of(), newStore(directory).loadAll().entries());
    }

    @Test
    void startupClaimRemainsExclusiveUntilTheClaimingStoreCloses() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore writer = newStore(directory);
        writer.save("session", snapshot());
        writer.close();
        RecoveryStore firstStartup = newStore(directory);
        RecoveryStore secondStartup = newStore(directory);

        List<RecoveryStore.Entry> claimed = firstStartup.loadAll().entries();

        assertEquals(1, claimed.size());
        assertEquals(new RecoveryStore.LoadResult(List.of(), List.of()), secondStartup.loadAll());
        assertThrows(IOException.class, () -> secondStartup.deleteAll(claimed));
        firstStartup.close();
        assertEquals(claimed, secondStartup.loadAll().entries());
    }

    @Test
    void releasingOneStartupClaimImmediatelyOffersItToAnotherOpenStoreWithoutChangingFiles() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore writer = newStore(directory);
        writer.save("session", snapshot());
        writer.save("retained", snapshot());
        writer.close();
        RecoveryStore first = newStore(directory);
        RecoveryStore second = newStore(directory);
        List<RecoveryStore.Entry> claimed = first.loadAll().entries();
        Path text = currentText(directory, "session");
        byte[] textBytes = Files.readAllBytes(text);
        byte[] metadata = Files.readAllBytes(directory.resolve("session.meta.properties"));
        Path lock = directory.resolve("session.lock");
        Object lockFileKey = Files.readAttributes(lock, java.nio.file.attribute.BasicFileAttributes.class).fileKey();

        second.release("session");
        assertTrue(second.loadAll().entries().isEmpty(), "a non-owner must not release someone else's claim");
        first.release("session");

        assertEquals(List.of("session"), second.loadAll().entries().stream().map(RecoveryStore.Entry::id).toList());
        assertArrayEquals(textBytes, Files.readAllBytes(text));
        assertArrayEquals(metadata, Files.readAllBytes(directory.resolve("session.meta.properties")));
        assertTrue(Files.exists(lock));
        assertEquals(lockFileKey,
            Files.readAttributes(lock, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
        assertThrows(IOException.class, () -> first.delete("session"));
        assertEquals(List.of("retained"), first.loadAll().entries().stream().map(RecoveryStore.Entry::id).toList());
        assertEquals(2, claimed.size());
    }

    @Test
    void peerDoesNotCleanAnActiveOwnersPendingDeletion() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore owner = newStore(directory);
        RecoveryStore peer = newStore(directory);
        owner.save("session", snapshot());
        Path blocker = directory.resolve("session.dddddddddddddddddddddddddddddddd.txt");
        Files.createDirectory(blocker);
        Path child = blocker.resolve("block-deletion");
        Files.writeString(child, "blocked");
        assertThrows(IOException.class, () -> owner.delete("session"));
        Files.delete(child);
        Files.delete(blocker);
        Path marker = directory.resolve("session.delete.properties");

        assertEquals(new RecoveryStore.LoadResult(List.of(), List.of()), peer.loadAll());
        assertTrue(Files.exists(marker));

        owner.close();

        assertEquals(new RecoveryStore.LoadResult(List.of(), List.of()), peer.loadAll());
        assertFalse(Files.exists(marker));
        assertTrue(Files.exists(directory.resolve("session.lock")));
    }

    @Test
    void successfulDeleteReleasesTheClaimAndRetainsItsStableSidecar() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore owner = newStore(directory);
        RecoveryStore peer = newStore(directory);
        owner.save("session", snapshot());

        owner.delete("session");

        assertTrue(Files.exists(directory.resolve("session.lock")));
        peer.save("session", snapshot());
        assertEquals(List.of(), owner.loadAll().entries());
        assertThrows(IOException.class, () -> owner.delete("session"));
    }

    @Test
    void failedStartupReadReleasesItsNewClaimForAnotherStore() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore writer = newStore(directory);
        writer.save("session", snapshot());
        writer.close();
        Path metadata = directory.resolve("session.meta.properties");
        String validMetadata = Files.readString(metadata);
        Properties invalid = readMetadata(metadata);
        invalid.setProperty("cursor.line", "-1");
        writeMetadata(metadata, invalid);
        RecoveryStore firstStartup = newStore(directory);

        assertEquals(1, firstStartup.loadAll().skipped().size());
        Files.writeString(metadata, validMetadata);

        assertEquals(snapshot(), newStore(directory).loadAll().entries().get(0).snapshot());
    }

    @Test
    void refusesLockSymlinksWithoutReadingOrChangingTheirTargets() throws IOException {
        assumePosix();
        Path directory = tempDir.resolve("recovery");
        RecoveryStore writer = newStore(directory);
        writer.save("session", snapshot());
        writer.close();
        Path lock = directory.resolve("session.lock");
        Files.deleteIfExists(lock);
        Path external = tempDir.resolve("external.txt");
        Files.writeString(external, "keep");
        Set<PosixFilePermission> permissions = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ
        );
        Files.setPosixFilePermissions(external, permissions);
        Files.createSymbolicLink(lock, external);
        RecoveryStore store = newStore(directory);

        RecoveryStore.LoadResult result = store.loadAll();

        assertEquals(List.of(), result.entries());
        assertEquals(1, result.skipped().size());
        assertThrows(IOException.class, () -> store.save("session", snapshot()));
        assertThrows(IOException.class, () -> store.delete("session"));
        assertEquals("keep", Files.readString(external));
        assertEquals(permissions, Files.getPosixFilePermissions(external));
        assertTrue(Files.isSymbolicLink(lock));
        assertTrue(Files.exists(directory.resolve("session.meta.properties")));
    }

    @Test
    void noncanonicalMetadataFilenameCannotAliasACanonicalClaim() throws IOException {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore writer = newStore(directory);
        writer.save("session", snapshot());
        writer.close();
        Path noncanonical = directory.resolve("session!.meta.properties");
        Files.copy(directory.resolve("session.meta.properties"), noncanonical);

        RecoveryStore.LoadResult result = newStore(directory).loadAll();

        assertEquals(List.of(new RecoveryStore.Entry("session", snapshot())), result.entries());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).reason().contains("noncanonical"));
        assertTrue(Files.exists(noncanonical));
        assertFalse(Files.exists(directory.resolve("session!.lock")));
    }

    @Test
    void closedStoreRejectsOperationsAndCloseIsIdempotent() throws IOException {
        RecoveryStore store = newStore(tempDir.resolve("recovery"));
        store.save("session", snapshot());

        store.close();
        store.close();

        assertThrows(IOException.class, store::loadAll);
        assertThrows(IOException.class, () -> store.save("session", snapshot()));
        assertThrows(IOException.class, () -> store.delete("session"));
        assertThrows(IOException.class, () -> store.deleteAll(List.of()));
        assertTrue(Files.exists(store.directory().resolve("session.meta.properties")));
    }

    @Test
    void rejectedLocalContenderDoesNotReleaseTheOwnersCrossProcessLock() throws Exception {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore owner = newStore(directory);
        RecoveryStore peer = newStore(directory);
        owner.save("session", snapshot());
        assertEquals(List.of(), peer.loadAll().entries());
        assertThrows(IOException.class, () -> peer.save("session", snapshot()));
        assertThrows(IOException.class, () -> peer.delete("session"));

        assertEquals("busy", probeLockInAnotherProcess(directory.resolve("session.lock")));

        owner.close();

        assertEquals("acquired", probeLockInAnotherProcess(directory.resolve("session.lock")));
    }

    @Test
    void lastStoreCloseReclaimsEntrySidecarsForSavedDeletedAndAbsentEntries() throws IOException {
        Path directory = Files.createDirectory(tempDir.resolve("recovery"));
        for (int run = 0; run < 3; run++) {
            try (RecoveryStore store = new RecoveryStore(directory)) {
                store.save("kept-" + run, snapshot());
                store.save("removed-" + run, snapshot());
                store.delete("removed-" + run);
                store.delete("never-saved-" + run);
            }
            try (var files = Files.list(directory)) {
                assertEquals(List.of(".namespace.lock"), files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".lock")).sorted().toList());
            }
        }
        try (RecoveryStore reader = new RecoveryStore(directory)) {
            assertEquals(3, reader.loadAll().entries().size(), "reclaimed sidecars must not remove snapshot data");
        }
    }

    @Test
    void cleanDiscoveryAlsoCollectsSidecarsLeftByEarlierSessions() throws IOException {
        Path directory = Files.createDirectory(tempDir.resolve("recovery"));
        Files.createFile(directory.resolve("orphan.lock"));
        for (int run = 0; run < 2; run++) {
            try (RecoveryStore store = new RecoveryStore(directory)) {
                assertTrue(store.discover().entries().isEmpty());
            }
            try (var files = Files.list(directory)) {
                assertEquals(List.of(".namespace.lock"), files.map(path -> path.getFileName().toString()).toList());
            }
        }
    }

    @Test
    void sharedNamespaceGuardPreservesOtherLocalStoresEntryInodesUntilTheirClose() throws Exception {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore first = newStore(directory);
        RecoveryStore second = newStore(directory);
        first.save("first", snapshot());
        second.save("second", snapshot());
        Path sidecar = directory.resolve("second.lock");
        Object inode = Files.readAttributes(sidecar, java.nio.file.attribute.BasicFileAttributes.class).fileKey();

        first.close();

        assertEquals("busy", probeLockInAnotherProcess(sidecar));
        assertEquals("busy", probeLockInAnotherProcess(directory.resolve(".namespace.lock")));
        assertEquals(inode, Files.readAttributes(sidecar, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
        assertTrue(Files.exists(directory.resolve("first.lock")), "cleanup must defer while any store has entry channels");
        second.close();
        assertFalse(Files.exists(sidecar));
        assertFalse(Files.exists(directory.resolve("first.lock")));
        assertTrue(Files.exists(directory.resolve(".namespace.lock")));
    }

    @Test
    void anotherProcessDefersNamespaceCollectionUntilItsStoreCloses() throws Exception {
        Path directory = tempDir.resolve("recovery");
        RecoveryStore local = newStore(directory);
        local.save("local", snapshot());
        Path ready = tempDir.resolve("peer-ready");
        String classpath = String.join(java.io.File.pathSeparator,
            classLocation(RecoveryNamespacePeer.class), classLocation(RecoveryStore.class),
            classLocation(RecoveryLimits.class), classLocation(RecoverySnapshot.class), classLocation(dev.tuink.storage.jvm.JvmDocumentStorage.class), classLocation(dev.tuink.editor.document.DocumentId.class));
        Process peer = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, RecoveryNamespacePeer.class.getName(), directory.toString(), ready.toString())
            .redirectErrorStream(true).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(ready) && peer.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(ready), () -> {
                try {
                    return "peer must hold its recovery namespace before the local store closes: "
                        + (peer.isAlive() ? "peer still starting" : new String(peer.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException error) {
                    return error.toString();
                }
            });
            Path namespace = directory.resolve(".namespace.lock");
            Object namespaceInode = Files.readAttributes(namespace, java.nio.file.attribute.BasicFileAttributes.class).fileKey();

            local.close();

            assertTrue(Files.exists(directory.resolve("local.lock")));
            assertEquals("busy", probeLockInAnotherProcess(directory.resolve("peer.lock")));
            try (RecoveryStore contender = new RecoveryStore(directory)) {
                assertThrows(IOException.class, () -> contender.save("peer", snapshot()));
            }
            peer.getOutputStream().write(1);
            peer.getOutputStream().flush();
            assertTrue(peer.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, peer.exitValue(), new String(peer.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertFalse(Files.exists(directory.resolve("local.lock")));
            assertFalse(Files.exists(directory.resolve("peer.lock")));
            assertEquals(namespaceInode,
                Files.readAttributes(namespace, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
            try (RecoveryStore reader = new RecoveryStore(directory)) {
                assertEquals(List.of("local", "peer"), reader.loadAll().entries().stream().map(RecoveryStore.Entry::id).toList());
            }
        } finally {
            peer.destroyForcibly();
        }
    }

    private static String classLocation(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static String probeLockInAnotherProcess(Path lock) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path testClasses = Path.of(RecoveryStoreTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process process = new ProcessBuilder(
            java.toString(), "-cp", testClasses.toString(), LockProbe.class.getName(), lock.toString()
        ).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "lock probe did not terminate");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    /** JDK-only subprocess fixture: it deliberately needs no test-runtime dependencies. */
    public static final class LockProbe {
        public static void main(String[] args) throws IOException {
            try (FileChannel channel = FileChannel.open(
                Path.of(args[0]), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS
            ); FileLock lock = channel.tryLock()) {
                System.out.println(lock == null ? "busy" : "acquired");
            }
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

    private static Path currentText(Path directory, String id) throws IOException {
        Properties properties = readMetadata(directory.resolve(id + ".meta.properties"));
        return directory.resolve(id + "." + properties.getProperty("textGeneration") + ".txt");
    }

    private static Properties readMetadata(Path metadata) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(metadata)) {
            properties.load(in);
        }
        return properties;
    }

    private static void writeMetadata(Path metadata, Properties properties) throws IOException {
        try (var out = Files.newOutputStream(metadata)) {
            properties.store(out, "test recovery metadata");
        }
    }

    private static String assertRejectedAndPreserved(
        RecoveryStore store,
        Path metadata,
        Path text
    ) throws IOException {
        String originalMetadata = Files.readString(metadata);
        String originalText = Files.readString(text);

        RecoveryStore.LoadResult result = store.loadAll();

        assertEquals(List.of(), result.entries());
        assertEquals(1, result.skipped().size());
        assertFalse(result.skipped().get(0).reason().isBlank());
        assertThrows(IOException.class, () -> store.save("session", snapshot()));
        assertEquals(originalMetadata, Files.readString(metadata));
        assertEquals(originalText, Files.readString(text));
        return result.skipped().get(0).reason();
    }

    private RecoverySnapshot openedFileSnapshot() throws IOException {
        Path file = tempDir.resolve("document.txt");
        Files.writeString(file, "original", StandardCharsets.UTF_8);
        TabSession session = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        session.engine().open(JvmDocumentStorage.resource(file));
        session.engine().insertInput("edited ");
        RecoverySnapshot captured = session.engine().captureRecoverySnapshot();
        return new RecoverySnapshot(
            captured.lines(),
            captured.cursor(),
            captured.viewport(),
            captured.selectionAnchor(),
            captured.dirty(),
            captured.currentResource(),
            captured.saveDisposition(),
            captured.fileMetadata(),
            JvmDocumentStorage.baselineOf(captured.fileBaseline()),
            Instant.parse("2026-07-24T00:00:00Z")
        );
    }

    private static RecoverySnapshot snapshot() {
        return new RecoverySnapshot(
            List.of("first", "second", ""),
            new Position(1, 3),
            new ViewportState(1, 2, 3, 4),
            new Position(0, 2),
            true,
            JvmDocumentStorage.resource(Path.of("/tmp/example.txt")),
            SaveDisposition.REPLACE_EXISTING,
            new DocumentFileMetadata(
                StandardCharsets.UTF_8,
                BomKind.UTF_8,
                EditorState.LINE_SEPARATOR_CRLF,
                List.of(EditorState.LINE_SEPARATOR_CRLF, EditorState.LINE_SEPARATOR_LF)
            ),
            null,
            Instant.parse("2026-07-24T00:00:00Z")
        );
    }
}
