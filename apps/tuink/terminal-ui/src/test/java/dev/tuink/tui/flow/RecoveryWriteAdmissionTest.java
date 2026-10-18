package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.SaveDisposition;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryWriteAdmissionTest {
    @TempDir Path directory;

    @Test
    void rejectedDocumentAndFailureAfterTextStagingPreserveTheLastReadableGeneration() throws Exception {
        RecoverySnapshot previous = snapshot(List.of("last good", "tail"), null);
        try (RecoveryStore store = new RecoveryStore(directory.resolve("recovery"))) {
            store.save("draft", previous);
            Path metadata = store.directory().resolve("draft.meta.properties");
            Properties properties = properties(metadata);
            Path text = store.directory().resolve("draft." + properties.getProperty("textGeneration") + ".txt");
            byte[] oldMetadata = Files.readAllBytes(metadata);
            byte[] oldText = Files.readAllBytes(text);
            Set<Path> oldPaths = paths(store.directory());
            RecoverySnapshot oversized = snapshot(Collections.nCopies(70_000, "x".repeat(1024)), null);

            assertThrows(IOException.class, () -> store.save("draft", oversized));
            assertArrayEquals(oldMetadata, Files.readAllBytes(metadata));
            assertArrayEquals(oldText, Files.readAllBytes(text));
            assertEquals(oldPaths, paths(store.directory()));

            // Native path serialization fails only after the next text generation has been staged.
            URI archive = URI.create("jar:" + directory.resolve("paths.zip").toUri());
            try (var filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
                RecoverySnapshot unpersistable = snapshot(List.of("new unsaved work"), filesystem.getPath("/draft.txt"));
                assertThrows(IOException.class, () -> store.save("draft", unpersistable));
            }
            assertArrayEquals(oldMetadata, Files.readAllBytes(metadata));
            assertArrayEquals(oldText, Files.readAllBytes(text));
            assertEquals(oldPaths, paths(store.directory()), "failed generations and temporary files must be removed");
            assertEquals(previous, store.loadAll().entries().getFirst().snapshot());
        }
    }

    @Test
    void streamingOutputLimitRejectsAChunkBeforeWritingAnyPartOfIt() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = RecoveryLimits.boundedOutput(bytes, 4, "test limit")) {
            output.write(new byte[] {1, 2, 3});
            assertThrows(IOException.class, () -> output.write(new byte[] {4, 5}));
            assertArrayEquals(new byte[] {1, 2, 3}, bytes.toByteArray());
            output.write(4);
            assertThrows(IOException.class, () -> output.write(5));
            assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes.toByteArray());
        }
    }

    @Test
    void malformedCompactCountCannotBeExpandedByDiscoveryOrOverwrite() throws Exception {
        try (RecoveryStore store = new RecoveryStore(directory)) {
            RecoverySnapshot previous = snapshot(List.of("draft"), null);
            store.save("draft", previous);
            Path metadata = directory.resolve("draft.meta.properties");
            Properties properties = properties(metadata);
            properties.setProperty("lineSeparators", "packed2:2147483647:");
            try (var output = Files.newOutputStream(metadata)) { properties.store(output, "oversized count"); }
            byte[] before = Files.readAllBytes(metadata);

            assertFalse(store.discover().skipped().isEmpty());
            assertEquals(List.of(), store.loadAll().entries());
            assertThrows(IOException.class, () -> store.save("draft", previous));
            assertArrayEquals(before, Files.readAllBytes(metadata));
        }
    }

    private static RecoverySnapshot snapshot(List<String> lines, Path currentFile) {
        return new RecoverySnapshot(lines, null, null, null, true, currentFile, SaveDisposition.CREATE_NEW,
            null, null, Instant.parse("2026-10-18T00:00:00Z"));
    }

    private static Properties properties(Path file) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        return properties;
    }

    private static Set<Path> paths(Path directory) throws IOException {
        try (var files = Files.list(directory)) { return files.collect(Collectors.toSet()); }
    }
}
