package dev.tuink.tui;

import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.document.ViewId;
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
        var source = (TerminalEditorApp.EditorTab) tab;
        return new UiRecipient(Scope.PRESENTATION, tab.presentationId(), source.documentSession().viewId(),
            0, tab.uiState(), flow);
    }

    static UiRecipient window(TabUiState state, ViewId subject) {
        return new UiRecipient(Scope.WINDOW, null, subject, 0, state, Flow.WINDOW);
    }

    boolean current(TerminalEditorApp.SessionContext workspace, TabUiState windowUi) {
        if (state.closed) return false;
        if (scope == Scope.WINDOW) return state == windowUi;
        WindowTab tab = workspace.presentation(presentationId);
        if (tab == null || tab.uiState() != state) return false;
        return tab instanceof TerminalEditorApp.EditorTab source && Objects.equals(viewId, source.documentSession().viewId());
    }

}
