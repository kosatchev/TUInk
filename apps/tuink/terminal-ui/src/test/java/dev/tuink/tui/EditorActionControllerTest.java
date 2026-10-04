package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.UiModalMode;
import dev.tuink.tui.flow.UiModalState;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EditorActionControllerTest {
    @Test
    void saveUsesTheCurrentFileOrPromptsForAnUnnamedOrReadOnlyDocument() {
        EditorActionController.Result existing = decide(TuinkCommand.DOCUMENT_SAVE, Path.of("file.txt"), false, false);
        EditorActionController.Result unnamed = decide(TuinkCommand.DOCUMENT_SAVE, null, false, false);
        EditorActionController.Result readOnly = decide(TuinkCommand.DOCUMENT_SAVE, Path.of("file.txt"), true, false);

        assertTrue(existing.requestSaveCurrent());
        assertEquals(UiModalMode.NONE, existing.modalState().mode());
        assertFalse(unnamed.requestSaveCurrent());
        assertEquals(UiModalMode.SAVE_AS, unnamed.modalState().mode());
        assertEquals(EditorActionController.SaveAsPreset.DEFAULT_FILE_NAME, unnamed.saveAsPreset());
        assertFalse(readOnly.requestSaveCurrent());
        assertEquals(UiModalMode.SAVE_AS, readOnly.modalState().mode());
        assertEquals(EditorActionController.SaveAsPreset.SUGGESTED_FROM_CURRENT, readOnly.saveAsPreset());
        assertEquals("File is read-only. Use Save As. file.txt", readOnly.notificationError());
    }

    @Test
    void explicitSaveAsAlwaysPromptsWithTheCurrentDocumentName() {
        EditorActionController.Result result = decide(
            TuinkCommand.DOCUMENT_SAVE_AS,
            Path.of("file.txt"),
            false,
            false
        );

        assertFalse(result.requestSaveCurrent());
        assertEquals(UiModalMode.SAVE_AS, result.modalState().mode());
        assertEquals(EditorActionController.SaveAsPreset.FROM_CURRENT_OR_DEFAULT, result.saveAsPreset());
        assertTrue(result.clearNotification());
    }

    @Test
    void dirtyDocumentsKeepTheAppRunningUntilQuitIsConfirmed() {
        EditorActionController.Result dirty = decide(TuinkCommand.APP_QUIT, null, false, true);
        EditorActionController.Result clean = decide(TuinkCommand.APP_QUIT, null, false, false);

        assertTrue(dirty.running());
        assertEquals(UiModalMode.QUIT_CONFIRM, dirty.modalState().mode());
        assertFalse(clean.running());
        assertEquals(UiModalMode.NONE, clean.modalState().mode());
    }

    @Test
    void closingADirtyTabKeepsItsIndexForTheConfirmationWorkflow() {
        EditorActionController.Result dirty = decide(TuinkCommand.TAB_CLOSE, null, false, true);
        EditorActionController.Result clean = decide(TuinkCommand.TAB_CLOSE, null, false, false);

        assertEquals(UiModalState.closeTabConfirm(4), dirty.modalState());
        assertEquals(EditorActionController.TabCommand.NONE, dirty.tabCommand());
        assertEquals(EditorActionController.TabCommand.CLOSE_TAB, clean.tabCommand());
    }

    private static EditorActionController.Result decide(
        TuinkCommand command,
        Path currentFile,
        boolean readOnly,
        boolean dirty
    ) {
        return EditorActionController.decide(
            command,
            true,
            UiModalState.none(),
            true,
            dirty,
            dirty,
            4,
            currentFile,
            readOnly
        );
    }
}
