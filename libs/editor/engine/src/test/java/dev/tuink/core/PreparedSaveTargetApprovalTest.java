package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.storage.jvm.FileBaseline;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedSaveTargetApprovalTest {
    @TempDir Path directory;

    @Test
    void ownerCaptureDoesNotResolveTheFilesystemAndInspectionNeverReadsTheLiveModel() throws Exception {
        EditorEngine engine = engine();
        engine.insertInput("captured");
        var unavailable = engine.prepareSaveAsInput(JvmDocumentStorage.resource(directory.resolve("absent-parent/file.txt")),
            SaveDisposition.CREATE_NEW, false);
        assertThrows(IOException.class, unavailable::inspectTarget);

        Path target = Files.writeString(directory.resolve("target.txt"), "before");
        var preparation = engine.prepareSaveAsInput(JvmDocumentStorage.resource(target), SaveDisposition.REPLACE_EXISTING, false);
        EditorEngine.SaveTargetInspection inspection;
        try (var worker = Executors.newSingleThreadExecutor()) {
            inspection = worker.submit(preparation::inspectTarget).get();
        }
        assertEquals("before", Files.readString(target));
        assertTrue(engine.state().isDirty());
        var approved = engine.approveSaveAs(inspection);
        SavedDocument saved;
        try (var worker = Executors.newSingleThreadExecutor()) {
            saved = worker.submit(approved::write).get();
        }
        assertTrue(engine.state().isDirty(), "worker cannot publish the savepoint");
        engine.completeSaved(approved, saved);
        assertFalse(engine.state().isDirty());
        assertEquals("captured", Files.readString(target));
    }

    @Test
    void retargetingTheInspectedLeafAliasBeforeApprovalNeverSelectsItsNewDestination() throws Exception {
        EditorEngine engine = engine();
        engine.insertInput("draft");
        Path first = Files.writeString(directory.resolve("first.txt"), "first");
        Path second = Files.writeString(directory.resolve("second.txt"), "second");
        Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), first);
        var inspection = engine.prepareSaveAsInput(JvmDocumentStorage.resource(alias), SaveDisposition.REPLACE_EXISTING, false).inspectTarget();
        Files.delete(alias);
        Files.createSymbolicLink(alias, second);

        var approved = engine.approveSaveAs(inspection);
        assertThrows(IOException.class, approved::write);

        assertEquals("first", Files.readString(first));
        assertEquals("second", Files.readString(second));
        assertEquals(second, alias.toRealPath());
        assertTrue(engine.state().isDirty());
        assertEquals(null, JvmDocumentStorage.pathOf(engine.state().getCurrentResource()));
        assertTrue(engine.undo());
        assertEquals(List.of(""), engine.model().snapshotLines());
    }

    @Test
    void retargetingAParentAliasAfterApprovalCannotChangeTheApprovedAddress() throws Exception {
        EditorEngine engine = engine();
        engine.insertInput("draft");
        Path first = Files.createDirectory(directory.resolve("first-dir"));
        Path second = Files.createDirectory(directory.resolve("second-dir"));
        Files.writeString(first.resolve("file.txt"), "first");
        Files.writeString(second.resolve("file.txt"), "second");
        Path parent = Files.createSymbolicLink(directory.resolve("parent"), first);
        var approved = engine.prepareSaveAs(JvmDocumentStorage.resource(parent.resolve("file.txt")), SaveDisposition.REPLACE_EXISTING, false);
        Files.delete(parent);
        Files.createSymbolicLink(parent, second);

        assertThrows(IOException.class, approved::write);

        assertEquals("first", Files.readString(first.resolve("file.txt")));
        assertEquals("second", Files.readString(second.resolve("file.txt")));
        assertTrue(engine.state().isDirty());
    }

    @Test
    void sameSizeChangesWithRestoredModificationTimeStillConflictWithTheApprovedBytes() throws Exception {
        EditorEngine engine = engine();
        engine.insertInput("draft");
        Path target = Files.writeString(directory.resolve("target.txt"), "before");
        var modified = Files.getLastModifiedTime(target);
        var approved = engine.prepareSaveAs(JvmDocumentStorage.resource(target), SaveDisposition.REPLACE_EXISTING, false);
        Files.writeString(target, "remote");
        Files.setLastModifiedTime(target, modified);

        assertThrows(IOException.class, approved::write);

        assertEquals("remote", Files.readString(target));
        assertTrue(engine.state().isDirty());
        assertTrue(engine.undo());
    }

    @Test
    void sameSizeChangesWhileAwaitingApprovalConflictEvenWithRestoredModificationTime() throws Exception {
        EditorEngine engine = engine();
        engine.insertInput("draft");
        Path target = Files.writeString(directory.resolve("target.txt"), "before");
        var modified = Files.getLastModifiedTime(target);
        var inspection = engine.prepareSaveAsInput(JvmDocumentStorage.resource(target),
            SaveDisposition.REPLACE_EXISTING, false).inspectTarget();
        Files.writeString(target, "remote");
        Files.setLastModifiedTime(target, modified);

        var approved = engine.approveSaveAs(inspection);
        assertThrows(IOException.class, approved::write);

        assertEquals("remote", Files.readString(target));
        assertEquals(List.of("draft"), engine.model().snapshotLines());
        assertEquals(null, JvmDocumentStorage.pathOf(engine.state().getCurrentResource()));
        assertTrue(engine.state().isDirty());
        assertTrue(engine.undo());
        assertEquals(List.of(""), engine.model().snapshotLines());
        assertTrue(engine.redo());
        assertEquals(List.of("draft"), engine.model().snapshotLines());
    }

    @Test
    void replacingTheParentDirectoryOfANewTargetRequiresFreshApproval() throws Exception {
        EditorEngine engine = engine();
        engine.insertInput("draft");
        Path parent = Files.createDirectory(directory.resolve("destination"));
        var attributes = Files.readAttributes(parent, BasicFileAttributes.class);
        assumeTrue(attributes.fileKey() != null, "provider does not expose physical directory identity");
        Path target = parent.resolve("new.txt");
        var approved = engine.prepareSaveAs(JvmDocumentStorage.resource(target), SaveDisposition.CREATE_NEW, false);
        Files.move(parent, directory.resolve("old-destination"));
        Files.createDirectory(parent);

        assertThrows(IOException.class, approved::write);

        assertFalse(Files.exists(target));
        assertTrue(engine.state().isDirty());
    }

    @Test
    void replacingTheInodeWithIdenticalBytesIsStillANewUnapprovedFile() throws Exception {
        EditorEngine engine = engine();
        engine.insertInput("draft");
        Path target = Files.writeString(directory.resolve("target.txt"), "before");
        var attributes = Files.readAttributes(target, BasicFileAttributes.class);
        assumeTrue(attributes.fileKey() != null, "provider does not expose physical file identity");
        var approved = engine.prepareSaveAs(JvmDocumentStorage.resource(target), SaveDisposition.REPLACE_EXISTING, false);
        Path replacement = Files.writeString(directory.resolve("replacement.txt"), "before");
        Files.setLastModifiedTime(replacement, attributes.lastModifiedTime());
        Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
        assertNotEquals(attributes.fileKey(), Files.readAttributes(target, BasicFileAttributes.class).fileKey());

        assertThrows(IOException.class, approved::write);

        assertEquals("before", Files.readString(target));
        assertTrue(engine.state().isDirty());
    }

    @Test
    void ownerChangesDuringInspectionRejectApprovalWithoutWritingOrLosingRedo() throws Exception {
        EditorEngine engine = engine();
        engine.insertInput("draft");
        Path target = Files.writeString(directory.resolve("target.txt"), "before");
        var inspection = engine.prepareSaveAsInput(JvmDocumentStorage.resource(target), SaveDisposition.REPLACE_EXISTING, true).inspectTarget();
        engine.insertNewline();
        assertTrue(engine.undo());

        assertThrows(IOException.class, () -> engine.approveSaveAs(inspection));

        assertEquals("before", Files.readString(target));
        assertTrue(engine.redo());
        assertTrue(engine.state().isDirty());
    }

    private static EditorEngine engine() {
        final class OwnerModel extends TextModel {
            private final Thread owner = Thread.currentThread();
            OwnerModel() { super(new SimpleLineBuffer()); }
            private void checkOwner() {
                if (Thread.currentThread() != owner) throw new AssertionError("worker read the live document");
            }
            @Override public LineText lineText(int index) { checkOwner(); return super.lineText(index); }
            @Override public FileBaseline fileBaseline() { checkOwner(); return JvmDocumentStorage.baselineOf(super.fileBaseline()); }
        }
        TextModel model = new OwnerModel();
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.setAutoPairsEnabled(false);
        engine.setAutoIndentEnabled(false);
        engine.setAutoListEnabled(false);
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        return engine;
    }
}
