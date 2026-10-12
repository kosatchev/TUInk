package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.SaveDisposition;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.UiModalMode;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.input.EditorInput;
import dev.tuiframe.input.KeyStroke;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SaveFlowControllerTest {
    @Test
    void printableUnicodeInputPreservesTheCompleteFilenameTextAndContinuation() {
        UiModalState saveAs = UiModalState.saveAsForCloseTab(4);
        String filename = "café中😀.txt";

        SaveFlowController.SaveAsResult result = input(EditorInput.text(filename), saveAs);

        assertTrue(result.consumed());
        assertEquals(saveAs, result.modalState());
        assertEquals(SaveFlowController.TextInputEffectKind.INSERT, result.textInputEffect().kind());
        assertEquals(filename, result.textInputEffect().text());
        assertNull(result.saveNowPath());
    }

    @Test
    void unboundPhysicalInputKeepsTheSaveAsWorkflowWithoutEditingText() {
        UiModalState saveAs = UiModalState.saveAsForQuit();

        SaveFlowController.SaveAsResult result = input(
            new EditorInput(KeyStroke.of(KeyStroke.Key.F1), null),
            saveAs
        );

        assertTrue(result.consumed());
        assertEquals(saveAs, result.modalState());
        assertEquals(SaveFlowController.TextInputEffectKind.NONE, result.textInputEffect().kind());
        assertNull(result.saveNowPath());
    }

    @Test
    void quitCommandCancelsSaveAsWithoutExecutingThePendingContinuation() {
        SaveFlowController.SaveAsResult result = input(
            EditorInput.command(TuinkCommand.APP_QUIT),
            UiModalState.saveAsForQuit()
        );

        assertEquals(UiModalMode.NONE, result.modalState().mode());
        assertEquals(SaveContinuation.NONE, result.modalState().saveContinuation());
        assertEquals(SaveFlowController.TextInputEffectKind.CLEAR, result.textInputEffect().kind());
        assertNull(result.saveNowPath());
    }

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
    void saveAsPreservesLeadingTrailingAndOnlySpacesInFileNames() {
        for (String filename : new String[] {" report.txt", "report.txt ", " report.txt ", " "}) {
            SaveFlowController.SaveAsResult result = enter(UiModalState.saveAs(), filename, false, null);

            assertEquals(Path.of(filename), result.saveNowPath(), filename);
            assertEquals(SaveDisposition.CREATE_NEW, result.saveDisposition(), filename);
        }
    }

    @Test
    void overwriteConfirmationKeepsTheExactWhitespaceInTheTargetName() {
        String filename = " existing.txt ";

        SaveFlowController.SaveAsResult result = enter(UiModalState.saveAs(), filename, true, null);

        assertEquals(UiModalMode.SAVE_OVERWRITE_CONFIRM, result.modalState().mode());
        assertEquals(Path.of(filename), result.modalState().pendingOverwritePath());
    }

    @Test
    void emptyFilenameReportsValidationErrorWithoutClearingAnySaveWorkflow() {
        for (UiModalState saveAs : saveAsStates()) {
            SaveFlowController.SaveAsResult result = enter(saveAs, "", false, null);

            assertTrue(result.consumed());
            assertSame(saveAs, result.modalState());
            assertEquals(UiModalMode.SAVE_AS, result.modalState().mode());
            assertEquals(saveAs.saveContinuation(), result.modalState().saveContinuation());
            assertEquals(saveAs.pendingCloseTabIndex(), result.modalState().pendingCloseTabIndex());
            assertEquals("Save failed: file name is empty", result.errorMessage());
            assertFalse(result.clearNotification());
            assertEquals(SaveFlowController.TextInputEffectKind.NONE, result.textInputEffect().kind());
            assertNull(result.textInputEffect().text());
            assertNull(result.saveNowPath());
            assertNull(result.saveDisposition());
        }
    }

    @Test
    void validFilenameAfterEmptySubmissionRetainsThePendingSaveContinuation() {
        for (UiModalState saveAs : saveAsStates()) {
            SaveFlowController.SaveAsResult rejected = enter(saveAs, "", false, null);

            SaveFlowController.SaveAsResult corrected = enter(rejected.modalState(), "corrected-中.txt", false, null);

            assertTrue(corrected.consumed());
            assertSame(saveAs, corrected.modalState());
            assertEquals(Path.of("corrected-中.txt"), corrected.saveNowPath());
            assertEquals(SaveDisposition.CREATE_NEW, corrected.saveDisposition());
            assertNull(corrected.errorMessage());
            assertEquals(SaveFlowController.TextInputEffectKind.NONE, corrected.textInputEffect().kind());
        }
    }

    @Test
    void cancelAfterEmptySubmissionStillCancelsStandaloneQuitAndCloseTabFlows() {
        for (UiModalState saveAs : saveAsStates()) {
            for (TuinkCommand cancel : List.of(TuinkCommand.UI_ESCAPE, TuinkCommand.APP_QUIT)) {
                SaveFlowController.SaveAsResult rejected = enter(saveAs, "", false, null);

                SaveFlowController.SaveAsResult cancelled = input(EditorInput.command(cancel), rejected.modalState());

                assertTrue(cancelled.consumed());
                assertEquals(UiModalState.none(), cancelled.modalState());
                assertEquals(SaveContinuation.NONE, cancelled.modalState().saveContinuation());
                assertEquals(-1, cancelled.modalState().pendingCloseTabIndex());
                assertEquals(SaveFlowController.TextInputEffectKind.CLEAR, cancelled.textInputEffect().kind());
                assertTrue(cancelled.clearNotification());
                assertNull(cancelled.errorMessage());
                assertNull(cancelled.saveNowPath());
                assertNull(cancelled.saveDisposition());
            }
        }
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
            EditorInput.command(TuinkCommand.EDIT_INSERT_NEWLINE),
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
            EditorInput.command(TuinkCommand.EDIT_INSERT_NEWLINE),
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
            EditorInput.command(TuinkCommand.UI_ESCAPE),
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

    private static SaveFlowController.SaveAsResult input(EditorInput input, UiModalState modalState) {
        return SaveFlowController.handleSaveAsInput(
            input,
            modalState,
            "saved.txt",
            null,
            SaveDisposition.CREATE_NEW,
            path -> null,
            path -> false,
            path -> false,
            (left, right) -> false,
            Path::toString
        );
    }

    private static List<UiModalState> saveAsStates() {
        return List.of(UiModalState.saveAs(), UiModalState.saveAsForQuit(), UiModalState.saveAsForCloseTab(4));
    }

    private static SaveFlowController.SaveAsResult enter(
        UiModalState modalState,
        String text,
        boolean exists,
        String validationError
    ) {
        return SaveFlowController.handleSaveAsInput(
            EditorInput.command(TuinkCommand.EDIT_INSERT_NEWLINE),
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
