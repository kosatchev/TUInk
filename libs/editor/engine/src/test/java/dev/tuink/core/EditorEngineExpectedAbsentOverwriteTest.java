package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EditorEngineExpectedAbsentOverwriteTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void onlyExplicitSaveAsReplaceCanOverwriteAConcurrentFile(boolean recovered, boolean alias) throws IOException {
        if (alias) {
            assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        }
        Path target = directory.resolve("new.txt");
        EditorEngine engine = emptyEngine();
        engine.open(JvmDocumentStorage.resource(target));
        engine.insertInput("draft");
        if (recovered) {
            RecoverySnapshot snapshot = engine.captureRecoverySnapshot();
            engine = emptyEngine();
            engine.restoreRecoverySnapshot(snapshot);
        }
        assertEquals(SaveDisposition.CREATE_NEW, engine.state().getSaveDisposition());
        assertNull(JvmDocumentStorage.baselineOf(engine.model().fileBaseline()));
        Files.writeString(target, "external");
        Path requested = alias
            ? Files.createSymbolicLink(directory.resolve("alias.txt"), target.getFileName()) : target;
        EditorEngine active = engine;

        assertThrows(FileAlreadyExistsException.class, active::save);
        assertThrows(FileAlreadyExistsException.class, () -> active.saveAs(JvmDocumentStorage.resource(requested)));
        assertEquals("external", Files.readString(target));
        assertTrue(active.state().isDirty());
        assertEquals(SaveDisposition.CREATE_NEW, active.state().getSaveDisposition());

        active.saveAs(JvmDocumentStorage.resource(requested), SaveDisposition.REPLACE_EXISTING);

        assertEquals("draft", Files.readString(target));
        assertEquals(requested, JvmDocumentStorage.pathOf(active.state().getCurrentResource()));
        assertEquals(SaveDisposition.REPLACE_EXISTING, active.state().getSaveDisposition());
        assertNotNull(JvmDocumentStorage.baselineOf(active.model().fileBaseline()));
        assertEquals(target.toRealPath(), JvmDocumentStorage.baselineOf(active.model().fileBaseline()).target());
        assertFalse(active.state().isDirty());
        if (alias) {
            assertTrue(Files.isSymbolicLink(requested));
        }
        active.insertInput("!");
        Files.writeString(target, "changed after the successful save");
        assertThrows(IOException.class, active::save);
        assertEquals("changed after the successful save", Files.readString(target));
        assertTrue(active.state().isDirty());
    }

    @Test
    void restoredExistingFileWithoutABaselineStillCannotReplaceItsCurrentPath() throws IOException {
        Path target = Files.writeString(directory.resolve("existing.txt"), "original");
        EditorEngine source = emptyEngine();
        source.open(JvmDocumentStorage.resource(target));
        source.insertInput("draft ");
        RecoverySnapshot captured = source.captureRecoverySnapshot();
        RecoverySnapshot withoutBaseline = new RecoverySnapshot(
            captured.lines(), captured.cursor(), captured.viewport(), captured.selectionAnchor(), captured.dirty(),
            captured.currentResource(), SaveDisposition.REPLACE_EXISTING, captured.fileMetadata(), null, captured.createdAt()
        );
        EditorEngine restored = emptyEngine();
        restored.restoreRecoverySnapshot(withoutBaseline);

        IOException failure = assertThrows(IOException.class,
            () -> restored.saveAs(JvmDocumentStorage.resource(target), SaveDisposition.REPLACE_EXISTING));

        assertTrue(failure.getMessage().contains("Cannot verify the original file"));
        assertEquals("original", Files.readString(target));
        assertTrue(restored.state().isDirty());
        assertEquals(SaveDisposition.REPLACE_EXISTING, restored.state().getSaveDisposition());
        assertNull(JvmDocumentStorage.baselineOf(restored.model().fileBaseline()));
        Path copy = directory.resolve("rescued.txt");
        restored.saveAs(JvmDocumentStorage.resource(copy));
        assertEquals("draft original", Files.readString(copy));
        assertEquals("original", Files.readString(target));
    }

    private static EditorEngine emptyEngine() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        return new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
    }
}
