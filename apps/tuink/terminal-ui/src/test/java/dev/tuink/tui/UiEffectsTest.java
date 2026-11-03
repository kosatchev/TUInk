package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.tui.flow.UiModalState;
import org.junit.jupiter.api.Test;

class UiEffectsTest {
    @Test void filenameEffectsAdvanceTheDraftRevisionOnlyWhenTheyChangeTheField() {
        try (var ui = new TabUiState()) {
            assertTrue(UiEffects.filename(ui, SaveFlowController.TextInputEffect.none()));
            assertEquals(0, ui.filenameRevision);
            assertTrue(UiEffects.filename(ui, SaveFlowController.TextInputEffect.setText("copy.md")));
            assertEquals("copy.md", ui.saveAsInput.text()); assertEquals(1, ui.filenameRevision);
            assertTrue(UiEffects.filename(ui, SaveFlowController.TextInputEffect.clear()));
            assertTrue(ui.saveAsInput.isEmpty()); assertEquals(2, ui.filenameRevision);
        }
    }

    @Test void aReceiptCannotDismissAnIndependentModalOrAnEditedFilenameDraft() {
        try (var ui = new TabUiState()) {
            ui.modalState = UiModalState.helpHints();
            UiEffects.modal(ui, UiModalState.none(), false);
            assertTrue(ui.modalState.isHelpHints());
            ui.modalState = UiModalState.saveAs();
            UiEffects.modal(ui, UiModalState.none(), true);
            assertTrue(ui.modalState.isSaveAs());
            UiEffects.modal(ui, UiModalState.none(), false);
            assertTrue(ui.modalState.isNone());
        }
    }

    @Test void aNewAlertSurvivesAnOlderSuccessOrClearButAnOperationFailureIsReported() {
        var host = new TerminalEditorApp();
        var notice = host.new NotificationState();
        notice.info("Saving…"); long submitted = notice.revision();
        notice.warning("New independent warning");
        UiEffects.notice(notice, SaveWorkflow.Notice.success("Saved"), submitted);
        assertEquals("New independent warning", notice.message);
        UiEffects.notice(notice, SaveWorkflow.Notice.clear(), submitted);
        assertEquals("New independent warning", notice.message);
        UiEffects.notice(notice, SaveWorkflow.Notice.error("Save failed"), submitted);
        assertEquals("Save failed", notice.message);
        assertTrue(notice.requiresAttention());
    }

    @Test void progressAndInformationalEffectsPreserveANewerAlert() {
        var host = new TerminalEditorApp();
        var notice = host.new NotificationState();
        notice.info("Inspecting target…"); long submitted = notice.revision();
        notice.error("New independent error");
        UiEffects.information(notice, "Writing…", submitted);
        assertEquals("New independent error", notice.message);
        notice.warning("New independent warning");
        UiEffects.information(notice, "File is already open", submitted);
        assertEquals("New independent warning", notice.message);
    }

    @Test void anUnchangedNoticeAndAnOrdinarySuccessKeepTheirExpectedSemantics() {
        var host = new TerminalEditorApp();
        var notice = host.new NotificationState();
        notice.info("Saving…"); long submitted = notice.revision();
        UiEffects.notice(notice, SaveWorkflow.Notice.unchanged(), submitted);
        assertEquals(submitted, notice.revision());
        UiEffects.notice(notice, SaveWorkflow.Notice.success("Saved"), submitted);
        assertEquals("Saved", notice.message);
        assertFalse(notice.requiresAttention());
    }
}
