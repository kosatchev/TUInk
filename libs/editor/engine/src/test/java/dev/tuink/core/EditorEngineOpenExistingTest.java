package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.*;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEngineOpenExistingTest {
    @TempDir
    Path directory;

    @Test
    void missingExistingTargetPreservesDocumentMetadataViewAndBothHistoryStacks() throws IOException {
        DirtyDocument document = dirtyDocument(new TextModel(new SimpleLineBuffer()));
        RecoverySnapshot before = document.engine().captureRecoverySnapshot();
        long revision = document.model().revision();

        assertThrows(NoSuchFileException.class,
            () -> document.engine().openExisting(JvmDocumentStorage.resource(directory.resolve("missing.txt"))));

        assertUnchanged(document, before, revision);
    }

    @Test
    void disappearanceDuringLoadIsRejectedWithoutCreatingANewDocument() throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        DirtyDocument document = dirtyDocument(model);
        Path target = directory.resolve("disappearing.txt");
        Files.writeString(target, "selected contents");
        RecoverySnapshot before = document.engine().captureRecoverySnapshot();
        long revision = model.revision();

        assertTrue(Files.exists(target));
        assertThrows(NoSuchFileException.class, () -> document.engine().openExisting(disappearingResource(target)));

        assertFalse(Files.exists(target));
        assertUnchanged(document, before, revision);
    }

    @Test
    void directoryAndDecodeFailuresPreserveTheCurrentDocumentAndHistory() throws IOException {
        Path invalidUtf8 = directory.resolve("invalid.txt");
        Files.write(invalidUtf8, new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, (byte) 0xC3, 0x28});
        Path folder = Files.createDirectory(directory.resolve("folder"));
        for (Path target : List.of(folder, invalidUtf8)) {
            DirtyDocument document = dirtyDocument(new TextModel(new SimpleLineBuffer()));
            RecoverySnapshot before = document.engine().captureRecoverySnapshot();
            long revision = document.model().revision();

            assertThrows(IOException.class, () -> document.engine().openExisting(JvmDocumentStorage.resource(target)));

            assertUnchanged(document, before, revision);
        }
    }

    @Test
    void successfulExistingOpenResetsThePreviousDocumentAndHistory() throws IOException {
        DirtyDocument document = dirtyDocument(new TextModel(new SimpleLineBuffer()));
        Path target = directory.resolve("selected.txt");
        Files.writeString(target, "selected\r\ncontents");

        document.engine().openExisting(JvmDocumentStorage.resource(target));

        assertEquals(List.of("selected", "contents"), document.model().snapshotLines());
        assertEquals(target, JvmDocumentStorage.pathOf(document.state().getCurrentResource()));
        assertEquals(SaveDisposition.REPLACE_EXISTING, document.state().getSaveDisposition());
        assertEquals(new Position(0, 0), document.state().getCursor());
        assertEquals(new ViewportState(0, 0, 0, 0), document.state().viewport());
        assertFalse(document.state().hasSelection());
        assertFalse(document.state().isDirty());
        assertFalse(document.engine().undo());
        assertFalse(document.engine().redo());
    }

    @Test
    void ordinaryOpenStillCreatesANewDocumentForAMissingCliPath() throws IOException {
        DirtyDocument document = dirtyDocument(new TextModel(new SimpleLineBuffer()));
        Path target = directory.resolve("new-document.txt");

        document.engine().open(JvmDocumentStorage.resource(target));

        assertEquals(List.of(""), document.model().snapshotLines());
        assertEquals(DocumentFileMetadata.newDocument(1), document.model().fileMetadata());
        assertEquals(target, JvmDocumentStorage.pathOf(document.state().getCurrentResource()));
        assertEquals(SaveDisposition.CREATE_NEW, document.state().getSaveDisposition());
        assertFalse(document.state().isDirty());
        assertFalse(Files.exists(target));
        assertFalse(document.engine().undo());
        assertFalse(document.engine().redo());
    }

    private DirtyDocument dirtyDocument(TextModel model) throws IOException {
        Path source = directory.resolve("source.txt");
        Files.writeString(source, "\uFEFFalpha\r\nbeta\n");
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));
        engine.open(JvmDocumentStorage.resource(source));
        engine.insertInput("first");
        engine.moveRight();
        engine.insertInput("second");
        List<String> afterRedo = model.snapshotLines();
        assertTrue(engine.undo());
        engine.restoreSelection(new Position(1, 3), new Position(1, 1));
        state.setViewport(new ViewportState(1, 0, 2, 2));
        assertTrue(state.isDirty());
        return new DirtyDocument(engine, model, state, afterRedo);
    }

    private static void assertUnchanged(DirtyDocument document, RecoverySnapshot before, long revision) {
        RecoverySnapshot after = document.engine().captureRecoverySnapshot();
        assertEquals(before.lines(), after.lines());
        assertEquals(before.cursor(), after.cursor());
        assertEquals(before.viewport(), after.viewport());
        assertEquals(before.selectionAnchor(), after.selectionAnchor());
        assertEquals(JvmDocumentStorage.pathOf(before.currentResource()), JvmDocumentStorage.pathOf(after.currentResource()));
        assertEquals(before.saveDisposition(), after.saveDisposition());
        assertEquals(before.fileMetadata(), after.fileMetadata());
        assertEquals(JvmDocumentStorage.baselineOf(before.fileBaseline()), JvmDocumentStorage.baselineOf(after.fileBaseline()));
        assertEquals(before.dirty(), after.dirty());
        assertEquals(before.fileMetadata().charset(), document.state().getFileEncoding());
        assertEquals(before.fileMetadata().defaultLineSeparator(), document.state().getLineSeparator());
        assertEquals(revision, document.model().revision());
        assertTrue(document.engine().redo());
        assertEquals(document.afterRedo(), document.model().snapshotLines());
        assertTrue(document.engine().undo());
        assertEquals(before.lines(), document.model().snapshotLines());
        assertTrue(document.engine().undo());
        assertEquals(List.of("alpha", "beta", ""), document.model().snapshotLines());
        assertFalse(document.state().isDirty());
    }

    private record DirtyDocument(EditorEngine engine, TextModel model, EditorState state, List<String> afterRedo) {}

    private static DocumentResource disappearingResource(Path path) {
        DocumentResource selected = JvmDocumentStorage.resource(path);
        DocumentStorage storage = new DocumentStorage() {
            @Override public DocumentLoad load(DocumentResource resource, DocumentOpenMode mode, boolean allowMissing)
                throws IOException {
                Files.delete(path);
                return selected.storage().load(selected, mode, allowMissing);
            }
            @Override public SaveTarget inspect(DocumentResource resource, ResourceBinding current) {
                throw new AssertionError("Opening must not inspect a save target");
            }
            @Override public boolean isCurrentTarget(DocumentResource resource, ResourceBinding current) {
                throw new AssertionError("Opening must not inspect a save target");
            }
            @Override public SavedDocument write(DocumentSave request, SaveAuthorization authorization) {
                throw new AssertionError("Opening must not write a resource");
            }
        };
        return new DocumentResource() {
            @Override public ResourceId id() { return selected.id(); }
            @Override public String displayName() { return selected.displayName(); }
            @Override public String nameHint() { return selected.nameHint(); }
            @Override public DocumentStorage storage() { return storage; }
        };
    }
}
