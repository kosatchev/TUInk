package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.RecoverySnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class RecoveryPublicationFailureTest {
    @TempDir Path directory;

    private enum CleanupFailure { IO, RUNTIME, MEMORY }
    private enum RenameFailure {
        IO, MEMORY, FATAL;

        Throwable exception() {
            return switch (this) {
                case IO -> new IOException("publication failed after rename");
                case MEMORY -> new OutOfMemoryError("publication failed after rename");
                case FATAL -> new AssertionError("publication failed after rename");
            };
        }
    }

    @ParameterizedTest
    @MethodSource("ambiguousMetadataPublications")
    void aMetadataRenameFollowedByFailureRetainsTheGenerationItNowReferences(
        RenameFailure kind, boolean existingSnapshot
    ) throws Exception {
        RenameFault files = new RenameFault(kind.exception());
        RecoverySnapshot expected = snapshot("committed despite publication error");
        try (RecoveryStore store = new RecoveryStore(directory, files)) {
            Path previousText = null;
            if (existingSnapshot) {
                store.save("session", snapshot("previous draft"));
                previousText = currentText();
            }
            files.failSuffix = ".meta.properties";

            assertSame(files.failure, assertThrows(files.failure.getClass(), () -> store.save("session", expected)));

            assertTrue(files.triggered);
            assertTrue(Files.isRegularFile(currentText()), "metadata's referenced text must never be removed");
            if (previousText != null) {
                assertFalse(previousText.equals(currentText()));
                assertTrue(Files.exists(previousText), "an uncertain publication cannot authorize old-generation cleanup");
            }
            assertEquals(existingSnapshot ? 2 : 1, generationFiles().size());
            assertEquals(List.of(new RecoveryStore.Entry("session", expected)), store.loadAll().entries());
        }
        assertReopened(expected);
    }

    private static Stream<Arguments> ambiguousMetadataPublications() {
        return Stream.of(RenameFailure.values()).flatMap(kind -> Stream.of(
            Arguments.of(kind, false), Arguments.of(kind, true)));
    }

    @ParameterizedTest
    @EnumSource(RenameFailure.class)
    void aTextRenameFollowedByFailureBeforeMetadataPublicationRemovesOnlyTheUnreferencedText(
        RenameFailure kind
    ) throws Exception {
        RenameFault files = new RenameFault(kind.exception());
        RecoverySnapshot previous = snapshot("previous committed draft");
        try (RecoveryStore store = new RecoveryStore(directory, files)) {
            store.save("session", previous);
            Path previousText = currentText();
            byte[] previousMetadata = Files.readAllBytes(directory.resolve("session.meta.properties"));
            files.failSuffix = ".txt";

            assertSame(files.failure, assertThrows(files.failure.getClass(),
                () -> store.save("session", snapshot("unreferenced draft"))));

            assertTrue(files.triggered);
            assertArrayEquals(previousMetadata, Files.readAllBytes(directory.resolve("session.meta.properties")));
            assertEquals(previousText, currentText());
            assertEquals(List.of(previousText), generationFiles());
            assertEquals(List.of(new RecoveryStore.Entry("session", previous)), store.loadAll().entries());
        }
        assertReopened(previous);
    }

    @ParameterizedTest
    @EnumSource(CleanupFailure.class)
    void cleanupFailureAfterMetadataRenameCannotUndoTheCommittedGeneration(CleanupFailure kind) throws Exception {
        CleanupFault files = new CleanupFault();
        RecoverySnapshot expected = snapshot("new committed draft");
        Path previousText;
        try (RecoveryStore store = new RecoveryStore(directory, files)) {
            store.save("session", snapshot("previous draft"));
            previousText = currentText();
            files.failMetadataCleanup = kind;

            store.save("session", expected);

            assertTrue(files.triggered);
            assertFalse(Files.exists(previousText));
            assertEquals("8", metadata().getProperty("format"));
            assertTrue(Files.isRegularFile(currentText()));
            assertEquals(List.of(new RecoveryStore.Entry("session", expected)), store.loadAll().entries());
        }
        assertReopened(expected);
    }

    @ParameterizedTest
    @EnumSource(CleanupFailure.class)
    void aFailedMetadataCommitKeepsThePreviousGenerationAndTheOriginalFailure(CleanupFailure kind) throws Exception {
        CleanupFault files = new CleanupFault();
        RecoverySnapshot previous = snapshot("previous committed draft");
        try (RecoveryStore store = new RecoveryStore(directory, files)) {
            store.save("session", previous);
            Path previousText = currentText();
            byte[] previousMetadata = Files.readAllBytes(directory.resolve("session.meta.properties"));
            files.failMetadataCleanup = kind;
            files.failMetadataPublish = true;

            assertSame(files.publishFailure, assertThrows(IOException.class,
                () -> store.save("session", snapshot("uncommitted draft"))));

            assertTrue(files.triggered);
            assertEquals(1, files.publishFailure.getSuppressed().length);
            assertArrayEquals(previousMetadata, Files.readAllBytes(directory.resolve("session.meta.properties")));
            assertEquals(previousText, currentText());
            var retained = generationFiles();
            assertEquals(2, retained.size(), "a failed publication may have committed, so its text is retained");
            assertTrue(retained.contains(previousText));
            Path orphan = retained.stream().filter(path -> !path.equals(previousText)).findFirst().orElseThrow();
            assertEquals("uncommitted draft", Files.readString(orphan));
            assertEquals(List.of(new RecoveryStore.Entry("session", previous)), store.loadAll().entries());
        }
        assertReopened(previous);
    }

    @Test
    void anUnexpectedFatalCleanupErrorStillCannotDeleteTheReferencedGeneration() throws Exception {
        AssertionError fatal = new AssertionError("fatal error after metadata rename");
        class FatalCleanup implements RecoveryStore.PublicationFiles {
            boolean armed;
            @Override public void deleteIfExists(Path path) throws IOException {
                if (armed && isMetadataTemp(path)) {
                    armed = false;
                    throw fatal;
                }
                RecoveryStore.PublicationFiles.super.deleteIfExists(path);
            }
        }
        FatalCleanup files = new FatalCleanup();
        RecoverySnapshot expected = snapshot("committed before fatal cleanup");
        try (RecoveryStore store = new RecoveryStore(directory, files)) {
            store.save("session", snapshot("previous draft"));
            files.armed = true;

            assertSame(fatal, assertThrows(AssertionError.class, () -> store.save("session", expected)));

            assertTrue(Files.isRegularFile(currentText()));
            assertEquals(List.of(new RecoveryStore.Entry("session", expected)), store.loadAll().entries());
        }
        assertReopened(expected);
    }

    @Test
    void outOfMemoryWhileDeletingTheOldGenerationLeavesTheNewSnapshotRecoverable() throws Exception {
        class OrphanCleanup implements RecoveryStore.PublicationFiles {
            Path failOn;
            boolean triggered;
            @Override public void deleteIfExists(Path path) throws IOException {
                if (path.equals(failOn)) {
                    triggered = true;
                    throw new OutOfMemoryError("old generation cleanup");
                }
                RecoveryStore.PublicationFiles.super.deleteIfExists(path);
            }
        }
        OrphanCleanup files = new OrphanCleanup();
        RecoverySnapshot expected = snapshot("new committed draft");
        try (RecoveryStore store = new RecoveryStore(directory, files)) {
            store.save("session", snapshot("previous draft"));
            files.failOn = currentText();

            store.save("session", expected);

            assertTrue(files.triggered);
            assertTrue(Files.exists(files.failOn), "an orphan is harmless once metadata names the new generation");
            assertFalse(files.failOn.equals(currentText()));
            assertEquals(List.of(new RecoveryStore.Entry("session", expected)), store.loadAll().entries());
        }
        assertReopened(expected);
    }

    private void assertReopened(RecoverySnapshot expected) throws IOException {
        try (RecoveryStore reopened = new RecoveryStore(directory)) {
            var loaded = reopened.loadAll();
            assertTrue(loaded.skipped().isEmpty());
            assertEquals(List.of(new RecoveryStore.Entry("session", expected)), loaded.entries());
        }
    }

    private Properties metadata() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(directory.resolve("session.meta.properties"))) {
            properties.load(in);
        }
        return properties;
    }

    private Path currentText() throws IOException {
        return directory.resolve("session." + metadata().getProperty("textGeneration") + ".txt");
    }

    private List<Path> generationFiles() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".txt")).toList();
        }
    }

    private static RecoverySnapshot snapshot(String text) {
        return new RecoverySnapshot(List.of(text), null, null, null, true, null, null, null, null,
            Instant.ofEpochMilli(1234));
    }

    private static boolean isMetadataTemp(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(".session.meta.properties.") && name.endsWith(".tmp");
    }

    private static final class CleanupFault implements RecoveryStore.PublicationFiles {
        private final IOException publishFailure = new IOException("metadata rename refused");
        private CleanupFailure failMetadataCleanup;
        private boolean failMetadataPublish;
        private boolean triggered;

        @Override public void publish(Path staged, Path target) throws IOException {
            if (failMetadataPublish && target.getFileName().toString().equals("session.meta.properties")) {
                throw publishFailure;
            }
            RecoveryStore.PublicationFiles.super.publish(staged, target);
        }

        @Override public void deleteIfExists(Path path) throws IOException {
            if (failMetadataCleanup != null && isMetadataTemp(path)) {
                CleanupFailure kind = failMetadataCleanup;
                failMetadataCleanup = null;
                triggered = true;
                switch (kind) {
                    case IO -> throw new IOException("metadata temp cleanup");
                    case RUNTIME -> throw new IllegalStateException("metadata temp cleanup");
                    case MEMORY -> throw new OutOfMemoryError("metadata temp cleanup");
                }
            }
            RecoveryStore.PublicationFiles.super.deleteIfExists(path);
        }
    }

    private static final class RenameFault implements RecoveryStore.PublicationFiles {
        private final Throwable failure;
        private String failSuffix;
        private boolean triggered;

        private RenameFault(Throwable failure) { this.failure = failure; }

        @Override public void publish(Path staged, Path target) throws IOException {
            RecoveryStore.PublicationFiles.super.publish(staged, target);
            if (failSuffix != null && target.getFileName().toString().endsWith(failSuffix)) {
                failSuffix = null;
                triggered = true;
                if (failure instanceof IOException io) throw io;
                if (failure instanceof Error error) throw error;
                throw new AssertionError("unsupported fixture failure", failure);
            }
        }
    }
}
