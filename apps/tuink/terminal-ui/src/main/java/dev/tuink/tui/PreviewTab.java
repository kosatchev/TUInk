package dev.tuink.tui;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.document.PresentationGroupId;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import java.util.Objects;

/** Immutable membership attachment. Only the tab's UI state is retained across binding changes. */
final class PreviewTab implements WindowTab {
    sealed interface Source permits LiveSource, ClosedSnapshot {}
    record LiveSource(DocumentId documentId, ViewId viewId) implements Source {
        LiveSource { Objects.requireNonNull(documentId); Objects.requireNonNull(viewId); }
    }
    record ClosedSnapshot(FrozenDocumentSession subject) implements Source {
        ClosedSnapshot { Objects.requireNonNull(subject); }
        FrozenDocumentSource source() { return subject.source(); }
    }

    private final PresentationId presentationId;
    private final PresentationGroupId groupId;
    private final Source source;
    private final long generation;
    private final TabUiState uiState;

    PreviewTab(TerminalEditorApp.EditorTab editor) {
        this(PresentationId.create(), editor.groupId(),
            new LiveSource(editor.documentSession().document().documentId(), editor.documentSession().viewId()),
            0, new TabUiState());
    }

    private PreviewTab(PresentationId presentationId, PresentationGroupId groupId, Source source,
        long generation, TabUiState uiState) {
        this.presentationId = Objects.requireNonNull(presentationId);
        this.groupId = Objects.requireNonNull(groupId);
        this.source = Objects.requireNonNull(source);
        this.generation = generation;
        this.uiState = Objects.requireNonNull(uiState);
    }

    @Override public PresentationId presentationId() { return presentationId; }
    @Override public PresentationGroupId groupId() { return groupId; }
    @Override public TabUiState uiState() { return uiState; }
    Source source() { return source; }
    long generation() { return generation; }

    PreviewTab freeze(FrozenDocumentSource captured) {
        return new PreviewTab(presentationId, groupId, new ClosedSnapshot(FrozenDocumentSession.create(captured)),
            Math.incrementExact(generation), uiState);
    }

    PreviewTab saved() {
        return new PreviewTab(presentationId, groupId, source, Math.incrementExact(generation), uiState);
    }

    void close() {
        if (source instanceof ClosedSnapshot frozen) frozen.subject().close();
        uiState.close();
    }

    PreviewTab relink(TerminalEditorApp.EditorTab editor) {
        return new PreviewTab(presentationId, groupId,
            new LiveSource(editor.documentSession().document().documentId(), editor.documentSession().viewId()),
            Math.incrementExact(generation), uiState);
    }
}
