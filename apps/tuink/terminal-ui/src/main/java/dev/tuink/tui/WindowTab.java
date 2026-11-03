package dev.tuink.tui;

import dev.tuink.editor.document.PresentationGroupId;
import dev.tuink.editor.document.PresentationId;

/** A visible workspace presentation; a projection is never an attached editing view. */
sealed interface WindowTab permits TerminalEditorApp.EditorTab {
    PresentationId presentationId();
    PresentationGroupId groupId();
    TabUiState uiState();
}
