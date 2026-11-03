package dev.tuink.tui;

import dev.tuink.tui.flow.UiModalState;

/** Small widget effects shared by live and frozen workflows; owns no storage or window state. */
final class UiEffects {
    private UiEffects() {}

    static boolean filename(TabUiState ui, SaveFlowController.TextInputEffect effect) {
        switch (effect.kind()) {
            case NONE -> { }
            case CLEAR -> { ui.saveAsInput.clear(); ui.filenameRevision++; }
            case SET_TEXT -> {
                if (!ui.saveAsInput.set(effect.text())) return false;
                ui.filenameRevision++;
            }
        }
        return true;
    }

    static boolean preserveModal(UiModalState current, UiModalState next, boolean preserveFilename) {
        boolean independent = !current.isNone() && !current.isSaveAs()
            && !current.isSaveOverwriteConfirm() && !current.isSaveUtf8Confirm();
        return next.isNone() && (independent || preserveFilename && current.isSaveAs());
    }

    static void modal(TabUiState ui, UiModalState next, boolean preserveFilename) {
        if (!preserveModal(ui.modalState, next, preserveFilename)) ui.modalState = next;
    }

    static void focusFilename(TabUiState ui) {
        ui.blockingContentFocused = false;
        ui.saveAsFocusManager.deactivate(TerminalEditorApp.SAVE_AS_SCOPE_ID);
    }

    static boolean preserveNotice(TerminalEditorApp.NotificationState ui, long submittedRevision) {
        return submittedRevision >= 0 && ui.revision() != submittedRevision && ui.requiresAttention();
    }

    static void information(TerminalEditorApp.NotificationState ui, String message, long submittedRevision) {
        if (!preserveNotice(ui, submittedRevision) && !message.equals(ui.message)) ui.info(message);
    }

    static void notice(TerminalEditorApp.NotificationState ui, SaveWorkflow.Notice effect, long submittedRevision) {
        if (preserveNotice(ui, submittedRevision) && effect.kind() != SaveWorkflow.NoticeKind.ERROR) return;
        switch (effect.kind()) {
            case UNCHANGED -> { }
            case CLEAR -> ui.clear();
            case SUCCESS -> ui.success(effect.message());
            case ERROR -> ui.error(effect.message());
        }
    }
}
