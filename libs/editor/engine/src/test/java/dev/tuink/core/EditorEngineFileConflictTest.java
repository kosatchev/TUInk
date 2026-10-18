package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEngineFileConflictTest {
    @TempDir
    Path directory;

    @Test
    void saveRejectsExternalEditsEvenWhenSizeAndTimestampAreUnchanged() throws IOException {
        Path path = write("document.txt", "before");
        EditorEngine engine = open(path);
        var modified = Files.getLastModifiedTime(path);
        engine.insertInput("mine ");
        FileBaseline baseline = engine.model().fileBaseline();
        Files.writeString(path, "remote");
        Files.setLastModifiedTime(path, modified);

        IOException conflict = assertThrows(IOException.class, engine::save);

        assertTrue(conflict.getMessage().contains("File changed outside TUInk"));
        assertEquals("remote", Files.readString(path));
        assertEquals("mine before", engine.model().getLine(0));
        assertTrue(engine.state().isDirty());
        assertEquals(baseline, engine.model().fileBaseline());
        engine.undo();
        assertFalse(engine.state().isDirty());
        assertEquals("before", engine.model().getLine(0));
    }

    @Test
    void saveDoesNotRecreateAnExternallyDeletedFile() throws IOException {
        Path path = write("deleted.txt", "before");
        EditorEngine engine = open(path);
        engine.insertInput("mine ");
        Files.delete(path);

        assertThrows(IOException.class, engine::save);

        assertFalse(Files.exists(path));
        assertTrue(engine.state().isDirty());
    }

    @Test
    void successfulSaveRefreshesTheBaselineForFollowingSaves() throws IOException {
        Path path = write("repeat.txt", "before");
        EditorEngine engine = open(path);
        engine.insertInput("first ");
        engine.save();
        FileBaseline firstSave = engine.model().fileBaseline();
        engine.insertInput("second ");
        engine.save();

        assertEquals("first second before", Files.readString(path));
        assertFalse(engine.state().isDirty());
        assertFalse(firstSave.equals(engine.model().fileBaseline()));
        engine.undo();
        engine.save();
        assertEquals("first before", Files.readString(path));
    }

    @Test
    void saveAsToTheCurrentPathCannotBypassConflictChecking() throws IOException {
        Path path = write("same.txt", "before");
        EditorEngine engine = open(path);
        engine.insertInput("mine ");
        Files.writeString(path, "remote");

        assertThrows(IOException.class, () -> engine.saveAs(path, SaveDisposition.REPLACE_EXISTING));

        assertEquals("remote", Files.readString(path));
        assertTrue(engine.state().isDirty());
    }

    @Test
    void saveAsToADifferentPathPreservesBothVersionsAndOwnsTheNewPath() throws IOException {
        Path path = write("source.txt", "before");
        EditorEngine engine = open(path);
        engine.insertInput("mine ");
        Files.writeString(path, "remote");
        Path copy = directory.resolve("copy.txt");

        engine.saveAs(copy);
        engine.insertInput("new ");
        engine.save();

        assertEquals("remote", Files.readString(path));
        assertEquals("mine new before", Files.readString(copy));
        assertEquals(copy, engine.state().getCurrentFile());
        assertFalse(engine.state().isDirty());
    }

    @Test
    void recoveredDocumentRetainsTheOriginalDiskBaseline() throws IOException {
        Path path = write("recovered.txt", "before");
        EditorEngine source = open(path);
        source.insertInput("mine ");
        RecoverySnapshot snapshot = source.captureRecoverySnapshot();
        assertNotNull(snapshot.fileBaseline());
        Files.writeString(path, "remote");
        EditorEngine restored = emptyEngine();
        restored.restoreRecoverySnapshot(snapshot);

        assertThrows(IOException.class, restored::save);

        assertEquals("remote", Files.readString(path));
        assertEquals("mine before", restored.model().getLine(0));
        assertTrue(restored.state().isDirty());
    }

    @Test
    void recoveredDocumentCanSaveWhenItsOriginalFileIsUnchanged() throws IOException {
        Path path = write("unchanged.txt", "before");
        EditorEngine source = open(path);
        source.insertInput("mine ");
        EditorEngine restored = emptyEngine();
        restored.restoreRecoverySnapshot(source.captureRecoverySnapshot());

        restored.save();

        assertEquals("mine before", Files.readString(path));
        assertFalse(restored.state().isDirty());
    }

    @Test
    void recoveryWithoutABaselineCannotOverwriteAnExistingFile() throws IOException {
        Path path = write("unknown.txt", "remote");
        EditorEngine restored = open(path);
        restored.restoreRecoverySnapshot(new RecoverySnapshot(
            List.of("draft"), new Position(0, 0), new ViewportState(0, 0, 0, 0),
            null, true, path, SaveDisposition.REPLACE_EXISTING,
            DocumentFileMetadata.newDocument(1), null, null
        ));

        IOException conflict = assertThrows(IOException.class, restored::save);

        assertTrue(conflict.getMessage().contains("Cannot verify the original file"));
        assertEquals("remote", Files.readString(path));
        assertTrue(restored.state().isDirty());
    }

    @Test
    void retargetingASymlinkIsRejectedEvenWhenBothTargetsHaveIdenticalBytes() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path first = write("first.txt", "before");
        Path second = write("second.txt", "before");
        Path link = directory.resolve("link.txt");
        Files.createSymbolicLink(link, first);
        EditorEngine engine = open(link);
        engine.insertInput("mine ");
        Files.delete(link);
        Files.createSymbolicLink(link, second);

        assertThrows(IOException.class, engine::save);

        assertEquals("before", Files.readString(first));
        assertEquals("before", Files.readString(second));
        assertEquals(second, Files.readSymbolicLink(link));
        assertTrue(engine.state().isDirty());
    }

    @Test
    void saveAsThroughAnAliasOfTheCurrentFileAlsoChecksForExternalEdits() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path path = write("aliased.txt", "before");
        Path alias = directory.resolve("alias.txt");
        Files.createSymbolicLink(alias, path);
        EditorEngine engine = open(path);
        engine.insertInput("mine ");
        Files.writeString(path, "remote");

        assertThrows(IOException.class, () -> engine.saveAs(alias, SaveDisposition.REPLACE_EXISTING));

        assertEquals("remote", Files.readString(path));
        assertEquals(path, engine.state().getCurrentFile());
        assertTrue(Files.isSymbolicLink(alias));
    }

    @Test
    void saveAsThroughAnUnchangedHardLinkKeepsTheExistingFileSafe() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path path = write("hardlink-source.txt", "before");
        Path alias = directory.resolve("hardlink-alias.txt");
        Files.createLink(alias, path);
        EditorEngine engine = open(path);
        engine.insertInput("mine ");

        engine.saveAs(alias, SaveDisposition.REPLACE_EXISTING);
        engine.insertInput("new ");
        engine.save();

        assertEquals("before", Files.readString(path));
        assertEquals("mine new before", Files.readString(alias));
        assertFalse(engine.state().isDirty());
    }

    @Test
    void saveAsToTheOriginalTargetAfterSymlinkRetargetingStillChecksItsBaseline() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path original = write("original-target.txt", "before");
        Path other = write("other-target.txt", "other");
        Path link = directory.resolve("retargeted.txt");
        Files.createSymbolicLink(link, original);
        EditorEngine engine = open(link);
        engine.insertInput("mine ");
        Files.writeString(original, "remote");
        Files.delete(link);
        Files.createSymbolicLink(link, other);

        assertThrows(IOException.class, () -> engine.saveAs(original, SaveDisposition.REPLACE_EXISTING));

        assertEquals("remote", Files.readString(original));
        assertEquals("other", Files.readString(other));
        assertEquals(link, engine.state().getCurrentFile());
        assertTrue(engine.state().isDirty());
    }

    @Test
    void saveAsCanExplicitlyReplaceTheNewTargetOfARetargetedOpeningAlias() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path original = write("source-target.txt", "before");
        Path other = write("other-target.txt", "other");
        Path link = Files.createSymbolicLink(directory.resolve("source-alias.txt"), original.getFileName());
        EditorEngine engine = open(link);
        engine.insertInput("mine ");
        Files.delete(link);
        Files.createSymbolicLink(link, other.getFileName());

        assertTrue(engine.isCurrentSaveTarget(link), "the original spelling keeps its baseline guard");
        assertTrue(engine.isCurrentSaveTarget(original));
        assertFalse(engine.isCurrentSaveTarget(other), "the live alias must not claim another physical target");
        assertThrows(IOException.class, engine::save);
        assertThrows(IOException.class, () -> engine.saveAs(link, SaveDisposition.REPLACE_EXISTING));
        engine.saveAs(other, SaveDisposition.REPLACE_EXISTING);

        assertEquals("before", Files.readString(original));
        assertEquals("mine before", Files.readString(other));
        assertEquals(other, engine.state().getCurrentFile());
        assertFalse(engine.state().isDirty());
        assertEquals(other.getFileName(), Files.readSymbolicLink(link));
    }

    @Test
    void anOriginalPhysicalPathReplacedByASymlinkDoesNotClaimThatLinksNewTarget() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path original = write("physical-source.txt", "before");
        Path other = write("physical-other.txt", "other");
        EditorEngine engine = open(original);
        engine.insertInput("mine ");
        Files.delete(original);
        Files.createSymbolicLink(original, other.getFileName());

        assertTrue(engine.isCurrentSaveTarget(original));
        assertFalse(engine.isCurrentSaveTarget(other));
        assertThrows(IOException.class, () -> engine.saveAs(original, SaveDisposition.REPLACE_EXISTING));
        engine.saveAs(other, SaveDisposition.REPLACE_EXISTING);

        assertEquals("mine before", Files.readString(other));
        assertTrue(Files.isSymbolicLink(original));
    }

    @Test
    void saveAsCannotRecreateADeletedOwnedFileButCanStillCreateADifferentNewName() throws IOException {
        Path original = write("deleted-owned.txt", "before");
        EditorEngine engine = open(original);
        engine.insertInput("mine ");
        FileBaseline baseline = engine.model().fileBaseline();
        Files.delete(original);

        assertTrue(engine.isCurrentSaveTarget(original));
        assertThrows(IOException.class, () -> engine.saveAs(original, SaveDisposition.CREATE_NEW));
        assertFalse(Files.exists(original));
        assertEquals(baseline, engine.model().fileBaseline());
        assertTrue(engine.state().isDirty());
        Path copy = directory.resolve("deleted-owned-copy.txt");
        assertFalse(engine.isCurrentSaveTarget(copy));
        engine.saveAs(copy, SaveDisposition.CREATE_NEW);

        assertEquals("mine before", Files.readString(copy));
        assertFalse(Files.exists(original));
        assertFalse(engine.state().isDirty());
    }

    @Test
    void theMissingLoadedTargetIsGuardedEvenAfterItsOpeningAliasIsRetargeted() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path original = write("missing-baseline.txt", "before");
        Path other = write("missing-baseline-other.txt", "other");
        Path link = Files.createSymbolicLink(directory.resolve("missing-baseline-alias.txt"), original.getFileName());
        EditorEngine engine = open(link);
        engine.insertInput("mine ");
        Files.delete(original);
        Files.delete(link);
        Files.createSymbolicLink(link, other.getFileName());

        assertTrue(engine.isCurrentSaveTarget(original));
        assertThrows(IOException.class, () -> engine.saveAs(original, SaveDisposition.CREATE_NEW));
        assertFalse(Files.exists(original));
        assertEquals("other", Files.readString(other));
        assertTrue(engine.state().isDirty());
    }

    @Test
    void replacingTheOriginalRegularPathWithASymlinkCannotBypassTheIdentityCheck() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path path = write("replaced.txt", "before");
        Path other = write("replacement-target.txt", "before");
        EditorEngine engine = open(path);
        engine.insertInput("mine ");
        Files.delete(path);
        Files.createSymbolicLink(path, other);

        assertThrows(IOException.class, engine::save);

        assertEquals("before", Files.readString(other));
        assertTrue(Files.isSymbolicLink(path));
        assertTrue(engine.state().isDirty());
    }

    private Path write(String name, String content) throws IOException {
        Path path = directory.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private static EditorEngine open(Path path) throws IOException {
        EditorEngine engine = emptyEngine();
        engine.open(path);
        return engine;
    }

    private static EditorEngine emptyEngine() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        return new EditorEngine(model, new EditorState(), new TestEditorTextLayout(model));
    }
}
