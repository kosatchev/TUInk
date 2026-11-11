package dev.tuink.tui;

import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.UiModalState;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns detached saves and per-presentation UI publication for closed preview sources. */
final class SnapshotSaveController implements AutoCloseable {
    private final TerminalEditorApp host;
    private final TerminalEditorApp.SessionContext context;
    private final DocumentIoController io;
    private final Runnable invalidate;
    record Completion(DocumentIoController.Operation operation, DocumentIoCoordinator.Transition transition,
        SnapshotSaveWorkflow.Result result) {}
    private final Consumer<Completion> onCompleted;
    private SnapshotSaveWorkflow.Request pending;
    private record SaveUiAttempt(SnapshotSaveWorkflow.Request request, UiRecipient recipient, long noticeRevision) {}
    private SaveUiAttempt pendingUi;
    private boolean closed;

    SnapshotSaveController(TerminalEditorApp host, TerminalEditorApp.SessionContext context, DocumentIoController io,
        Runnable invalidate, Consumer<Completion> onCompleted) {
        this.host = Objects.requireNonNull(host);
        this.context = Objects.requireNonNull(context);
        this.io = Objects.requireNonNull(io);
        this.invalidate = Objects.requireNonNull(invalidate);
        this.onCompleted = Objects.requireNonNull(onCompleted);
    }

    void saveCurrent(PreviewTab preview) {
        if (!available(preview)) return;
        var result = SnapshotSaveWorkflow.saveCurrent(workflowContext(owner(preview)));
        apply(preview, result, false);
        if (result.pendingSave() != null) start(result.pendingSave());
        else onCompleted.accept(new Completion(null, null, result));
    }

    void openSaveAs(PreviewTab preview) {
        if (!available(preview)) return;
        apply(preview, SnapshotSaveWorkflow.openSaveAs(workflowContext(owner(preview)), preview.uiState().saveAsInput.text()), false);
    }

    void input(PreviewTab preview, TuinkCommand command) {
        if (closed || current(owner(preview)) == null) return;
        if (command == TuinkCommand.UI_ESCAPE || command == TuinkCommand.APP_QUIT) {
            var cancelled = cancel(preview);
            if (cancelled == DocumentIoCoordinator.CancelResult.DRAINING_WRITE) return;
            preview.uiState().modalState = UiModalState.none();
            preview.uiState().saveAsInput.clear();
            preview.uiState().filenameRevision++;
            invalidate.run();
            return;
        }
        if (!preview.uiState().modalState.isSaveAs() || !available(preview)) return;
        var result = SnapshotSaveWorkflow.saveAsInput(workflowContext(owner(preview)), command,
            preview.uiState().modalState, preview.uiState().saveAsInput.text());
        apply(preview, result, false);
        if (result.pendingSave() != null) start(result.pendingSave());
    }

    void confirm(PreviewTab preview, ConfirmChoice choice) {
        if (closed || current(owner(preview)) == null) return;
        var ui = preview.uiState();
        if (ui.modalState.isSaveUtf8Confirm()) {
            apply(preview, SnapshotSaveWorkflow.encodingChoice(ui.modalState, choice), false);
            return;
        }
        if (!ui.modalState.isSaveOverwriteConfirm()) return;
        if (choice == ConfirmChoice.SAVE && io.busy()) {
            notification(ui).info("A file operation is already in progress"); invalidate.run(); return;
        }
        var result = SnapshotSaveWorkflow.overwrite(workflowContext(owner(preview)), ui.modalState, choice);
        apply(preview, result, false);
        if (result.pendingSave() != null) start(result.pendingSave());
    }

    boolean busy() {
        if (pending != null) return true;
        for (var tab : context.presentations()) {
            if (tab instanceof PreviewTab preview && preview.uiState().snapshotOverwriteApproval != null) return true;
        }
        return false;
    }

    DocumentIoController.Operation pendingOperation() { return pending == null ? null : io.pendingOperation(); }

    /** True after a provider has crossed its publication fence. */
    boolean writing() { return pending != null && pending.operation().publishing(); }

    DocumentIoCoordinator.CancelResult cancel(PreviewTab preview) {
        var owner = owner(preview);
        if (current(owner) == null) return DocumentIoCoordinator.CancelResult.IDLE;
        if (pending != null && pending.owner().equals(owner)) {
            var result = owner.subject().io().cancel(pending.operation());
            if (result == DocumentIoCoordinator.CancelResult.CANCELLED) {
                pending = null;
                pendingUi = null;
                io.cancelSave();
                preview.uiState().modalState = UiModalState.none();
                notification(preview.uiState()).info("Save cancelled; snapshot kept");
            } else if (result == DocumentIoCoordinator.CancelResult.DRAINING_WRITE) {
                notification(preview.uiState()).info("Finishing save…");
            }
            invalidate.run();
            return result;
        }
        var approval = preview.uiState().snapshotOverwriteApproval;
        if (approval != null && approval.request().owner().equals(owner)) {
            var result = owner.subject().io().cancel(approval.inspection());
            preview.uiState().snapshotOverwriteApproval = null;
            preview.uiState().modalState = UiModalState.none();
            invalidate.run();
            return result;
        }
        return DocumentIoCoordinator.CancelResult.IDLE;
    }

    /** Cancels the pending subject even if its presentation has already retired. */
    DocumentIoCoordinator.CancelResult cancelPendingForClose() {
        if (pending == null) return DocumentIoCoordinator.CancelResult.IDLE;
        var request = pending;
        var attempt = pendingUi;
        var result = request.owner().subject().io().cancel(request.operation());
        boolean revoked = result == DocumentIoCoordinator.CancelResult.CANCELLED
            || result == DocumentIoCoordinator.CancelResult.IDLE && !request.owner().subject().io().busy();
        if (revoked) {
            pending = null; pendingUi = null;
            io.cancelSave();
        }
        if (attempt != null && attempt.recipient().current(context, null)) {
            if (revoked) notification(attempt.recipient().state()).info("Save cancelled; snapshot kept");
            else if (result == DocumentIoCoordinator.CancelResult.DRAINING_WRITE)
                notification(attempt.recipient().state()).info("Finishing save…");
        }
        invalidate.run();
        return result;
    }

    void updateProgress() {
        if (pending == null || closed) return;
        PreviewTab preview = current(pending.owner());
        if (preview == null) return;
        var notice = notification(preview.uiState());
        if (notice.requiresAttention()) return;
        String message = SaveProgressPresentation.message(pending.operation().progress(), true);
        if (!message.equals(notice.message)) { notice.info(message); invalidate.run(); }
    }

    private boolean available(PreviewTab preview) {
        if (closed || current(owner(preview)) == null) return false;
        if (io.busy() || pending != null || owner(preview).subject().io().busy()) {
            notification(preview.uiState()).info("A file operation is already in progress"); invalidate.run(); return false;
        }
        return true;
    }

    private void start(SnapshotSaveWorkflow.Request request) { start(request, -1); }

    private void start(SnapshotSaveWorkflow.Request request, long originalNoticeRevision) {
        PreviewTab preview = current(request.owner());
        if (preview == null || closed) { request.owner().subject().io().cancel(request.operation()); return; }
        var subject = request.owner().subject();
        var bound = subject.binding().resource();
        if (!request.externalOverwrite() && !request.inspecting() && host.externalNotices.visible(subject.documentId())
            && bound != null && (request.purpose() != SnapshotSaveWorkflow.SavePurpose.SAVE_AS
                || bound.id().equals(request.operation().resource().id()))) {
            subject.io().cancel(request.operation());
            try { request = request.inspectExternalOverwrite(); }
            catch (java.io.IOException failure) { notification(preview.uiState()).error(failure.getMessage()); invalidate.run(); return; }
        }
        if (request.purpose() == SnapshotSaveWorkflow.SavePurpose.SAVE_AS && request.submission() == null)
            request = request.submitted(preview.uiState().saveAsInput.text(), preview.uiState().filenameRevision);
        pending = request;
        updateProgress();
        SaveUiAttempt attempt = new SaveUiAttempt(request, UiRecipient.presentation(preview, UiRecipient.Flow.LOCAL),
            originalNoticeRevision < 0 ? notification(preview.uiState()).revision() : originalNoticeRevision);
        pendingUi = attempt;
        var submitted = request;
        io.submitSave(submitted::perform, completion -> completed(attempt, completion));
        invalidate.run();
    }

    private void completed(SaveUiAttempt attempt,
        DocumentIoController.Completion<DocumentIoCoordinator.Completion> completion) {
        var request = attempt.request();
        // Storage owns receipt acceptance. UI retirement never discards a
        // committed receipt or redirects it to a different presentation.
        var transition = request.accept(completion.value(), completion.failure());
        boolean currentAttempt = pending == request && pendingUi == attempt;
        if (currentAttempt) { pending = null; pendingUi = null; }
        UiRecipient recipient = attempt.recipient();
        PreviewTab preview = closed || !currentAttempt || !recipient.current(context, null) ? null : current(request.owner());
        if (preview == null) {
            if (transition instanceof DocumentIoCoordinator.Approval approval) request.owner().subject().io().cancel(approval.confirmation());
            if (!closed && currentAttempt) onCompleted.accept(new Completion(completion.operation(), transition, new SnapshotSaveWorkflow.Result(UiModalState.none(),
                SaveFlowController.TextInputEffect.none(), SaveWorkflow.Notice.unchanged(), false, null, null, false,
                transition instanceof DocumentIoCoordinator.Failed failed ? failed.kind() : null)));
            invalidate.run(); return;
        }
        var result = SnapshotSaveWorkflow.complete(workflowContext(request.owner()), request, transition);
        if (result.saved() && transition instanceof DocumentIoCoordinator.Saved accepted) {
            preview = preview.saved();
            context.updatePreview(preview);
            var state = preview.uiState().preview;
            var saved = request.owner().subject().source();
            if (state.sourceForFreeze().text() == saved.text()) state.updateSavedSource(saved, preview.generation());
            else state.adoptExternalSource(saved, preview.generation());
            // Only this accepted operation can advance its captured UI address.
            recipient = recipient.afterAcceptedSaveRebind(preview, request.operation(), accepted);
        }
        boolean preserveFilename = request.submission() != null
            && (recipient.state().filenameRevision != request.submission().filenameRevision()
                || !recipient.state().saveAsInput.text().equals(request.submission().filename()));
        apply(recipient, result, preserveFilename, attempt.noticeRevision());
        if (result.pendingSave() != null) start(result.pendingSave(), attempt.noticeRevision());
        else if (result.overwriteApproval() == null) onCompleted.accept(new Completion(completion.operation(), transition, result));
        invalidate.run();
    }

    private SnapshotSaveWorkflow.Context workflowContext(SnapshotSaveWorkflow.Owner owner) {
        var documents = new ArrayList<WorkspaceSaveConflictPolicy.OpenDocument>();
        var presentations = context.presentations();
        for (int index = 0; index < presentations.size(); index++) {
            if (presentations.get(index) instanceof TerminalEditorApp.EditorTab source) {
                documents.add(new WorkspaceSaveConflictPolicy.OpenDocument(source.documentSession().document(), index + 1));
            }
        }
        PreviewTab preview = current(owner);
        return new SnapshotSaveWorkflow.Context(owner, documents, preview == null ? null : preview.uiState().snapshotOverwriteApproval);
    }

    private void apply(PreviewTab preview, SnapshotSaveWorkflow.Result result, boolean preserveFilename) {
        apply(UiRecipient.presentation(preview, UiRecipient.Flow.LOCAL), result, preserveFilename, -1);
    }

    private void apply(UiRecipient recipient, SnapshotSaveWorkflow.Result result, boolean preserveFilename, long noticeRevision) {
        if (!recipient.current(context, null)) return;
        var ui = recipient.state();
        if (!preserveFilename && !UiEffects.filename(ui, result.filename())) {
            notification(ui).error("Not enough memory to open Save As; the filename was not changed");
            invalidate.run(); return;
        }
        UiEffects.modal(ui, result.modalState(), preserveFilename);
        ui.snapshotOverwriteApproval = result.overwriteApproval();
        if (result.focusFilename()) UiEffects.focusFilename(ui);
        UiEffects.notice(notification(ui), result.notice(), noticeRevision);
        invalidate.run();
    }

    private TerminalEditorApp.NotificationState notification(TabUiState ui) {
        if (ui.notification == null) ui.notification = host.new NotificationState();
        return ui.notification;
    }

    private static SnapshotSaveWorkflow.Owner owner(PreviewTab preview) {
        if (!(preview.source() instanceof PreviewTab.ClosedSnapshot frozen)) {
            throw new IllegalArgumentException("Snapshot save requires a closed source");
        }
        return new SnapshotSaveWorkflow.Owner(preview.presentationId(), preview.generation(), frozen.subject());
    }

    private PreviewTab current(SnapshotSaveWorkflow.Owner owner) {
        var tab = context.presentation(owner.presentationId());
        return tab instanceof PreviewTab preview && !preview.uiState().closed && preview.generation() == owner.generation()
            && preview.source() instanceof PreviewTab.ClosedSnapshot frozen && frozen.subject() == owner.subject() ? preview : null;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (pending != null) {
            var cancellation = pending.owner().subject().io().cancel(pending.operation());
            if (cancellation == DocumentIoCoordinator.CancelResult.CANCELLED) { pending = null; pendingUi = null; io.cancelSave(); }
        }
        for (var tab : context.presentations()) {
            if (tab instanceof PreviewTab preview && preview.uiState().snapshotOverwriteApproval != null) {
                var approval = preview.uiState().snapshotOverwriteApproval;
                approval.request().owner().subject().io().cancel(approval.inspection());
                preview.uiState().snapshotOverwriteApproval = null;
            }
        }
    }
}
