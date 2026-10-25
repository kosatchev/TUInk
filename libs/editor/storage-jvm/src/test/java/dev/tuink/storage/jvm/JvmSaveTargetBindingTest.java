package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.storage.SaveDisposition;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** The baseline bytes and the publication address must always describe the same target. */
@ResourceLock("user.home")
class JvmSaveTargetBindingTest {
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
        AtomicInteger stagedFiles = new AtomicInteger();
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {
            @Override public OutputStream openStaged(Path staged) throws IOException {
                stagedFiles.incrementAndGet();
                assertTrue(staged.getFileName().toString().startsWith(".originaltxt."),
                    "Staging must remain beside the physical file chosen before verification");
                retarget(alias, original);
                return JvmDocumentStorage.PublicationFiles.super.openStaged(staged);
            }
        }, target -> {
            assertEquals(original, target, "Every baseline read must inspect the actual publication target");
            InputStream input = Files.newInputStream(target);
            if (reads.getAndIncrement() == 0) retarget(alias, unrelated);
            return input;
        });
        EditorEngine engine = open(storage, alias);
        engine.insertInput("draft ");

        var prepared = engine.prepareSave();
        var saved = prepared.write();
        engine.completeSaved(prepared, saved);

        assertTrue(reads.get() >= 3);
        assertEquals(1, stagedFiles.get());
        assertEquals("draft original bytes", Files.readString(original));
        assertEquals("unrelated bytes", Files.readString(unrelated));
        assertTrue(Files.isSymbolicLink(alias));
        assertEquals(original.getFileName(), Files.readSymbolicLink(alias));
        assertFalse(engine.state().isDirty());
        assertEquals(original, JvmDocumentStorage.baselineOf(engine.model().fileBaseline()).target());
    }

    @Test void anExplicitApprovedForeignOverwriteStillUsesItsChosenTarget() throws Exception {
        Path original = Files.writeString(directory.resolve("original.txt"), "original bytes");
        Path foreign = Files.writeString(directory.resolve("foreign.txt"), "foreign bytes");
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {});
        EditorEngine engine = open(storage, original);
        engine.insertInput("draft ");

        var prepared = engine.prepareSaveAs(storage.fileResource(foreign), SaveDisposition.REPLACE_EXISTING, false);
        var saved = prepared.write();
        engine.completeSaved(prepared, saved);

        assertEquals("original bytes", Files.readString(original));
        assertEquals("draft original bytes", Files.readString(foreign));
        assertFalse(engine.state().isDirty());
        assertEquals(foreign, JvmDocumentStorage.baselineOf(engine.model().fileBaseline()).target());
    }

    @Test void anApprovedHardLinkAliasKeepsItsIndependentDirectoryEntry() throws Exception {
        Path original = Files.writeString(directory.resolve("original.txt"), "original bytes");
        Path alias = Files.createLink(directory.resolve("hardlink.txt"), original);
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {});
        EditorEngine engine = open(storage, original);
        engine.insertInput("draft ");

        var prepared = engine.prepareSaveAs(storage.fileResource(alias), SaveDisposition.REPLACE_EXISTING, false);
        var saved = prepared.write();
        engine.completeSaved(prepared, saved);

        assertEquals("original bytes", Files.readString(original));
        assertEquals("draft original bytes", Files.readString(alias));
        assertFalse(Files.isSameFile(original, alias));
        assertFalse(engine.state().isDirty());
        assertEquals(alias, JvmDocumentStorage.baselineOf(engine.model().fileBaseline()).target());
        assertEquals(List.of("draft original bytes"), engine.model().snapshotLines());
    }

    private static EditorEngine open(JvmDocumentStorage storage, Path path) throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.setAutoPairsEnabled(false);
        engine.setAutoIndentEnabled(false);
        engine.setAutoListEnabled(false);
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        engine.openExisting(storage.fileResource(path));
        return engine;
    }

    private static void retarget(Path alias, Path target) throws IOException {
        Files.delete(alias);
        Files.createSymbolicLink(alias, target.getFileName());
    }
}
