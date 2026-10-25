package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.core.ViewportState;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryStreamingSaveTest {
    @TempDir Path directory;

    @Test
    void updatingALargeSnapshotDoesNotMaterializeThePreviousDocumentInASmallHeap() throws Exception {
        String classpath = String.join(File.pathSeparator, location(getClass()), location(RecoveryStore.class),
            location(RecoverySnapshot.class), location(dev.tuink.storage.jvm.JvmDocumentStorage.class), location(dev.tuink.editor.document.DocumentId.class));
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", classpath, UpdateProbe.class.getName(), directory.toString())
            .redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "snapshot update probe timed out");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("large snapshot updated"), output);
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void streamingValidationPreservesMalformedTextAndInvalidCoordinatesBeforeAnyWrite() throws Exception {
        RecoverySnapshot snapshot = unicodeSnapshot();
        try (RecoveryStore store = new RecoveryStore(directory)) {
            store.save("draft", snapshot);
            Path metadata = directory.resolve("draft.meta.properties");
            Path text = textPath(directory, "draft");
            byte[] validMetadata = Files.readAllBytes(metadata);
            byte[] validText = Files.readAllBytes(text);
            for (byte[] malformed : List.of(new byte[] {(byte) 0xc3, 0x28},
                "bad\rline\ntail".getBytes(StandardCharsets.UTF_8),
                "short\ntail".getBytes(StandardCharsets.UTF_8))) {
                Files.write(text, malformed);
                assertThrows(IOException.class, () -> store.save("draft", snapshot));
                assertArrayEquals(validMetadata, Files.readAllBytes(metadata));
                assertArrayEquals(malformed, Files.readAllBytes(text));
            }
            Files.write(text, validText);
            for (Map<String, String> change : List.of(
                Map.of("cursor.col", "8192"),
                Map.of("selection.col", "8192"),
                Map.of("cursor.line", "2"),
                Map.of("selection.line", "2"),
                Map.of("viewport.topLine", "2"),
                Map.of("lineSeparators", "LF,LF"),
                Map.of("saveDisposition", "REPLACE_EXISTING"),
                Map.of("dirty", "invalid"),
                Map.of("fileEncoding", "unsupported-encoding"))) {
                Files.write(metadata, validMetadata);
                Properties properties = metadata(metadata);
                change.forEach(properties::setProperty);
                try (var output = Files.newOutputStream(metadata)) {
                    properties.store(output, "invalid recovery metadata");
                }
                byte[] malformed = Files.readAllBytes(metadata);
                assertThrows(IOException.class, () -> store.save("draft", snapshot), change.toString());
                assertArrayEquals(malformed, Files.readAllBytes(metadata));
                assertArrayEquals(validText, Files.readAllBytes(text));
            }
            Files.write(metadata, validMetadata);
            store.save("draft", snapshot);
            assertFalse(Files.exists(text), "a valid old generation is replaced after complete validation");
            assertEquals(snapshot, store.loadAll().entries().getFirst().snapshot());
            try (var files = Files.list(directory)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
            }
        }
    }

    private static RecoverySnapshot unicodeSnapshot() {
        return new RecoverySnapshot(List.of("a".repeat(8191) + "😀", "tail"), new Position(0, 8193),
            new ViewportState(1, 0, 0, 0), new Position(0, 8191), true, null, SaveDisposition.CREATE_NEW,
            DocumentFileMetadata.newDocument(2), null, Instant.parse("2026-10-17T00:00:00Z"));
    }

    private static Properties metadata(Path path) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static Path textPath(Path directory, String id) throws IOException {
        return directory.resolve(id + "." + metadata(directory.resolve(id + ".meta.properties"))
            .getProperty("textGeneration") + ".txt");
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class UpdateProbe {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            try (RecoveryStore store = new RecoveryStore(directory)) {
                for (char value : new char[] {'a', 'b', 'c'}) {
                    var snapshot = new RecoverySnapshot(List.of(String.valueOf(value).repeat(4 * 1024 * 1024)),
                        new Position(0, 0), new ViewportState(0, 0, 0, 0), null, true, null,
                        SaveDisposition.CREATE_NEW, DocumentFileMetadata.newDocument(1), null, Instant.now());
                    store.save("draft", snapshot);
                    Path text = textPath(directory, "draft");
                    if (Files.size(text) != 4 * 1024 * 1024) {
                        throw new AssertionError("snapshot update truncated the document");
                    }
                    try (var input = Files.newInputStream(text)) {
                        if (input.read() != value) {
                            throw new AssertionError("snapshot update kept the previous generation");
                        }
                    }
                }
            }
            System.out.println("large snapshot updated");
        }
    }
}
