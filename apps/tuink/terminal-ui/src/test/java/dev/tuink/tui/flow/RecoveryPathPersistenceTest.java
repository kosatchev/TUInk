package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.TabSession;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryPathPersistenceTest {
    @TempDir Path directory;

    @Test
    void rawUnixFilenameSurvivesDiscoveryAndRestorationWithoutWritingToItsDisplayNameNeighbor() throws Exception {
        assumeUnix();
        Path original = Files.writeString(rawPath("legacy-%FF.txt"), "original");
        Path neighbor = Files.writeString(directory.resolve("legacy-\uFFFD.txt"), "original");
        try (var paths = Files.list(directory)) {
            original = paths.filter(path -> path.toUri().toASCIIString().endsWith("legacy-%FF.txt"))
                .findFirst().orElseThrow();
        }
        assertNotEquals(original, neighbor);
        TabSession source = session();
        source.engine().openExisting(original);
        source.engine().insertInput("edited ");
        RecoverySnapshot expected = storedPrecisionSnapshot(source);
        Path recovery = directory.resolve("recovery");

        try (RecoveryStore writer = new RecoveryStore(recovery)) {
            writer.save("draft", expected);
        }
        try (RecoveryStore reader = new RecoveryStore(recovery)) {
            var discovered = reader.discover();
            assertTrue(discovered.skipped().isEmpty());
            RecoverySnapshot restored = discovered.entries().getFirst().snapshot();
            assertEquals(expected, restored);
            assertEquals(original, restored.currentFile());
            assertEquals(original.toRealPath(), restored.fileBaseline().target());
            assertEquals(expected, reader.loadAll().entries().getFirst().snapshot());
            Properties metadata = readMetadata(recovery.resolve("draft.meta.properties"));
            assertEquals("8", metadata.getProperty("format"));
            assertEquals("unix", metadata.getProperty("pathPlatform"));
            assertEquals(original.toUri().toASCIIString(), metadata.getProperty("currentFile"));
            assertEquals(original.toRealPath().toUri().toASCIIString(), metadata.getProperty("fileBaseline.target"));

            TabSession document = session();
            document.engine().restoreRecoverySnapshot(restored);
            document.engine().save();

            assertEquals("edited original", Files.readString(original));
            assertEquals("original", Files.readString(neighbor));
            assertFalse(document.state().isDirty());
        }
    }

    @Test
    void expectedAbsentRawUnixFilenameCanBeRestoredAndCreatedWithoutAReplacementCharacterPath() throws Exception {
        assumeUnix();
        Path target = rawPath("new-%FF.txt");
        Path wrong = directory.resolve("new-\uFFFD.txt");
        TabSession source = session();
        source.engine().open(target);
        source.engine().insertInput("draft");
        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            store.save("draft", source.engine().captureRecoverySnapshot());
            TabSession restored = session();
            restored.engine().restoreRecoverySnapshot(store.discover().entries().getFirst().snapshot());

            restored.engine().save();

            assertEquals(target, restored.state().getCurrentFile());
            assertEquals("draft", Files.readString(target));
            assertFalse(Files.exists(wrong));
        }
    }

    @Test
    void unicodeSpacesAndUriReservedCharactersRoundTripAsAscii() throws Exception {
        Path original = Files.writeString(directory.resolve("данные #50% + 中😀.txt"), "original");
        TabSession source = session();
        source.engine().openExisting(original);
        source.engine().insertInput("draft ");
        RecoverySnapshot expected = storedPrecisionSnapshot(source);
        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            store.save("draft", expected);

            assertEquals(expected, store.discover().entries().getFirst().snapshot());
            Properties metadata = readMetadata(store.directory().resolve("draft.meta.properties"));
            assertTrue(metadata.getProperty("currentFile").chars().allMatch(character -> character < 128));
            assertEquals(original.toUri().toASCIIString(), metadata.getProperty("currentFile"));
        }
    }

    @Test
    void pathDecodingDoesNotDependOnWhetherAnExternalProcessMadeTheTargetADirectory() throws Exception {
        Path original = Files.writeString(directory.resolve("original.txt"), "original");
        TabSession source = session();
        source.engine().openExisting(original);
        source.engine().insertInput("draft ");
        RecoverySnapshot expected = storedPrecisionSnapshot(source);
        Files.delete(original);
        Files.createDirectory(original);
        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            store.save("draft", expected);
            Files.delete(original);

            assertEquals(expected, store.discover().entries().getFirst().snapshot());
            assertEquals(expected, store.loadAll().entries().getFirst().snapshot());
            assertFalse(Files.exists(original));
        }
    }

    @Test
    void malformedForeignAndNoncanonicalUriFieldsAreRejectedWithoutChangingTheSnapshot() throws Exception {
        Path original = Files.writeString(directory.resolve("original.txt"), "original");
        TabSession source = session();
        source.engine().openExisting(original);
        source.engine().insertInput("draft ");
        RecoverySnapshot snapshot = source.engine().captureRecoverySnapshot();
        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            store.save("draft", snapshot);
            Path metadata = store.directory().resolve("draft.meta.properties");
            Properties valid = readMetadata(metadata);
            Path text = textPath(store.directory(), valid);
            byte[] originalText = Files.readAllBytes(text);
            String uri = original.toUri().toASCIIString();
            List<String> invalid = List.of(
                original.toString(), "relative.txt", "https://example.com/document.txt", "jar:" + uri + "!/entry",
                "file:relative.txt", uri + "?query", uri + "#fragment", "file:///bad%zz", "file:///bad%00",
                directory.toUri().toASCIIString() + "../original.txt",
                directory.toUri().toASCIIString() + "%2E%2E/original.txt",
                directory.toUri().toASCIIString() + "сырой.txt", uri.replace("file:///", "file:/")
            );
            for (String key : List.of("currentFile", "fileBaseline.target")) {
                for (String value : invalid) {
                    Properties malformed = new Properties();
                    malformed.putAll(valid);
                    malformed.setProperty(key, value);
                    writeMetadata(metadata, malformed);
                    byte[] committed = Files.readAllBytes(metadata);

                    var discovered = store.discover();
                    assertTrue(discovered.entries().isEmpty(), key + "=" + value);
                    assertEquals(1, discovered.skipped().size(), key + "=" + value);
                    assertTrue(discovered.skipped().getFirst().reason().contains("canonical ASCII file URI"));
                    assertThrows(IOException.class, () -> store.save("draft", snapshot));
                    assertArrayEquals(committed, Files.readAllBytes(metadata));
                    assertArrayEquals(originalText, Files.readAllBytes(text));
                }
            }
        }
    }

    @Test
    void aForeignPlatformMarkerIsRejectedBeforePathsAreUsed() throws Exception {
        assertRejectedFormatMutation("pathPlatform", File.separatorChar == '\\' ? "unix" : "windows");
    }

    @Test
    void formatFiveWithNewerSchemaFieldsIsRejectedAndCannotBeOverwrittenByAutosave() throws Exception {
        assertRejectedFormatMutation("format", "5");
    }

    private void assertRejectedFormatMutation(String key, String value) throws Exception {
        Path original = Files.writeString(directory.resolve("original.txt"), "original");
        TabSession source = session();
        source.engine().openExisting(original);
        source.engine().insertInput("draft ");
        RecoverySnapshot snapshot = source.engine().captureRecoverySnapshot();
        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            store.save("draft", snapshot);
            Path metadata = store.directory().resolve("draft.meta.properties");
            Properties fields = readMetadata(metadata);
            Path text = textPath(store.directory(), fields);
            fields.setProperty(key, value);
            if (key.equals("format")) {
                fields.remove("pathPlatform");
                fields.setProperty("currentFile", snapshot.currentFile().toString());
                fields.setProperty("fileBaseline.target", snapshot.fileBaseline().target().toString());
            }
            writeMetadata(metadata, fields);
            byte[] metadataBytes = Files.readAllBytes(metadata);
            byte[] textBytes = Files.readAllBytes(text);

            var discovered = store.discover();
            assertTrue(discovered.entries().isEmpty());
            assertEquals(1, discovered.skipped().size());
            assertEquals(1, store.loadAll().skipped().size());
            assertThrows(IOException.class, () -> store.save("draft", snapshot));
            assertArrayEquals(metadataBytes, Files.readAllBytes(metadata));
            assertArrayEquals(textBytes, Files.readAllBytes(text));
        }
    }

    private void assumeUnix() throws IOException {
        assumeTrue(File.separatorChar == '/' && Files.getFileStore(directory).supportsFileAttributeView("posix"));
    }

    private Path rawPath(String encodedName) {
        return Path.of(URI.create(directory.toUri().toASCIIString() + encodedName));
    }

    private static TabSession session() {
        return TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
    }

    private static RecoverySnapshot storedPrecisionSnapshot(TabSession source) {
        RecoverySnapshot captured = source.engine().captureRecoverySnapshot();
        return new RecoverySnapshot(captured.lines(), captured.cursor(), captured.viewport(), captured.selectionAnchor(),
            captured.dirty(), captured.currentFile(), captured.saveDisposition(), captured.fileMetadata(),
            captured.fileBaseline(), captured.createdAt().truncatedTo(ChronoUnit.MILLIS));
    }

    private static Properties readMetadata(Path path) throws IOException {
        Properties result = new Properties();
        try (var input = Files.newInputStream(path)) {
            result.load(input);
        }
        return result;
    }

    private static void writeMetadata(Path path, Properties fields) throws IOException {
        try (var output = Files.newOutputStream(path)) {
            fields.store(output, "recovery contract fixture");
        }
    }

    private static Path textPath(Path directory, Properties metadata) {
        return directory.resolve("draft." + metadata.getProperty("textGeneration") + ".txt");
    }
}
