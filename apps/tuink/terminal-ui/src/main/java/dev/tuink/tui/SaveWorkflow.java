package dev.tuink.tui;

import dev.tuink.core.SaveDisposition;
import dev.tuink.core.TabSession;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.UiModalState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Stateless application save transitions. The window applies effects to its widgets and tabs. */
final class SaveWorkflow {
    private SaveWorkflow() {}

    record Context(List<TabSession> tabs, int activeTabIndex, SaveAsSuggestionStore suggestions) {
        TabSession active() {
            return tabs.get(activeTabIndex);
        }
    }

    enum NoticeKind { UNCHANGED, CLEAR, SUCCESS, ERROR }

    record Notice(NoticeKind kind, String message) {
        static Notice unchanged() { return new Notice(NoticeKind.UNCHANGED, null); }
        static Notice clear() { return new Notice(NoticeKind.CLEAR, null); }
        static Notice success(String text) { return new Notice(NoticeKind.SUCCESS, text); }
        static Notice error(String text) { return new Notice(NoticeKind.ERROR, text); }
    }

    record Result(boolean running, UiModalState modalState, int activateTabIndex, int closeTabIndex,
                  SaveFlowController.TextInputEffect filename, Notice notice,
                  boolean refreshSavedHighlighter, boolean focusFilename) {}

    static SaveFlowController.TextInputEffect initialFilename(Context context, EditorActionController.SaveAsPreset preset) {
        if (preset == EditorActionController.SaveAsPreset.NONE) {
            return SaveFlowController.TextInputEffect.none();
        }
        TabSession tab = context.active();
        String suggestion;
        if (preset == EditorActionController.SaveAsPreset.SUGGESTED_FROM_CURRENT) {
            suggestion = context.suggestions().suggestionFor(tab, null,
                SaveTargetPolicy.suggestWritableName(tab.state().getCurrentFile()), SaveTargetPolicy::isSuitableDraft);
            context.suggestions().rememberDraft(tab, suggestion);
        } else {
            suggestion = context.suggestions().suggestionFor(tab, tab.state().getCurrentFile(),
                SaveTargetPolicy.DEFAULT_FILE_NAME);
        }
        return SaveFlowController.TextInputEffect.setText(suggestion);
    }

    static Result saveCurrent(Context context) {
        TabSession tab = context.active();
        try {
            tab.engine().save();
            context.suggestions().rememberSavedPath(tab, tab.state().getCurrentFile());
            return result(UiModalState.none(), Notice.success(SaveTargetPolicy.savedMessage(tab.state())));
        } catch (IOException error) {
            return result(UiModalState.none(), Notice.error(SaveTargetPolicy.saveErrorMessage(error)));
        }
    }

    static Result saveAsInput(Context context, TuinkCommand command, UiModalState modal, String draft) {
        TabSession tab = context.active();
        SaveFlowController.SaveAsResult decision = SaveFlowController.handleSaveAsInput(command, modal, draft,
            tab.state().getCurrentFile(), tab.state().getSaveDisposition(), SaveTargetPolicy::validateSaveTarget,
            Files::exists, SaveTargetPolicy::isReadOnlyFile, SaveTargetPolicy::isSameFilePath,
            SaveTargetPolicy::suggestWritableName);
        String nextDraft = switch (decision.textInputEffect().kind()) {
            case NONE -> draft;
            case SET_TEXT -> decision.textInputEffect().text();
            case CLEAR -> "";
        };
        context.suggestions().rememberDraft(tab, decision.modalState().isSaveAs() ? nextDraft : draft);
        if (decision.saveNowPath() != null) {
            return saveAs(context, modal, decision.saveNowPath(), decision.saveDisposition());
        }
        Notice notice = decision.errorMessage() != null ? Notice.error(decision.errorMessage())
            : decision.clearNotification() ? Notice.clear() : Notice.unchanged();
        return new Result(true, decision.modalState(), -1, -1, decision.textInputEffect(), notice,
            false, decision.errorMessage() != null);
    }

    static Result overwrite(Context context, UiModalState modal, ConfirmChoice choice) {
        return switch (choice) {
            case SAVE -> saveAs(context, modal, modal.pendingOverwritePath(), SaveDisposition.REPLACE_EXISTING);
            case DISCARD -> result(modal.backToSaveAs(), Notice.clear());
            case CANCEL -> result(UiModalState.none(), Notice.clear());
        };
    }

    private static Result saveAs(Context context, UiModalState modal, Path target, SaveDisposition disposition) {
        TabSession tab = context.active();
        try {
            tab.engine().saveAs(target, disposition);
            context.suggestions().rememberSavedPath(tab, tab.state().getCurrentFile());
            Notice notice = Notice.success(SaveTargetPolicy.savedMessage(tab.state()));
            Result continuation = afterSuccessfulSave(context, modal, notice);
            return new Result(continuation.running(), continuation.modalState(), continuation.activateTabIndex(),
                continuation.closeTabIndex(), continuation.filename(), continuation.notice(), true,
                continuation.focusFilename());
        } catch (IOException error) {
            return new Result(true, modal, -1, -1, SaveFlowController.TextInputEffect.none(),
                Notice.error(SaveTargetPolicy.saveErrorMessage(error)), false, modal.isSaveAs());
        }
    }

    private static Result afterSuccessfulSave(Context context, UiModalState modal, Notice notice) {
        if (modal.saveContinuation() == SaveContinuation.QUIT) {
            return continueQuit(context, notice, SaveFlowController.TextInputEffect.clear());
        }
        int closeIndex = modal.saveContinuation() == SaveContinuation.CLOSE_TAB
            && validIndex(context, modal.pendingCloseTabIndex()) ? modal.pendingCloseTabIndex() : -1;
        return new Result(true, UiModalState.none(), -1, closeIndex, SaveFlowController.TextInputEffect.clear(),
            notice, false, false);
    }

    static Result quit(Context context, ConfirmChoice choice) {
        return switch (choice) {
            case SAVE -> continueQuit(context, Notice.unchanged(), SaveFlowController.TextInputEffect.none());
            case DISCARD -> new Result(false, UiModalState.none(), -1, -1,
                SaveFlowController.TextInputEffect.none(), Notice.unchanged(), false, false);
            case CANCEL -> result(UiModalState.none(), Notice.unchanged());
        };
    }

    private static Result continueQuit(Context context, Notice notice, SaveFlowController.TextInputEffect filename) {
        // Resolve every required filename before saving named tabs, preserving retry/cancel semantics.
        for (int index = 0; index < context.tabs().size(); index++) {
            TabSession tab = context.tabs().get(index);
            if (tab.state().isDirty() && SaveTargetPolicy.requiresSaveAs(tab.state().getCurrentFile())) {
                return prepareSaveAs(context, index, UiModalState.saveAsForQuit());
            }
        }
        for (int index = 0; index < context.tabs().size(); index++) {
            String error = saveDirty(context, index);
            if (error != null) {
                return new Result(true, UiModalState.quitConfirm(), index, -1,
                    filename, Notice.error(error), false, false);
            }
        }
        return new Result(false, UiModalState.none(), -1, -1, filename,
            notice, false, false);
    }

    static Result closeTab(Context context, UiModalState modal, ConfirmChoice choice) {
        int index = modal.pendingCloseTabIndex();
        if (!validIndex(context, index) || choice == ConfirmChoice.CANCEL) {
            return result(UiModalState.none(), Notice.unchanged());
        }
        if (choice == ConfirmChoice.SAVE) {
            if (SaveTargetPolicy.requiresSaveAs(context.tabs().get(index).state().getCurrentFile())) {
                return prepareSaveAs(context, index, UiModalState.saveAsForCloseTab(index));
            }
            String error = saveDirty(context, index);
            if (error != null) {
                return result(modal, Notice.error(error));
            }
        }
        return new Result(true, UiModalState.none(), -1, index, SaveFlowController.TextInputEffect.none(),
            Notice.unchanged(), false, false);
    }

    private static Result prepareSaveAs(Context context, int index, UiModalState modal) {
        TabSession tab = context.tabs().get(index);
        Path file = tab.state().getCurrentFile();
        String suggestion = file == null
            ? context.suggestions().suggestionFor(tab, null, SaveTargetPolicy.DEFAULT_FILE_NAME)
            : context.suggestions().suggestionFor(tab, null, SaveTargetPolicy.suggestWritableName(file),
                SaveTargetPolicy::isSuitableDraft);
        context.suggestions().rememberDraft(tab, suggestion);
        return new Result(true, modal, index, -1, SaveFlowController.TextInputEffect.setText(suggestion),
            Notice.clear(), false, true);
    }

    private static String saveDirty(Context context, int index) {
        TabSession tab = context.tabs().get(index);
        if (!tab.state().isDirty()) {
            return null;
        }
        Path file = tab.state().getCurrentFile();
        if (file == null) {
            return "Save failed: tab has no file path. Use Save As.";
        }
        if (SaveTargetPolicy.isReadOnlyFile(file)) {
            return "File is read-only. Use Save As. " + file;
        }
        try {
            tab.engine().save();
            context.suggestions().rememberSavedPath(tab, file);
            return null;
        } catch (IOException error) {
            return SaveTargetPolicy.saveErrorMessage(error);
        }
    }

    private static boolean validIndex(Context context, int index) {
        return index >= 0 && index < context.tabs().size();
    }

    private static Result result(UiModalState modal, Notice notice) {
        return new Result(true, modal, -1, -1, SaveFlowController.TextInputEffect.none(), notice, false, false);
    }
}
