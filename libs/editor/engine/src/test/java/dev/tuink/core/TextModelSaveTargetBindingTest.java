package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** The baseline bytes and the publication address must describe the same target. */
@ResourceLock("user.home")
class TextModelSaveTargetBindingTest {
    @TempDir Path directory;
    private String previousHome;

    @BeforeEach void isolateLocks() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.resolve("home").toString());
    }

    @AfterEach void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test void aliasChangesAfterVerificationCannotChooseAnotherFilesPublicationAddress() throws Exception {
        Path original = Files.writeString(directory.resolve("original.txt"), "original bytes");
        Path unrelated = Files.writeString(directory.resolve("unrelated.txt"), "unrelated bytes");
        Path alias = Files.createSymbolicLink(directory.resolve("editing.txt"), original.getFileName());
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean restoreBeforeStaging = new AtomicBoolean();
        TextModel model = new TextModel(new SimpleLineBuffer(), target -> {
            assertEquals(original, target, "Every baseline read must inspect the actual publication target");
            InputStream input = Files.newInputStream(target);
            if (reads.getAndIncrement() == 0) {
                retarget(alias, unrelated);
                restoreBeforeStaging.set(true);
            }
            return input;
        }) {
            @Override public String getLine(int line) {
                // Saving reads the text after choosing the target.
                if (restoreBeforeStaging.getAndSet(false)) {
                    try { retarget(alias, original); }
                    catch (IOException failure) { throw new UncheckedIOException(failure); }
                }
                return super.getLine(line);
            }
        };
        model.load(alias);
        model.insertText(new Position(0, 0), "draft ");

        model.saveIfUnchanged(alias, model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);

        assertTrue(reads.get() >= 3);
        assertEquals("draft original bytes", Files.readString(original));
        assertEquals("unrelated bytes", Files.readString(unrelated));
        assertTrue(Files.isSymbolicLink(alias));
        assertEquals(original.getFileName(), Files.readSymbolicLink(alias));
        assertEquals(original, model.fileBaseline().target());
        assertEquals(List.of("draft original bytes"), model.snapshotLines());
    }

    @Test void anExplicitForeignOverwriteStillUsesItsChosenTarget() throws Exception {
        Path original = Files.writeString(directory.resolve("original.txt"), "original bytes");
        Path foreign = Files.writeString(directory.resolve("foreign.txt"), "foreign bytes");
        TextModel model = open(original);

        model.save(foreign, model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);

        assertEquals("original bytes", Files.readString(original));
        assertEquals("draft original bytes", Files.readString(foreign));
        assertEquals(foreign, model.fileBaseline().target());
    }

    @Test void aHardLinkAliasKeepsItsIndependentDirectoryEntry() throws Exception {
        Path original = Files.writeString(directory.resolve("original.txt"), "original bytes");
        Path alias = Files.createLink(directory.resolve("hardlink.txt"), original);
        TextModel model = open(original);

        model.save(alias, model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);

        assertEquals("original bytes", Files.readString(original));
        assertEquals("draft original bytes", Files.readString(alias));
        assertTrue(!Files.isSameFile(original, alias));
        assertEquals(alias, model.fileBaseline().target());
        assertEquals(List.of("draft original bytes"), model.snapshotLines());
    }

    private static TextModel open(Path path) throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(path);
        model.insertText(new Position(0, 0), "draft ");
        return model;
    }

    private static void retarget(Path alias, Path target) throws IOException {
        Files.delete(alias);
        Files.createSymbolicLink(alias, target.getFileName());
    }
}
