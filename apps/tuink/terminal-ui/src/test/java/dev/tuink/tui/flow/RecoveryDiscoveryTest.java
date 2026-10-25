package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryDiscoveryTest {
    @TempDir Path directory;

    @Test
    void discoveryDefersTextDecodingAndFailedRestoreLeavesTheSnapshotAvailable() throws Exception {
        try (RecoveryStore writer = new RecoveryStore(directory)) {
            writer.save("draft", snapshot());
        }
        Path text = textPath(directory, "draft");
        byte[] malformed = {(byte) 0xc3, 0x28};
        Files.write(text, malformed);

        try (RecoveryStore store = new RecoveryStore(directory)) {
            var discovered = store.discover();
            assertTrue(discovered.skipped().isEmpty());
            assertEquals(1, discovered.entries().size());
            assertThrows(IOException.class, () -> discovered.entries().getFirst().snapshot());
            assertArrayEquals(malformed, Files.readAllBytes(text));
            store.release("draft");
            try (RecoveryStore peer = new RecoveryStore(directory)) {
                assertEquals(1, peer.discover().entries().size());
            }
        }
    }

    @Test
    void oversizedTextCanBeOfferedPostponedOrDeletedWithoutReadingIt() throws Exception {
        try (RecoveryStore writer = new RecoveryStore(directory)) {
            writer.save("draft", snapshot());
        }
        Path text = textPath(directory, "draft");
        try (RandomAccessFile sparse = new RandomAccessFile(text.toFile(), "rw")) {
            sparse.setLength(2L * 1024 * 1024 * 1024);
        }
        try (RecoveryStore store = new RecoveryStore(directory)) {
            var entry = store.discover().entries().getFirst();
            assertThrows(IOException.class, entry::snapshot);
            assertEquals(2L * 1024 * 1024 * 1024, Files.size(text));
            store.release(entry.id());
            assertThrows(IOException.class, entry::snapshot, "postponed handles must not silently reacquire ownership");
            try (RecoveryStore peer = new RecoveryStore(directory)) {
                var offeredAgain = peer.discover().entries().getFirst();
                peer.delete(offeredAgain.id());
                assertFalse(Files.exists(text));
                assertTrue(peer.discover().entries().isEmpty());
            }
        }
    }

    @Test
    void descriptorDoesNotReadAnUnexpectedReplacementGeneration() throws Exception {
        try (RecoveryStore store = new RecoveryStore(directory)) {
            store.save("draft", snapshot());
            var old = store.discover().entries().getFirst();
            store.save("draft", snapshot());
            assertThrows(IOException.class, old::snapshot);
            assertEquals(snapshot().lines(), store.discover().entries().getFirst().snapshot().lines());
        }
    }

    @Test
    void severalValidLargeSnapshotsCannotExhaustTheStartupHeap() throws Exception {
        // The text is valid UTF-8 and has the same single logical line as its metadata.
        try (RecoveryStore writer = new RecoveryStore(directory)) {
            for (int i = 0; i < 4; i++) {
                writer.save("draft-" + i, snapshot());
            }
        }
        byte[] block = "a".repeat(8192).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 4; i++) {
            try (var out = Files.newOutputStream(textPath(directory, "draft-" + i))) {
                for (int n = 0; n < 1280; n++) {
                    out.write(block);
                }
            }
        }
        String classpath = String.join(java.io.File.pathSeparator, location(RecoveryStore.class),
            location(getClass()), location(RecoverySnapshot.class), location(dev.tuink.storage.jvm.JvmDocumentStorage.class), location(dev.tuink.editor.document.DocumentId.class));
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", classpath,
            BudgetProbe.class.getName(), directory.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "recovery probe timed out");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("recovery discovery and admission passed"), output);
        } finally {
            child.destroyForcibly();
        }
    }

    private static RecoverySnapshot snapshot() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.insertInput("draft");
        return engine.captureRecoverySnapshot();
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static Path textPath(Path directory, String id) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(directory.resolve(id + ".meta.properties"))) {
            p.load(in);
        }
        return directory.resolve(id + "." + p.getProperty("textGeneration") + ".txt");
    }

    public static final class BudgetProbe {
        public static void main(String[] args) throws Exception {
            try (RecoveryStore store = new RecoveryStore(Path.of(args[0]))) {
                var offered = store.discover();
                if (offered.entries().size() != 4 || !offered.skipped().isEmpty()) {
                    throw new AssertionError("startup must offer all valid snapshots without text reads");
                }
                for (var entry : offered.entries()) {
                    try {
                        entry.snapshot();
                        throw new AssertionError("large snapshot unexpectedly admitted into a small heap");
                    } catch (IOException expected) {
                        store.release(entry.id());
                    }
                }
                var eager = store.loadAll();
                if (!eager.entries().isEmpty() || eager.skipped().size() != 4) {
                    throw new AssertionError("eager convenience API must also respect memory admission");
                }
                System.out.println("recovery discovery and admission passed");
            }
        }
    }
}
