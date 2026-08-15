package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.core.SaveDisposition;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.UiModalMode;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.input.KeyEvent;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SaveFlowControllerTest {
    @Test
    void acceptedPathKeepsCloseContinuationUntilSaveCompletes() {
        UiModalState saveAs = UiModalState.saveAsForCloseTab(4);

        SaveFlowController.SaveAsResult result = enter(saveAs, "saved.txt", false, null);

        assertEquals(Path.of("saved.txt"), result.saveNowPath());
        assertEquals(SaveDisposition.CREATE_NEW, result.saveDisposition());
        assertEquals(saveAs, result.modalState());
        assertEquals(SaveContinuation.CLOSE_TAB, result.modalState().saveContinuation());
    }

    @Test
    void overwriteConfirmationKeepsQuitContinuation() {
        UiModalState saveAs = UiModalState.saveAsForQuit();

        SaveFlowController.SaveAsResult result = enter(saveAs, "existing.txt", true, null);

        assertEquals(UiModalMode.SAVE_OVERWRITE_CONFIRM, result.modalState().mode());
        assertEquals(SaveContinuation.QUIT, result.modalState().saveContinuation());
        assertEquals(Path.of("existing.txt"), result.modalState().pendingOverwritePath());
    }

    @Test
    void existingCurrentFileCarriesExplicitReplaceAuthorization() {
        SaveFlowController.SaveAsResult result = SaveFlowController.handleSaveAsInput(
            new KeyEvent.ActionEvent(KeyEvent.Action.ENTER),
            UiModalState.saveAs(),
            "current.txt",
            Path.of("current.txt"),
            SaveDisposition.REPLACE_EXISTING,
            path -> null,
            path -> true,
            path -> false,
            Path::equals,
            Path::toString
        );

        assertEquals(Path.of("current.txt"), result.saveNowPath());
        assertEquals(SaveDisposition.REPLACE_EXISTING, result.saveDisposition());
    }

    @Test
    void externallyCreatedExpectedAbsentPathStillRequiresOverwriteConfirmation() {
        SaveFlowController.SaveAsResult result = SaveFlowController.handleSaveAsInput(
            new KeyEvent.ActionEvent(KeyEvent.Action.ENTER),
            UiModalState.saveAs(),
            "current.txt",
            Path.of("current.txt"),
            SaveDisposition.CREATE_NEW,
            path -> null,
            path -> true,
            path -> false,
            Path::equals,
            Path::toString
        );

        assertEquals(UiModalMode.SAVE_OVERWRITE_CONFIRM, result.modalState().mode());
        assertEquals(Path.of("current.txt"), result.modalState().pendingOverwritePath());
    }

    @Test
    void validationFailureKeepsQuitContinuationInSaveAs() {
        UiModalState saveAs = UiModalState.saveAsForQuit();

        SaveFlowController.SaveAsResult result = enter(
            saveAs,
            "missing/file.txt",
            false,
            "Save failed: directory does not exist"
        );

        assertEquals(saveAs, result.modalState());
        assertEquals(SaveContinuation.QUIT, result.modalState().saveContinuation());
    }

    @Test
    void escapeCancelsPendingContinuation() {
        SaveFlowController.SaveAsResult result = SaveFlowController.handleSaveAsInput(
            new KeyEvent.ActionEvent(KeyEvent.Action.ESCAPE),
            UiModalState.saveAsForQuit(),
            "saved.txt",
            null,
            SaveDisposition.CREATE_NEW,
            path -> null,
            path -> false,
            path -> false,
            (left, right) -> false,
            Path::toString
        );

        assertEquals(UiModalMode.NONE, result.modalState().mode());
        assertEquals(SaveContinuation.NONE, result.modalState().saveContinuation());
    }

    private static SaveFlowController.SaveAsResult enter(
        UiModalState modalState,
        String text,
        boolean exists,
        String validationError
    ) {
        return SaveFlowController.handleSaveAsInput(
            new KeyEvent.ActionEvent(KeyEvent.Action.ENTER),
            modalState,
            text,
            null,
            SaveDisposition.CREATE_NEW,
            path -> validationError,
            path -> exists,
            path -> false,
            (left, right) -> false,
            Path::toString
        );
    }
}
