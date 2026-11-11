package dev.tuink.tui;

import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.editor.storage.DocumentEncodingException;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.DocumentSaveFormat;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.ui.NotificationPresentation;
import dev.tuiframe.explorer.file.PathLabels;
import dev.tuiframe.text.TerminalPlainText;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Save UI transitions for closed source snapshots; no editing session or view is manufactured. */
final class SnapshotSaveWorkflow {
    private SnapshotSaveWorkflow() { }

    record Owner(PresentationId presentationId, long generation, FrozenDocumentSession subject) {
        Owner {
            Objects.requireNonNull(presentationId, "presentationId");
            Objects.requireNonNull(subject, "subject");
            if (generation < 0) throw new IllegalArgumentException("Generation must be nonnegative");
        }
    }

    record Context(Owner owner, List<WorkspaceSaveConflictPolicy.OpenDocument> openDocuments,
                   OverwriteApproval overwriteApproval) {
        Context { Objects.requireNonNull(owner, "owner"); openDocuments = List.copyOf(openDocuments); }
        Context(Owner owner, List<WorkspaceSaveConflictPolicy.OpenDocument> openDocuments) { this(owner, openDocuments, null); }
    }

    enum SavePurpose { CURRENT, SAVE_AS }

    record Request(Owner owner, DocumentIoCoordinator.Work operation, UiModalState origin, SavePurpose purpose,
                   SaveWorkflow.SaveSubmission submission, boolean externalOverwrite) {
        Request {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(purpose, "purpose");
        }
        Request(Owner owner, DocumentIoCoordinator.Work operation, UiModalState origin, SavePurpose purpose) {
            this(owner, operation, origin, purpose, null, false);
        }
        Request(Owner owner, DocumentIoCoordinator.Work operation, UiModalState origin, SavePurpose purpose, SaveWorkflow.SaveSubmission submission) {
            this(owner, operation, origin, purpose, submission, false);
        }
        Request inspectExternalOverwrite() throws IOException {
            var work = owner.subject().io().beginSaveAs(operation.resource(), SaveDisposition.REPLACE_EXISTING,
                origin.saveFormat() == DocumentSaveFormat.UTF8);
            return new Request(owner, work, origin, purpose, submission, true);
        }
        boolean inspecting() { return operation.kind() == DocumentIoCoordinator.WorkKind.INSPECT; }
        Request submitted(String filename, long revision) {
            return new Request(owner, operation, origin, purpose, new SaveWorkflow.SaveSubmission(filename, revision), externalOverwrite);
        }
        DocumentIoCoordinator.Completion perform() throws IOException {
            if (inspecting()) {
                Path target = filePath(operation.resource());
                if (target != null && SaveTargetPolicy.isReadOnlyFile(target)) {
                    throw new AccessDeniedException(target.toString(), null, "target is read-only; choose another name");
                }
            }
            return operation.perform();
        }
        DocumentIoCoordinator.Transition accept(DocumentIoCoordinator.Completion completion, IOException failure) {
            var io = owner.subject().io();
            return failure == null ? io.accept(operation, completion) : io.reject(operation, failure);
        }
    }

    record OverwriteApproval(Request request, DocumentIoCoordinator.Confirmation inspection) { }

    record Result(UiModalState modalState, SaveFlowController.TextInputEffect filename, SaveWorkflow.Notice notice,
                  boolean focusFilename, Request pendingSave, OverwriteApproval overwriteApproval, boolean saved,
                  DocumentIoCoordinator.FailureKind failureKind) {
        boolean uncertain() {
            return failureKind == DocumentIoCoordinator.FailureKind.UNKNOWN
                || failureKind == DocumentIoCoordinator.FailureKind.CONFIRMED_NOT_PUBLISHED;
        }
    }

    static Result openSaveAs(Context context) { return openSaveAs(context, ""); }

    static Result openSaveAs(Context context, String draft) {
        return result(UiModalState.saveAs(), SaveFlowController.TextInputEffect.setText(
            draft == null || draft.isEmpty() ? initialFilename(context.owner()) : draft), SaveWorkflow.Notice.clear(), true);
    }

    static Result saveCurrent(Context context) {
        String conflict = conflict(context, filePath(context.owner().subject().binding().resource()));
        if (conflict != null) return result(UiModalState.none(), SaveWorkflow.Notice.error(conflict));
        if (context.owner().subject().io().busy()) return busy(UiModalState.none());
        try {
            return pending(new Request(context.owner(), context.owner().subject().io().beginSave(),
                UiModalState.none(), SavePurpose.CURRENT));
        } catch (IOException failure) {
            return result(UiModalState.none(), SaveWorkflow.Notice.error(SaveTargetPolicy.saveErrorMessage(failure)));
        }
    }

    static Result saveAsInput(Context context, TuinkCommand command, UiModalState modal, String draft) {
        var decision = SaveFlowController.handleSaveAsInput(command, modal, draft);
        if (decision.saveNowPath() != null) return saveAs(context, modal, decision.saveNowPath());
        var notice = decision.errorMessage() != null ? SaveWorkflow.Notice.error(decision.errorMessage())
            : decision.clearNotification() ? SaveWorkflow.Notice.clear() : SaveWorkflow.Notice.unchanged();
        return result(decision.modalState(), decision.textInputEffect(), notice, decision.errorMessage() != null);
    }

    private static Result saveAs(Context context, UiModalState origin, Path target) {
        String conflict = conflict(context, target);
        if (conflict != null) return result(origin, SaveWorkflow.Notice.error(conflict), true);
        if (context.owner().subject().io().busy()) return busy(origin);
        try {
            var work = context.owner().subject().io().beginSaveAs(JvmDocumentStorage.resource(target),
                SaveDisposition.REPLACE_EXISTING, origin.saveFormat() == DocumentSaveFormat.UTF8);
            return pending(new Request(context.owner(), work, origin, SavePurpose.SAVE_AS));
        } catch (IOException failure) {
            return result(origin, SaveWorkflow.Notice.error(SaveTargetPolicy.saveErrorMessage(failure)), true);
        }
    }

    static Result overwrite(Context context, UiModalState modal, ConfirmChoice choice) {
        OverwriteApproval approval = context.overwriteApproval();
        if (approval == null || !matches(context, approval.request())
            || !Objects.equals(modal.pendingOverwritePath(), filePath(approval.inspection().requestedResource()))) {
            return result(UiModalState.saveAs(), SaveWorkflow.Notice.error("Save target was not inspected. Choose the target again."), true);
        }
        if (choice == ConfirmChoice.SAVE) return approve(context, approval.request(), approval.inspection());
        approval.request().owner().subject().io().cancel(approval.inspection());
        return result(choice == ConfirmChoice.DISCARD && !approval.request().externalOverwrite() ? modal.backToSaveAs() : UiModalState.none(), SaveWorkflow.Notice.clear());
    }

    static Result encodingChoice(UiModalState modal, ConfirmChoice choice) {
        return choice == ConfirmChoice.SAVE
            ? result(modal.utf8SaveAs(), SaveFlowController.TextInputEffect.none(), SaveWorkflow.Notice.clear(), true)
            : result(UiModalState.none(), SaveWorkflow.Notice.clear());
    }

    static Result complete(Context context, Request request, DocumentIoCoordinator.Completion completion, IOException failure) {
        return complete(context, request, request.accept(completion, failure));
    }

    static Result complete(Context context, Request request, DocumentIoCoordinator.Transition transition) {
        if (!matches(context, request)) {
            if (transition instanceof DocumentIoCoordinator.Approval approval) request.owner().subject().io().cancel(approval.confirmation());
            return result(UiModalState.none(), SaveWorkflow.Notice.unchanged());
        }
        if (transition instanceof DocumentIoCoordinator.Ignored) return result(UiModalState.none(), SaveWorkflow.Notice.unchanged());
        if (transition instanceof DocumentIoCoordinator.Approval approval) {
            String conflict = conflict(context, filePath(approval.confirmation().physicalResource()));
            if (conflict != null) {
                request.owner().subject().io().cancel(approval.confirmation());
                return result(saveAsOrigin(request.origin()), SaveWorkflow.Notice.error(conflict), true);
            }
            if (approval.confirmation().requiresOverwriteConfirmation() || request.externalOverwrite()) {
                Path target = filePath(approval.confirmation().requestedResource());
                if (target == null) {
                    request.owner().subject().io().cancel(approval.confirmation());
                    return result(saveAsOrigin(request.origin()), SaveWorkflow.Notice.error(
                        "This provider requires its own overwrite approval. Choose a local file with Save As."), true);
                }
                var origin = saveAsOrigin(request.origin());
                var modal = (origin.isSaveAs() ? origin : UiModalState.saveAs()).confirmOverwrite(target);
                return new Result(modal, SaveFlowController.TextInputEffect.none(), SaveWorkflow.Notice.clear(), false,
                    null, new OverwriteApproval(request, approval.confirmation()), false, null);
            }
            return approve(context, request, approval.confirmation());
        }
        if (transition instanceof DocumentIoCoordinator.Failed failed) {
            if (!request.externalOverwrite() && failed.failure() instanceof dev.tuink.editor.storage.ExternalFileChangedException) {
                try { return pending(request.inspectExternalOverwrite()); }
                catch (IOException unavailable) { return result(UiModalState.none(), SaveWorkflow.Notice.error(unavailable.getMessage())); }
            }
            if (failed.failure() instanceof DocumentEncodingException && request.origin().saveFormat() != DocumentSaveFormat.UTF8) {
                var modal = (request.purpose() == SavePurpose.CURRENT ? UiModalState.saveAs() : saveAsOrigin(request.origin())).offerUtf8();
                var filename = request.purpose() == SavePurpose.CURRENT
                    ? SaveFlowController.TextInputEffect.setText(initialFilename(request.owner())) : SaveFlowController.TextInputEffect.none();
                return new Result(modal, filename, SaveWorkflow.Notice.error(SaveTargetPolicy.saveErrorMessage(failed.failure())),
                    false, null, null, false, failed.kind());
            }
            var modal = request.purpose() == SavePurpose.CURRENT ? UiModalState.none() : saveAsOrigin(request.origin());
            return new Result(modal, SaveFlowController.TextInputEffect.none(),
                SaveWorkflow.Notice.error(SaveTargetPolicy.saveErrorMessage(failed.failure())), modal.isSaveAs(),
                null, null, false, failed.kind());
        }
        if (!(transition instanceof DocumentIoCoordinator.Saved)) throw new IllegalStateException("Unexpected save transition: " + transition);
        var subject = request.owner().subject();
        Path target = filePath(subject.binding().resource());
        String name = target == null ? TerminalPlainText.singleLine(subject.binding().resource().displayName()) : NotificationPresentation.path(target);
        return new Result(UiModalState.none(), request.purpose() == SavePurpose.SAVE_AS
            ? SaveFlowController.TextInputEffect.clear() : SaveFlowController.TextInputEffect.none(),
            SaveWorkflow.Notice.success("Saved " + name + " [" + subject.metadata().charset().name() + "]"),
            false, null, null, true, null);
    }

    private static Result approve(Context context, Request request, DocumentIoCoordinator.Confirmation inspection) {
        String conflict = conflict(context, filePath(inspection.physicalResource()));
        if (conflict != null) {
            request.owner().subject().io().cancel(inspection);
            return result(saveAsOrigin(request.origin()), SaveWorkflow.Notice.error(conflict), true);
        }
        var transition = request.owner().subject().io().approve(inspection, request.externalOverwrite());
        if (transition instanceof DocumentIoCoordinator.Ready ready) {
            return pending(new Request(request.owner(), ready.work(), request.origin(), request.purpose(), request.submission(), request.externalOverwrite()));
        }
        return complete(context, request, transition);
    }

    static boolean matches(Context context, Request request) { return context.owner().equals(request.owner()); }

    private static String conflict(Context context, Path target) {
        return WorkspaceSaveConflictPolicy.conflict(context.openDocuments(), context.owner().subject(), target);
    }

    private static String initialFilename(Owner owner) {
        DocumentResource resource = owner.subject().binding().resource();
        if (resource == null) return "untitled.md";
        Path target = filePath(resource);
        if (target != null) return PathLabels.full(target);
        String hint = resource.nameHint();
        return hint == null || hint.isBlank() ? "untitled.md" : TerminalPlainText.singleLine(hint);
    }

    /** A provider-backed handle stays bound even when it has no JVM filesystem path. */
    private static Path filePath(DocumentResource resource) {
        return resource != null && resource.storage() instanceof JvmDocumentStorage ? JvmDocumentStorage.pathOf(resource) : null;
    }

    private static UiModalState saveAsOrigin(UiModalState modal) { return modal.isSaveOverwriteConfirm() ? modal.backToSaveAs() : modal; }
    private static Result busy(UiModalState modal) { return result(modal, SaveWorkflow.Notice.error("A save operation is already in progress"), modal.isSaveAs()); }
    private static Result pending(Request request) {
        return new Result(UiModalState.none(), SaveFlowController.TextInputEffect.none(), SaveWorkflow.Notice.clear(),
            false, request, null, false, null);
    }
    private static Result result(UiModalState modal, SaveWorkflow.Notice notice) { return result(modal, notice, false); }
    private static Result result(UiModalState modal, SaveWorkflow.Notice notice, boolean focus) {
        return result(modal, SaveFlowController.TextInputEffect.none(), notice, focus);
    }
    private static Result result(UiModalState modal, SaveFlowController.TextInputEffect filename, SaveWorkflow.Notice notice, boolean focus) {
        return new Result(modal, filename, notice, focus, null, null, false, null);
    }
}
