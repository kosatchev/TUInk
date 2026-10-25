package dev.tuink.tui;

import dev.tuink.core.SaveDisposition;
import dev.tuink.core.DocumentEncodingException;
import dev.tuink.core.TabSession;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.TextModel;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.DocumentSaveFormat;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.SaveTargetSuggester;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.ui.PathPresentation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Stateless application save transitions. The window applies effects to its widgets and tabs. */
final class SaveWorkflow {
    private SaveWorkflow() {}

    record Context(List<TabSession> tabs, int activeTabIndex, SaveAsSuggestionStore suggestions,
                   OverwriteApproval overwriteApproval) {
        Context(List<TabSession> tabs, int activeTabIndex, SaveAsSuggestionStore suggestions) {
            this(tabs, activeTabIndex, suggestions, null);
        }
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
                  boolean refreshSavedHighlighter, boolean focusFilename, SaveRequest pendingSave,
                  OverwriteApproval overwriteApproval) {
        Result(boolean running, UiModalState modalState, int activateTabIndex, int closeTabIndex,
               SaveFlowController.TextInputEffect filename, Notice notice,
               boolean refreshSavedHighlighter, boolean focusFilename, SaveRequest pendingSave) {
            this(running, modalState, activateTabIndex, closeTabIndex, filename, notice,
                refreshSavedHighlighter, focusFilename, pendingSave, null);
        }
        Result(boolean running, UiModalState modalState, int activateTabIndex, int closeTabIndex,
               SaveFlowController.TextInputEffect filename, Notice notice,
               boolean refreshSavedHighlighter, boolean focusFilename) {
            this(running, modalState, activateTabIndex, closeTabIndex, filename, notice,
                refreshSavedHighlighter, focusFilename, null);
        }
    }

    enum SavePurpose { CURRENT, SAVE_AS, QUIT, CLOSE_TAB }

    sealed interface SaveOperation permits InspectTarget, WriteFile {}
    record InspectTarget(EditorEngine.SavePreparation preparation) implements SaveOperation {}
    record WriteFile(EditorEngine.PreparedSave prepared) implements SaveOperation {}

    /** Captured owner state is inspected first, approved on the owner, then written detached. */
    record SaveSubmission(String filename, long filenameRevision) {}

    record SaveRequest(TabSession tab, SaveOperation operation, UiModalState origin, SavePurpose purpose,
                       SaveSubmission submission) {
        SaveRequest(TabSession tab, SaveOperation operation, UiModalState origin, SavePurpose purpose) {
            this(tab, operation, origin, purpose, null);
        }
        SaveRequest(TabSession tab, EditorEngine.PreparedSave prepared, UiModalState origin, SavePurpose purpose) {
            this(tab, new WriteFile(prepared), origin, purpose, null);
        }

        boolean inspecting() { return operation instanceof InspectTarget; }

        SaveRequest submitted(String filename, long revision) {
            return new SaveRequest(tab, operation, origin, purpose, new SaveSubmission(filename, revision));
        }

        Object perform() throws IOException {
            return operation instanceof InspectTarget check ? check.preparation().inspectTarget()
                : ((WriteFile) operation).prepared().write();
        }
    }

    record OverwriteApproval(SaveRequest request, EditorEngine.SaveTargetInspection inspection) {}

    static SaveFlowController.TextInputEffect initialFilename(Context context, EditorActionController.SaveAsPreset preset) {
        if (preset == EditorActionController.SaveAsPreset.NONE) {
            return SaveFlowController.TextInputEffect.none();
        }
        String suggestion = suggestFilename(context, context.activeTabIndex(),
            preset == EditorActionController.SaveAsPreset.SUGGESTED_FROM_CURRENT);
        return SaveFlowController.TextInputEffect.setText(suggestion);
    }

    static Result saveCurrent(Context context) {
        TabSession tab = context.active();
        try {
            return pending(new SaveRequest(tab, tab.engine().prepareSave(), UiModalState.none(), SavePurpose.CURRENT));
        } catch (IOException error) {
            if (error instanceof DocumentEncodingException) {
                return offerUtf8(context, context.activeTabIndex(), UiModalState.saveAs(), error, true);
            }
            return result(UiModalState.none(), Notice.error(SaveTargetPolicy.saveErrorMessage(error)));
        }
    }

    static Result saveAsInput(Context context, TuinkCommand command, UiModalState modal, String draft) {
        TabSession tab = context.active();
        SaveFlowController.SaveAsResult decision = SaveFlowController.handleSaveAsInput(command, modal, draft,
            target -> validateSaveAsTarget(context, target),
            Files::exists, SaveTargetPolicy::isReadOnlyFile, tab.engine()::isCurrentSaveTarget,
            target -> suggestWritableName(context, context.activeTabIndex(), target));
        context.suggestions().rememberInput(tab, draft);
        if (decision.textInputEffect().kind() == SaveFlowController.TextInputEffectKind.SET_TEXT) {
            context.suggestions().rememberGenerated(tab, decision.textInputEffect().text());
        }
        if (decision.saveNowPath() != null) {
            return saveAs(context, modal, decision.saveNowPath(), decision.saveDisposition());
        }
        if (decision.modalState().isSaveOverwriteConfirm()) {
            return saveAs(context, decision.modalState(), decision.modalState().pendingOverwritePath(),
                SaveDisposition.REPLACE_EXISTING);
        }
        Notice notice = decision.errorMessage() != null ? Notice.error(decision.errorMessage())
            : decision.clearNotification() ? Notice.clear() : Notice.unchanged();
        return new Result(true, decision.modalState(), -1, -1, decision.textInputEffect(), notice,
            false, decision.errorMessage() != null);
    }

    static Result overwrite(Context context, UiModalState modal, ConfirmChoice choice) {
        return switch (choice) {
            case SAVE -> approveOverwrite(context, modal);
            case DISCARD -> result(modal.backToSaveAs(), Notice.clear());
            case CANCEL -> result(UiModalState.none(), Notice.clear());
        };
    }

    private static Result approveOverwrite(Context context, UiModalState modal) {
        OverwriteApproval approval = context.overwriteApproval();
        if (approval == null || approval.request().tab() != context.active()
            || !approval.inspection().requestedPath().equals(modal.pendingOverwritePath())) {
            return result(modal.backToSaveAs(), Notice.error("Save target was not inspected. Choose the target again."));
        }
        return approveTarget(context, approval.request(), approval.inspection(), modal);
    }

    static Result encodingChoice(UiModalState modal, ConfirmChoice choice) {
        return choice == ConfirmChoice.SAVE
            ? new Result(true, modal.utf8SaveAs(), -1, -1, SaveFlowController.TextInputEffect.none(),
                Notice.clear(), false, true)
            : result(UiModalState.none(), Notice.clear());
    }

    private static Result saveAs(Context context, UiModalState modal, Path target, SaveDisposition disposition) {
        TabSession tab = context.active();
        // Reject known workspace owners before starting the detached inspection.
        String conflict = workspaceSaveConflict(context, target);
        if (conflict != null) {
            UiModalState retry = modal.isSaveOverwriteConfirm() ? modal.backToSaveAs() : modal;
            return new Result(true, retry, -1, -1, SaveFlowController.TextInputEffect.none(),
                Notice.error(conflict), false, true);
        }
        try {
            return pending(new SaveRequest(tab,
                new InspectTarget(tab.engine().prepareSaveAsInput(target, disposition,
                    modal.saveFormat() == DocumentSaveFormat.UTF8)),
                modal, SavePurpose.SAVE_AS));
        } catch (IOException error) {
            if (error instanceof DocumentEncodingException && modal.saveFormat() != DocumentSaveFormat.UTF8) {
                return offerUtf8(context, context.activeTabIndex(), modal, error, false);
            }
            return new Result(true, modal, -1, -1, SaveFlowController.TextInputEffect.none(),
                Notice.error(SaveTargetPolicy.saveErrorMessage(error)), false, modal.isSaveAs());
        }
    }

    static Result complete(Context context, SaveRequest request, Object completed, IOException failure) {
        int index = context.tabs().indexOf(request.tab());
        if (index < 0) {
            return result(UiModalState.none(), Notice.error("Save completed for a document that is no longer open"));
        }
        UiModalState origin = remapOrigin(request.origin(), index);
        if (request.inspecting() && failure == null) {
            EditorEngine.SaveTargetInspection inspection = (EditorEngine.SaveTargetInspection) completed;
            Context owner = new Context(context.tabs(), index, context.suggestions());
            String conflict = workspaceSaveConflict(owner, inspection.physicalTarget());
            if (conflict != null) {
                return new Result(true, saveAsOrigin(origin), index, -1, SaveFlowController.TextInputEffect.none(),
                    Notice.error(conflict), false, true);
            }
            if (((InspectTarget) request.operation()).preparation().disposition() == SaveDisposition.CREATE_NEW
                && inspection.exists()) {
                return new Result(true, saveAsOrigin(origin), index, -1, SaveFlowController.TextInputEffect.none(),
                    Notice.error("Save failed: target appeared while it was being inspected. Choose the target again."),
                    false, true);
            }
            if (inspection.exists() && !inspection.currentTarget()) {
                UiModalState confirmation = saveAsOrigin(origin).confirmOverwrite(inspection.requestedPath());
                return new Result(true, confirmation, index, -1, SaveFlowController.TextInputEffect.none(),
                    Notice.clear(), false, false, null, new OverwriteApproval(request, inspection));
            }
            return approveTarget(owner, request, inspection, saveAsOrigin(origin));
        }
        if (failure != null) {
            if (failure instanceof DocumentEncodingException && origin.saveFormat() != DocumentSaveFormat.UTF8) {
                UiModalState conversion = switch (request.purpose()) {
                    case CURRENT -> UiModalState.saveAs();
                    case QUIT -> UiModalState.saveAsForQuit();
                    case CLOSE_TAB -> UiModalState.saveAsForCloseTab(index);
                    case SAVE_AS -> origin;
                };
                return offerUtf8(context, index, conversion, failure, request.purpose() != SavePurpose.SAVE_AS);
            }
            UiModalState retry = switch (request.purpose()) {
                case CURRENT -> UiModalState.none();
                case QUIT -> UiModalState.quitConfirm();
                case CLOSE_TAB -> UiModalState.closeTabConfirm(index);
                case SAVE_AS -> saveAsOrigin(origin);
            };
            return new Result(true, retry, index, -1, SaveFlowController.TextInputEffect.none(),
                Notice.error(SaveTargetPolicy.saveErrorMessage(failure)), false, retry.isSaveAs());
        }
        TextModel.SavedFile saved = (TextModel.SavedFile) completed;
        request.tab().engine().completeSaved(((WriteFile) request.operation()).prepared(), saved);
        rememberSavedPath(context, request.tab(), request.tab().state().getCurrentFile());
        Notice notice = Notice.success(SaveTargetPolicy.savedMessage(request.tab().state()));
        return switch (request.purpose()) {
            case CURRENT -> result(UiModalState.none(), notice);
            case QUIT -> continueQuit(context, notice, SaveFlowController.TextInputEffect.none());
            case CLOSE_TAB -> new Result(true, UiModalState.none(), -1, index,
                SaveFlowController.TextInputEffect.none(), notice, false, false);
            case SAVE_AS -> {
                Result continuation = afterSuccessfulSave(context, origin, notice);
                SaveFlowController.TextInputEffect filename =
                    continuation.filename().kind() == SaveFlowController.TextInputEffectKind.NONE
                        ? SaveFlowController.TextInputEffect.clear() : continuation.filename();
                yield new Result(continuation.running(), continuation.modalState(), continuation.activateTabIndex(),
                    continuation.closeTabIndex(), filename, continuation.notice(), true,
                    continuation.focusFilename(), continuation.pendingSave());
            }
        };
    }

    private static Result approveTarget(Context context, SaveRequest request,
                                        EditorEngine.SaveTargetInspection inspection, UiModalState origin) {
        String conflict = workspaceSaveConflict(context, inspection.physicalTarget());
        if (conflict != null) {
            return approvalFailure(context, request, origin, conflict);
        }
        try {
            return pending(new SaveRequest(request.tab(), new WriteFile(request.tab().engine().approveSaveAs(inspection)),
                origin, request.purpose(), request.submission()));
        } catch (IOException failure) {
            return approvalFailure(context, request, origin, SaveTargetPolicy.saveErrorMessage(failure));
        }
    }

    private static Result approvalFailure(Context context, SaveRequest request, UiModalState origin, String message) {
        return new Result(true, saveAsOrigin(origin), context.tabs().indexOf(request.tab()), -1,
            SaveFlowController.TextInputEffect.none(), Notice.error(message), false, true);
    }

    private static UiModalState saveAsOrigin(UiModalState modal) {
        return modal.isSaveOverwriteConfirm() ? modal.backToSaveAs() : modal;
    }

    private static UiModalState remapOrigin(UiModalState modal, int tabIndex) {
        if (modal.saveContinuation() != SaveContinuation.CLOSE_TAB) {
            return modal;
        }
        return new UiModalState(modal.mode(), modal.pendingOverwritePath(), tabIndex, modal.saveContinuation(),
            modal.saveFormat(), modal.pendingOpenPath());
    }

    private static Result pending(SaveRequest request) {
        return new Result(true, UiModalState.none(), -1, -1, SaveFlowController.TextInputEffect.none(),
            Notice.clear(), false, false, request);
    }

    private static String validateSaveAsTarget(Context context, Path target) {
        String error = SaveTargetPolicy.validateSaveTarget(target);
        return error == null ? workspaceSaveConflict(context, target) : error;
    }

    private static String workspaceSaveConflict(Context context, Path target) {
        int owner = WorkspaceDocuments.findOpenFile(context.tabs(), context.activeTabIndex(), target);
        if (owner < 0 || owner == context.activeTabIndex()) {
            return null;
        }
        return "Save failed: target is already open in tab " + (owner + 1)
            + ". Switch to that tab or choose another name.";
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
            if (context.tabs().get(index).state().isDirty()) {
                return saveDirty(context, index, SavePurpose.QUIT);
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
            if (context.tabs().get(index).state().isDirty()) {
                return saveDirty(context, index, SavePurpose.CLOSE_TAB);
            }
        }
        return new Result(true, UiModalState.none(), -1, index, SaveFlowController.TextInputEffect.none(),
            Notice.unchanged(), false, false);
    }

    private static Result prepareSaveAs(Context context, int index, UiModalState modal) {
        TabSession tab = context.tabs().get(index);
        String suggestion = suggestFilename(context, index, tab.state().getCurrentFile() != null);
        return new Result(true, modal, index, -1, SaveFlowController.TextInputEffect.setText(suggestion),
            Notice.clear(), false, true);
    }

    private static Result offerUtf8(Context context, int index, UiModalState modal, IOException error,
                                    boolean suggestCopy) {
        var filename = suggestCopy
            ? SaveFlowController.TextInputEffect.setText(
                suggestWritableName(context, index, context.tabs().get(index).state().getCurrentFile()))
            : SaveFlowController.TextInputEffect.none();
        return new Result(true, modal.offerUtf8(), index, -1, filename,
            Notice.error(SaveTargetPolicy.saveErrorMessage(error)), false, false);
    }

    private static String suggestFilename(Context context, int index, boolean writableCopy) {
        TabSession tab = context.tabs().get(index);
        Path file = tab.state().getCurrentFile();
        return context.suggestions().suggestionFor(tab, () -> {
            if (writableCopy) {
                return suggestWritableName(context, index, file);
            }
            if (file != null) {
                return PathPresentation.full(file.toAbsolutePath());
            }
            Path preferred = Path.of("").toAbsolutePath().resolve(SaveTargetPolicy.DEFAULT_FILE_NAME);
            return PathPresentation.full(SaveTargetSuggester.firstAvailable(preferred,
                target -> occupied(context, index, target)));
        }, draft -> !writableCopy || SaveTargetPolicy.isSuitableDraft(draft));
    }

    private static String suggestWritableName(Context context, int index, Path source) {
        return SaveTargetPolicy.suggestWritableName(source, target -> occupied(context, index, target));
    }

    private static boolean occupied(Context context, int index, Path target) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        return WorkspaceDocuments.findOtherOpenFile(context.tabs(), index, target) >= 0;
    }

    private static Result saveDirty(Context context, int index, SavePurpose purpose) {
        TabSession tab = context.tabs().get(index);
        Path file = tab.state().getCurrentFile();
        UiModalState retry = purpose == SavePurpose.QUIT ? UiModalState.quitConfirm()
            : UiModalState.closeTabConfirm(index);
        try {
            if (file == null) {
                throw new IOException("Tab has no file path. Use Save As.");
            }
            if (SaveTargetPolicy.isReadOnlyFile(file)) {
                throw new IOException("File is read-only. Use Save As. " + file);
            }
            return pending(new SaveRequest(tab, tab.engine().prepareSave(), retry, purpose));
        } catch (IOException error) {
            if (error instanceof DocumentEncodingException) {
                return offerUtf8(context, index, purpose == SavePurpose.QUIT ? UiModalState.saveAsForQuit()
                    : UiModalState.saveAsForCloseTab(index), error, true);
            }
            return new Result(true, retry, index, -1, SaveFlowController.TextInputEffect.none(),
                Notice.error(SaveTargetPolicy.saveErrorMessage(error)), false, false);
        }
    }

    private static boolean validIndex(Context context, int index) {
        return index >= 0 && index < context.tabs().size();
    }

    private static void rememberSavedPath(Context context, TabSession tab, Path file) {
        context.suggestions().rememberDraft(tab, PathPresentation.full(file.toAbsolutePath()));
    }

    private static Result result(UiModalState modal, Notice notice) {
        return new Result(true, modal, -1, -1, SaveFlowController.TextInputEffect.none(), notice, false, false);
    }
}
