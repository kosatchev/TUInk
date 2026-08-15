package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEngineSaveAsTest {
    @TempDir
    Path tempDir;

    @Test
    void failedSaveAsDoesNotChangeTheCurrentPathOrDirtyState() throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        Path original = tempDir.resolve("original.txt");
        state.setCurrentFile(original);
        state.setDirty(true);
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));

        Path regularFileUsedAsParent = tempDir.resolve("not-a-directory");
        Files.writeString(regularFileUsedAsParent, "blocking parent");
        Path impossibleTarget = regularFileUsedAsParent.resolve("target.txt");

        assertThrows(IOException.class, () -> engine.saveAs(impossibleTarget));

        assertEquals(original, state.getCurrentFile());
        assertTrue(state.isDirty());
    }

    @Test
    void successfulSaveAsPreservesBomFinalEolAndMixedSeparators() throws IOException {
        byte[] text = "one\r\ntwo\n".getBytes(StandardCharsets.UTF_8);
        byte[] original = new byte[BomKind.UTF_8.bytes().length + text.length];
        System.arraycopy(BomKind.UTF_8.bytes(), 0, original, 0, BomKind.UTF_8.bytes().length);
        System.arraycopy(text, 0, original, BomKind.UTF_8.bytes().length, text.length);
        Path source = tempDir.resolve("source.txt");
        Path target = tempDir.resolve("target.txt");
        Files.write(source, original);
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));
        engine.open(source);

        engine.saveAs(target);

        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals(target, state.getCurrentFile());
        assertFalse(state.isDirty());
    }

    @Test
    void createNewSaveAsCannotOverwriteAFileThatAppearedAfterValidation() throws IOException {
        Path target = tempDir.resolve("raced.txt");
        Files.writeString(target, "external", StandardCharsets.UTF_8);
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "editor");
        EditorState state = new EditorState();
        state.setDirty(true);
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));

        assertThrows(IOException.class, () -> engine.saveAs(target));
        assertEquals("external", Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(state.isDirty());

        engine.saveAs(target, SaveDisposition.REPLACE_EXISTING);
        assertEquals("editor", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(state.isDirty());
    }

    @Test
    void missingOpenRetainsCreateIntentUntilTheFirstSuccessfulSave() throws IOException {
        Path target = tempDir.resolve("new-document.txt");
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));
        engine.open(target);
        engine.insertInput("editor");
        assertEquals(SaveDisposition.CREATE_NEW, state.getSaveDisposition());

        Files.writeString(target, "external", StandardCharsets.UTF_8);

        assertThrows(IOException.class, engine::save);
        assertEquals("external", Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(state.isDirty());
        assertEquals(SaveDisposition.CREATE_NEW, state.getSaveDisposition());

        Files.delete(target);
        engine.save();
        assertEquals("editor", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(SaveDisposition.REPLACE_EXISTING, state.getSaveDisposition());
        assertFalse(state.isDirty());
    }

    @Test
    void recoverySnapshotPreservesExpectedAbsentSaveIntent() throws IOException {
        Path target = tempDir.resolve("recovered-new-document.txt");
        TextModel sourceModel = new TextModel(new SimpleLineBuffer());
        EditorState sourceState = new EditorState();
        EditorEngine source = new EditorEngine(
            sourceModel,
            sourceState,
            new TestEditorTextLayout(sourceModel)
        );
        source.open(target);
        source.insertInput("recovered");

        TextModel restoredModel = new TextModel(new SimpleLineBuffer());
        EditorState restoredState = new EditorState();
        EditorEngine restored = new EditorEngine(
            restoredModel,
            restoredState,
            new TestEditorTextLayout(restoredModel)
        );
        restored.restoreRecoverySnapshot(source.captureRecoverySnapshot());
        Files.writeString(target, "external", StandardCharsets.UTF_8);

        assertEquals(SaveDisposition.CREATE_NEW, restoredState.getSaveDisposition());
        assertThrows(IOException.class, restored::save);
        assertEquals("external", Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(restoredState.isDirty());
    }
}
