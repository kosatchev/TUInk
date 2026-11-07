package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.storage.jvm.JvmFileAdvisory;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.DocumentEncodingException;
import dev.tuink.core.TabSession;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.DocumentSaveFormat;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SaveContinuation;
import dev.tuink.tui.flow.SaveTargetSuggester;
import dev.tuink.tui.flow.UiModalState;
import dev.tuiframe.explorer.file.PathLabels;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Stateless application save transitions. The window applies effects to its widgets and tabs. */
final class SaveWorkflow {
    private SaveWorkflow() {}

    record Context(List<TuiDocumentSession> documents, int activeTabIndex, SaveAsSuggestionStore suggestions,
                   OverwriteApproval overwriteApproval, java.util.Map<ViewId, Integer> displayNumbers) {
        Context(List<TuiDocumentSession> documents, int activeTabIndex, SaveAsSuggestionStore suggestions,
                OverwriteApproval overwriteApproval) {
            this(documents, activeTabIndex, suggestions, overwriteApproval, java.util.Map.of());
        }
        Context { displayNumbers = java.util.Map.copyOf(displayNumbers); }
        Context(List<TuiDocumentSession> documents, int activeTabIndex, SaveAsSuggestionStore suggestions) {
            this(documents, activeTabIndex, suggestions, null);
        }
        List<TabSession> tabs() { return documents.stream().map(TuiDocumentSession::view).toList(); }
        TabSession active() { return activeDocument().view(); }
        TuiDocumentSession activeDocument() { return documents.get(activeTabIndex); }
        int indexOf(ViewId viewId) {
            for (int index = 0; index < documents.size(); index++) {
                if (documents.get(index).viewId().equals(viewId)) return index;
            }
            return -1;
        }
        Context at(int index) { return new Context(documents, index, suggestions, overwriteApproval, displayNumbers); }
        int displayNumber(int index) { return displayNumbers.getOrDefault(documents.get(index).viewId(), index + 1); }
        TuiDocumentSession document(TabSession tab) {
            return documents.stream().filter(document -> document.view() == tab).findFirst().orElseThrow();
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

    /** Captured owner state is inspected first, approved on the owner, then written detached. */
    record SaveSubmission(String filename, long filenameRevision) {}

    record SaveRequest(TuiDocumentSession session, DocumentIoCoordinator.Work operation, UiModalState origin, SavePurpose purpose,
                       SaveSubmission submission) {
        SaveRequest(TuiDocumentSession session, DocumentIoCoordinator.Work operation, UiModalState origin, SavePurpose purpose) {
            this(session, operation, origin, purpose, null);
        }

        TabSession tab() { return session.view(); }

        boolean inspecting() { return operation.kind() == DocumentIoCoordinator.WorkKind.INSPECT; }

        SaveRequest submitted(String filename, long revision) {
            return new SaveRequest(session, operation, origin, purpose, new SaveSubmission(filename, revision));
        }

        DocumentIoCoordinator.Completion perform() throws IOException {
            if (inspecting()) {
                Path target = JvmDocumentStorage.pathOf(operation.resource());
                if (SaveTargetPolicy.isReadOnlyFile(target)) throw new ReadOnlyTarget(target);
            }
            return operation.perform();
        }

        DocumentIoCoordinator.Transition accept(DocumentIoCoordinator.Completion completion, IOException failure) {
            DocumentIoCoordinator io = session.document().io();
            return failure == null ? io.accept(operation, completion) : io.reject(operation, failure);
        }
    }

    record OverwriteApproval(SaveRequest request, DocumentIoCoordinator.Confirmation inspection) {}

    private static final class ReadOnlyTarget extends IOException {
        private final Path target;
        private ReadOnlyTarget(Path target) {
            super("target is read-only: " + target + ". Choose another name.");
            this.target = target;
        }
    }

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
            return pending(new SaveRequest(context.document(tab), context.document(tab).document().io().beginSave(), UiModalState.none(), SavePurpose.CURRENT));
        } catch (IOException error) {
            if (error instanceof DocumentEncodingException) {
                return offerUtf8(context, context.activeTabIndex(), UiModalState.saveAs(), error, true);
            }
            return result(UiModalState.none(), Notice.error(SaveTargetPolicy.saveErrorMessage(error)));
        }
    }

    static Result saveAsInput(Context context, TuinkCommand command, UiModalState modal, String draft) {
        int target = modal.targetViewId() == null ? context.activeTabIndex() : context.indexOf(modal.targetViewId());
        if (target < 0) return cancelledDialog(context, modal);
        context = context.at(target);
        TabSession tab = context.active();
        context.suggestions().rememberInput(tab, draft);
        SaveFlowController.SaveAsResult decision = SaveFlowController.handleSaveAsInput(command, modal, draft);
        // A filename prompt owns no approval. The host separately revokes a
        // pending save preparation when its owning view cancels Save As.
        if (decision.saveNowPath() != null) {
            // The detached inspection decides which bytes an overwrite would
            // replace. This input decision performs no preflight stat.
            return saveAs(context, modal, decision.saveNowPath(), SaveDisposition.REPLACE_EXISTING);
        }
        Notice notice = decision.errorMessage() != null ? Notice.error(decision.errorMessage())
            : decision.clearNotification() ? Notice.clear() : Notice.unchanged();
        return new Result(true, decision.modalState(), -1, -1, decision.textInputEffect(), notice,
            false, decision.errorMessage() != null);
    }

    static Result overwrite(Context context, UiModalState modal, ConfirmChoice choice) {
        OverwriteApproval approval = context.overwriteApproval();
        if (approval != null && !matchesApproval(context, modal, approval)) {
            return retainOverwrite(approval);
        }
        return switch (choice) {
            case SAVE -> approveOverwrite(context, modal);
            case DISCARD -> cancelOverwrite(context, modal, modal.backToSaveAs());
            case CANCEL -> cancelOverwrite(context, modal, UiModalState.none());
        };
    }

    private static Result cancelOverwrite(Context context, UiModalState source, UiModalState next) {
        OverwriteApproval approval = context.overwriteApproval();
        if (approval != null && matchesApproval(context, source, approval)) {
            approval.request().session().document().io().cancel(approval.inspection());
        }
        return result(next, Notice.clear());
    }

    private static boolean matchesApproval(Context context, UiModalState modal, OverwriteApproval approval) {
        ViewId target = modal.targetViewId() == null ? context.activeDocument().viewId() : modal.targetViewId();
        return approval.request().session().viewId().equals(target)
            && java.util.Objects.equals(JvmDocumentStorage.pathOf(approval.inspection().requestedResource()),
                modal.pendingOverwritePath());
    }

    private static Result retainOverwrite(OverwriteApproval approval) {
        UiModalState modal = saveAsOrigin(approval.request().origin())
            .confirmOverwrite(JvmDocumentStorage.pathOf(approval.inspection().requestedResource()))
            .bindTo(approval.request().session().viewId());
        return new Result(true, modal, -1, -1, SaveFlowController.TextInputEffect.none(),
            Notice.unchanged(), false, false, null, approval);
    }

    private static Result approveOverwrite(Context context, UiModalState modal) {
        OverwriteApproval approval = context.overwriteApproval();
        int target = modal.targetViewId() == null ? context.activeTabIndex() : context.indexOf(modal.targetViewId());
        if (target < 0) return cancelledDialog(context, modal);
        context = context.at(target);
        if (approval == null || !matchesApproval(context, modal, approval)) {
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
            return pending(new SaveRequest(context.document(tab),
                context.document(tab).document().io().beginSaveAs(JvmDocumentStorage.resource(target), disposition,
                    modal.saveFormat() == DocumentSaveFormat.UTF8),
                modal, SavePurpose.SAVE_AS));
        } catch (IOException error) {
            if (error instanceof DocumentEncodingException && modal.saveFormat() != DocumentSaveFormat.UTF8) {
                return offerUtf8(context, context.activeTabIndex(), modal, error, false);
            }
            return new Result(true, modal, -1, -1, SaveFlowController.TextInputEffect.none(),
                Notice.error(SaveTargetPolicy.saveErrorMessage(error)), false, modal.isSaveAs());
        }
    }

    static Result complete(Context context, SaveRequest request, DocumentIoCoordinator.Completion completed, IOException failure) {
        return complete(context, request, request.accept(completed, failure));
    }

    static Result complete(Context context, SaveRequest request, DocumentIoCoordinator.Transition transition) {
        DocumentIoCoordinator io = request.session().document().io();
        if (transition instanceof DocumentIoCoordinator.Ignored) {
            return result(UiModalState.none(), Notice.unchanged());
        }
        int index = context.indexOf(request.session().viewId());
        if (index < 0) {
            SaveRequest handedOff = handoffToSurvivingDocumentView(context, request);
            if (handedOff != null) { request = handedOff; index = context.indexOf(request.session().viewId()); }
        }
        if (index < 0) {
            io.cancel(request.operation());
            return result(UiModalState.none(), Notice.error("Save completed for a document that is no longer open"));
        }
        UiModalState origin = remapOrigin(request.origin(), index);
        if (transition instanceof DocumentIoCoordinator.Approval approval) {
            DocumentIoCoordinator.Confirmation inspection = approval.confirmation();
            Context owner = context.at(index);
            String conflict = workspaceSaveConflict(owner, JvmDocumentStorage.pathOf(inspection.physicalResource()));
            if (conflict != null) {
                io.cancel(inspection);
                return new Result(true, saveAsOrigin(origin), index, -1, SaveFlowController.TextInputEffect.none(),
                    Notice.error(conflict), false, true);
            }
            if (inspection.requiresOverwriteConfirmation()) {
                UiModalState confirmation = saveAsOrigin(origin).confirmOverwrite(JvmDocumentStorage.pathOf(inspection.requestedResource()));
                return new Result(true, confirmation, index, -1, SaveFlowController.TextInputEffect.none(),
                    Notice.clear(), false, false, null, new OverwriteApproval(request, inspection));
            }
            return approveTarget(owner, request, inspection, saveAsOrigin(origin));
        }
        if (transition instanceof DocumentIoCoordinator.Failed failed) {
            IOException failure = failed.failure();
            if (failure instanceof ReadOnlyTarget readOnly) {
                String suggestion = suggestWritableName(context, index, readOnly.target);
                context.suggestions().rememberGenerated(request.tab(), suggestion);
                return new Result(true, saveAsOrigin(origin), index, -1,
                    SaveFlowController.TextInputEffect.setText(suggestion),
                    Notice.error(SaveTargetPolicy.saveErrorMessage(failure)), false, true);
            }
            if (failure instanceof DocumentEncodingException && origin.saveFormat() != DocumentSaveFormat.UTF8) {
                UiModalState conversion = switch (request.purpose()) {
                    case CURRENT -> UiModalState.saveAs();
                    case QUIT -> UiModalState.saveAsForQuit();
                    case CLOSE_TAB -> UiModalState.saveAsForCloseTab(index);
                    case SAVE_AS -> origin;
                };
                if (origin.targetViewId() != null) conversion = conversion.bindTo(request.session().viewId());
                return offerUtf8(context, index, conversion, failure, request.purpose() != SavePurpose.SAVE_AS);
            }
            UiModalState retry = switch (request.purpose()) {
                case CURRENT -> UiModalState.none();
                case QUIT -> UiModalState.quitConfirm();
                case CLOSE_TAB -> UiModalState.closeTabConfirm(index);
                case SAVE_AS -> saveAsOrigin(origin);
            };
            if (origin.targetViewId() != null) retry = retry.bindTo(request.session().viewId());
            return new Result(true, retry, index, -1, SaveFlowController.TextInputEffect.none(),
                Notice.error(SaveTargetPolicy.saveErrorMessage(failure)), false, retry.isSaveAs());
        }
        if (!(transition instanceof DocumentIoCoordinator.Saved)) {
            throw new IllegalStateException("Unexpected save transition: " + transition);
        }
        rememberSavedPath(context, request.tab(), JvmDocumentStorage.pathOf(request.tab().state().getCurrentResource()));
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

    /** Explicit document-level policy for callers that choose an outcome handoff.
     * The window controller does not invoke completion for retired UI recipients. */
    private static SaveRequest handoffToSurvivingDocumentView(Context context, SaveRequest request) {
        for (TuiDocumentSession surviving : context.documents()) {
            if (surviving.document() == request.session().document())
                return new SaveRequest(surviving, request.operation(),
                    survivingOrigin(request.origin(), surviving.viewId()),
                    request.purpose() == SavePurpose.CLOSE_TAB ? SavePurpose.CURRENT : request.purpose(), request.submission());
        }
        return null;
    }

    private static Result approveTarget(Context context, SaveRequest request,
                                        DocumentIoCoordinator.Confirmation inspection, UiModalState origin) {
        int index = context.indexOf(request.session().viewId());
        if (index < 0) {
            request.session().document().io().cancel(inspection);
            return cancelledDialog(context, origin);
        }
        context = context.at(index);
        String conflict = workspaceSaveConflict(context, JvmDocumentStorage.pathOf(inspection.physicalResource()));
        if (conflict != null) {
            request.session().document().io().cancel(inspection);
            return approvalFailure(context, request, origin, conflict);
        }
        DocumentIoCoordinator.Transition transition = request.session().document().io().approve(inspection);
        if (transition instanceof DocumentIoCoordinator.Ready ready) {
            return pending(new SaveRequest(request.session(), ready.work(),
                origin, request.purpose(), request.submission()));
        }
        if (transition instanceof DocumentIoCoordinator.Failed failed) {
            return approvalFailure(context, request, origin, SaveTargetPolicy.saveErrorMessage(failed.failure()));
        }
        return approvalFailure(context, request, origin, "Save was cancelled. Choose the target again.");
    }

    private static Result approvalFailure(Context context, SaveRequest request, UiModalState origin, String message) {
        return new Result(true, saveAsOrigin(origin), context.indexOf(request.session().viewId()), -1,
            SaveFlowController.TextInputEffect.none(), Notice.error(message), false, true);
    }

    private static UiModalState saveAsOrigin(UiModalState modal) {
        return modal.isSaveOverwriteConfirm() ? modal.backToSaveAs() : modal;
    }

    private static UiModalState remapOrigin(UiModalState modal, int tabIndex) {
        if (modal.saveContinuation() != SaveContinuation.CLOSE_TAB) {
            return modal;
        }
        return modal.withCloseTabIndex(tabIndex);
    }

    private static UiModalState survivingOrigin(UiModalState origin, ViewId survivingViewId) {
        if (origin.isCloseTabConfirm()) return UiModalState.none();
        if (!origin.isSaveAs() && !origin.isSaveOverwriteConfirm() && !origin.isSaveUtf8Confirm()) return origin;
        return new UiModalState(origin.mode(), origin.pendingOverwritePath(), -1,
            origin.saveContinuation() == SaveContinuation.CLOSE_TAB ? SaveContinuation.NONE : origin.saveContinuation(),
            origin.saveFormat(), origin.pendingOpenPath(), survivingViewId);
    }

    private static Result cancelledDialog(Context context, UiModalState modal) {
        OverwriteApproval approval = context.overwriteApproval();
        if (approval != null && matchesApproval(context, modal, approval)) {
            approval.request().session().document().io().cancel(approval.inspection());
        }
        return result(UiModalState.none(), Notice.error("The dialog's document view is no longer open"));
    }

    private static Result pending(SaveRequest request) {
        return new Result(true, UiModalState.none(), -1, -1, SaveFlowController.TextInputEffect.none(),
            Notice.clear(), false, false, request);
    }

    private static String workspaceSaveConflict(Context context, Path target) {
        java.util.ArrayList<WorkspaceSaveConflictPolicy.OpenDocument> owners = new java.util.ArrayList<>();
        for (int index = 0; index < context.documents().size(); index++) {
            owners.add(new WorkspaceSaveConflictPolicy.OpenDocument(context.documents().get(index).document(), context.displayNumber(index)));
        }
        return WorkspaceSaveConflictPolicy.conflict(owners, context.activeDocument().document(), target);
    }

    private static Result afterSuccessfulSave(Context context, UiModalState modal, Notice notice) {
        if (modal.saveContinuation() == SaveContinuation.QUIT) {
            return continueQuit(context, notice, SaveFlowController.TextInputEffect.clear());
        }
        int closeIndex = modal.saveContinuation() == SaveContinuation.CLOSE_TAB ? closeIndex(context, modal) : -1;
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
            if (tab.state().isDirty() && SaveTargetPolicy.requiresSaveAs(JvmDocumentStorage.pathOf(tab.state().getCurrentResource()))) {
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
        int index = closeIndex(context, modal);
        if (!validIndex(context, index) || choice == ConfirmChoice.CANCEL) {
            return result(UiModalState.none(), Notice.unchanged());
        }
        if (choice == ConfirmChoice.SAVE) {
            if (SaveTargetPolicy.requiresSaveAs(JvmDocumentStorage.pathOf(context.tabs().get(index).state().getCurrentResource()))) {
                UiModalState saveAs = UiModalState.saveAsForCloseTab(index);
                if (modal.targetViewId() != null) saveAs = saveAs.bindTo(modal.targetViewId());
                return prepareSaveAs(context, index, saveAs);
            }
            if (context.tabs().get(index).state().isDirty()) {
                return saveDirty(context, index, SavePurpose.CLOSE_TAB,
                    modal.targetViewId() == null ? UiModalState.closeTabConfirm(index) : modal.withCloseTabIndex(index));
            }
        }
        return new Result(true, UiModalState.none(), -1, index, SaveFlowController.TextInputEffect.none(),
            Notice.unchanged(), false, false);
    }

    private static Result prepareSaveAs(Context context, int index, UiModalState modal) {
        TabSession tab = context.tabs().get(index);
        String suggestion = suggestFilename(context, index, JvmDocumentStorage.pathOf(tab.state().getCurrentResource()) != null);
        return new Result(true, modal, index, -1, SaveFlowController.TextInputEffect.setText(suggestion),
            Notice.clear(), false, true);
    }

    private static Result offerUtf8(Context context, int index, UiModalState modal, IOException error,
                                    boolean suggestCopy) {
        var filename = suggestCopy
            ? SaveFlowController.TextInputEffect.setText(
                suggestWritableName(context, index, JvmDocumentStorage.pathOf(context.tabs().get(index).state().getCurrentResource())))
            : SaveFlowController.TextInputEffect.none();
        return new Result(true, modal.offerUtf8(), index, -1, filename,
            Notice.error(SaveTargetPolicy.saveErrorMessage(error)), false, false);
    }

    private static String suggestFilename(Context context, int index, boolean writableCopy) {
        TabSession tab = context.tabs().get(index);
        Path file = JvmDocumentStorage.pathOf(tab.state().getCurrentResource());
        return context.suggestions().suggestionFor(tab, () -> {
            if (writableCopy) {
                return suggestWritableName(context, index, file);
            }
            if (file != null) {
                return PathLabels.full(file.toAbsolutePath());
            }
            Path preferred = Path.of("").toAbsolutePath().resolve(SaveTargetPolicy.DEFAULT_FILE_NAME);
            return PathLabels.full(SaveTargetSuggester.firstAvailable(preferred,
                target -> occupied(context, index, target)));
        }, draft -> !writableCopy || SaveTargetPolicy.isSuitableDraft(draft));
    }

    private static String suggestWritableName(Context context, int index, Path source) {
        return SaveTargetPolicy.suggestWritableName(source, target -> occupied(context, index, target));
    }

    private static boolean occupied(Context context, int index, Path target) {
        if (JvmFileAdvisory.exists(target)) {
            return true;
        }
        return WorkspaceDocuments.findOtherOpenFile(context.tabs(), index, target) >= 0;
    }

    private static Result saveDirty(Context context, int index, SavePurpose purpose) {
        return saveDirty(context, index, purpose, purpose == SavePurpose.QUIT ? UiModalState.quitConfirm()
            : UiModalState.closeTabConfirm(index));
    }

    private static Result saveDirty(Context context, int index, SavePurpose purpose, UiModalState retry) {
        TabSession tab = context.tabs().get(index);
        Path file = JvmDocumentStorage.pathOf(tab.state().getCurrentResource());
        try {
            if (file == null) {
                throw new IOException("Tab has no file path. Use Save As.");
            }
            if (SaveTargetPolicy.isReadOnlyFile(file)) {
                throw new IOException("File is read-only. Use Save As. " + file);
            }
            return pending(new SaveRequest(context.document(tab), context.document(tab).document().io().beginSave(), retry, purpose));
        } catch (IOException error) {
            if (error instanceof DocumentEncodingException) {
                UiModalState conversion = purpose == SavePurpose.QUIT ? UiModalState.saveAsForQuit()
                    : UiModalState.saveAsForCloseTab(index);
                if (retry.targetViewId() != null) conversion = conversion.bindTo(retry.targetViewId());
                return offerUtf8(context, index, conversion, error, true);
            }
            return new Result(true, retry, index, -1, SaveFlowController.TextInputEffect.none(),
                Notice.error(SaveTargetPolicy.saveErrorMessage(error)), false, false);
        }
    }

    private static boolean validIndex(Context context, int index) {
        return index >= 0 && index < context.tabs().size();
    }

    private static int closeIndex(Context context, UiModalState modal) {
        return modal.targetViewId() == null ? modal.pendingCloseTabIndex() : context.indexOf(modal.targetViewId());
    }

    private static void rememberSavedPath(Context context, TabSession tab, Path file) {
        context.suggestions().rememberDraft(tab, PathLabels.full(file.toAbsolutePath()));
    }

    private static Result result(UiModalState modal, Notice notice) {
        return new Result(true, modal, -1, -1, SaveFlowController.TextInputEffect.none(), notice, false, false);
    }
}
