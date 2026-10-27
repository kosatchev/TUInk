package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.UiModalState;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SaveFlowControllerTest {
    @Test
    void absentSaveIntentKeepsTheWorkflowWithoutEditingText() {
        UiModalState modal = UiModalState.saveAsForQuit();
        var result = input(null, modal, "saved.txt");
        assertTrue(result.consumed());
        assertSame(modal, result.modalState());
        assertEquals(SaveFlowController.TextInputEffectKind.NONE, result.textInputEffect().kind());
        assertNull(result.saveNowPath());
    }

    @Test
    void quitCommandCancelsSaveAsWithoutExecutingThePendingContinuation() {
        var result = input(TuinkCommand.APP_QUIT, UiModalState.saveAsForQuit(), "saved.txt");
        assertEquals(UiModalState.none(), result.modalState());
        assertEquals(SaveContinuation.NONE, result.modalState().saveContinuation());
        assertEquals(SaveFlowController.TextInputEffectKind.CLEAR, result.textInputEffect().kind());
        assertNull(result.saveNowPath());
    }

    @Test
    void acceptedPathKeepsCloseContinuationUntilSaveCompletes() {
        UiModalState modal = UiModalState.saveAsForCloseTab(4);
        var result = enter(modal, "saved.txt");
        assertEquals(Path.of("saved.txt"), result.saveNowPath());
        assertSame(modal, result.modalState());
        assertEquals(SaveContinuation.CLOSE_TAB, result.modalState().saveContinuation());
    }

    @Test
    void saveAsPreservesLeadingTrailingAndOnlySpacesInFileNames() {
        for (String filename : List.of(" report.txt", "report.txt ", " report.txt ", " ")) {
            assertEquals(Path.of(filename), enter(UiModalState.saveAs(), filename).saveNowPath(), filename);
        }
    }

    @Test
    void emptyFilenameReportsValidationErrorWithoutClearingAnySaveWorkflow() {
        for (UiModalState modal : saveAsStates()) {
            var result = enter(modal, "");
            assertTrue(result.consumed());
            assertSame(modal, result.modalState());
            assertEquals("Save failed: file name is empty", result.errorMessage());
            assertFalse(result.clearNotification());
            assertEquals(SaveFlowController.TextInputEffectKind.NONE, result.textInputEffect().kind());
            assertNull(result.saveNowPath());
        }
    }

    @Test
    void validFilenameAfterEmptySubmissionRetainsThePendingSaveContinuation() {
        for (UiModalState modal : saveAsStates()) {
            var rejected = enter(modal, "");
            var corrected = enter(rejected.modalState(), "corrected-中.txt");
            assertSame(modal, corrected.modalState());
            assertEquals(Path.of("corrected-中.txt"), corrected.saveNowPath());
            assertNull(corrected.errorMessage());
        }
    }

    @Test
    void cancelAfterEmptySubmissionStillCancelsStandaloneQuitAndCloseTabFlows() {
        for (UiModalState modal : saveAsStates()) {
            for (TuinkCommand cancel : List.of(TuinkCommand.UI_ESCAPE, TuinkCommand.APP_QUIT)) {
                var rejected = enter(modal, "");
                var result = input(cancel, rejected.modalState(), "");
                assertEquals(UiModalState.none(), result.modalState());
                assertEquals(-1, result.modalState().pendingCloseTabIndex());
                assertEquals(SaveFlowController.TextInputEffectKind.CLEAR, result.textInputEffect().kind());
                assertTrue(result.clearNotification());
                assertNull(result.errorMessage());
                assertNull(result.saveNowPath());
            }
        }
    }

    @Test
    void invalidPathRetainsTheFilenameAndPendingQuit() {
        UiModalState modal = UiModalState.saveAsForQuit();
        var result = enter(modal, "invalid\0name");
        assertSame(modal, result.modalState());
        assertEquals("Save failed: invalid target path", result.errorMessage());
        assertNull(result.saveNowPath());
        assertEquals(SaveFlowController.TextInputEffectKind.NONE, result.textInputEffect().kind());
    }

    @Test
    void directoryEndpointsAreRejectedLexically() {
        for (String name : List.of(".", "..", Path.of("/").toAbsolutePath().toString())) {
            var result = enter(UiModalState.saveAs(), name);
            assertNull(result.saveNowPath(), name);
            assertTrue(result.errorMessage().contains("target is a directory"), name);
        }
    }

    @Test
    void aValidInputDoesNotNeedItsParentToExistOnTheOwner() {
        UiModalState modal = UiModalState.saveAsForQuit();
        var result = enter(modal, "missing-parent/saved.txt");
        assertSame(modal, result.modalState());
        assertEquals(Path.of("missing-parent/saved.txt"), result.saveNowPath());
        assertNull(result.errorMessage(), "resource inspection belongs to the worker");
    }

    @Test
    void inputOutsideSaveAsIsNotConsumedOrTurnedIntoASave() {
        var result = input(TuinkCommand.EDIT_INSERT_NEWLINE, UiModalState.none(), "saved.txt");
        assertFalse(result.consumed());
        assertEquals(UiModalState.none(), result.modalState());
        assertNull(result.saveNowPath());
    }

    private static SaveFlowController.SaveAsResult input(TuinkCommand command, UiModalState modal, String text) {
        return SaveFlowController.handleSaveAsInput(command, modal, text);
    }

    private static SaveFlowController.SaveAsResult enter(UiModalState modal, String text) {
        return input(TuinkCommand.EDIT_INSERT_NEWLINE, modal, text);
    }

    private static List<UiModalState> saveAsStates() {
        return List.of(UiModalState.saveAs(), UiModalState.saveAsForQuit(), UiModalState.saveAsForCloseTab(4));
    }
}
