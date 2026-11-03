package dev.tuink.tui;

import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import java.util.Objects;

/** Explicit UI address. It is passed to handlers, never installed as mutable window context. */
record UiRecipient(Scope scope, PresentationId presentationId, ViewId viewId,
    long generation, TabUiState state, Flow flow) {
    enum Scope { WINDOW, PRESENTATION }
    enum Flow { LOCAL, WINDOW }

    UiRecipient {
        Objects.requireNonNull(scope); Objects.requireNonNull(state); Objects.requireNonNull(flow);
        if (scope == Scope.PRESENTATION) Objects.requireNonNull(presentationId);
        if (scope == Scope.WINDOW && presentationId != null) throw new IllegalArgumentException("Window UI has no presentation");
    }

    static UiRecipient presentation(WindowTab tab, Flow flow) {
        ViewId view = tab instanceof TerminalEditorApp.EditorTab source ? source.documentSession().viewId()
            : ((PreviewTab) tab).source() instanceof PreviewTab.LiveSource live ? live.viewId() : null;
        return new UiRecipient(Scope.PRESENTATION, tab.presentationId(), view,
            tab instanceof PreviewTab preview ? preview.generation() : 0, tab.uiState(), flow);
    }

    static UiRecipient window(TabUiState state, ViewId subject) {
        return new UiRecipient(Scope.WINDOW, null, subject, 0, state, Flow.WINDOW);
    }

    boolean current(TerminalEditorApp.SessionContext workspace, TabUiState windowUi) {
        if (state.closed) return false;
        if (scope == Scope.WINDOW) return state == windowUi;
        WindowTab tab = workspace.presentation(presentationId);
        if (tab == null || tab.uiState() != state) return false;
        if (tab instanceof PreviewTab preview) return preview.generation() == generation
            && Objects.equals(viewId, preview.source() instanceof PreviewTab.LiveSource live ? live.viewId() : null);
        return tab instanceof TerminalEditorApp.EditorTab source && Objects.equals(viewId, source.documentSession().viewId());
    }

    UiRecipient afterAcceptedSaveRebind(PreviewTab next, DocumentIoCoordinator.Work operation,
        DocumentIoCoordinator.Saved accepted) {
        Objects.requireNonNull(accepted); Objects.requireNonNull(operation);
        var subjectId = next.source() instanceof PreviewTab.LiveSource live ? live.documentId()
            : ((PreviewTab.ClosedSnapshot) next.source()).subject().documentId();
        if (!operation.documentId().equals(subjectId) || !operation.documentId().equals(accepted.documentId())
            || operation.operationId() != accepted.operationId())
            throw new IllegalArgumentException("Receipt does not belong to this operation");
        if (scope != Scope.PRESENTATION || !presentationId.equals(next.presentationId()) || state != next.uiState()
            || Math.incrementExact(generation) != next.generation())
            throw new IllegalArgumentException("Save rebind must advance the validated presentation exactly once");
        return presentation(next, flow);
    }
}
