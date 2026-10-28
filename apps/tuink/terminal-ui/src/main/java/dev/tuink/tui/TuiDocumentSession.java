package dev.tuink.tui;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.TabSession;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.diff.jgit.JgitLineDiff;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.core.EditorDocument;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.core.Position;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.TerminalEditorTextLayout;
import java.util.Objects;
import java.util.UUID;

/** TUInk composition boundary between the portable document session and its terminal view. */
final class TuiDocumentSession {
    private final EditorDocumentSession document;
    private final CoreDocumentBackend backend;
    private final TabSession view;
    private final ViewId viewId;

    private TuiDocumentSession(EditorDocumentSession document, CoreDocumentBackend backend, TabSession view, ViewId viewId) {
        this.document = Objects.requireNonNull(document, "document");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.view = Objects.requireNonNull(view, "view");
        this.viewId = Objects.requireNonNull(viewId, "viewId");
    }

    static TuiDocumentSession create(EditorClipboard clipboard) {
        TabSession view = new EditorDocument(new TextModel(new SimpleLineBuffer()))
            .attach(TerminalEditorTextLayout::new, clipboard, new JgitLineDiff());
        return adopt(view);
    }

    /** Connects one existing terminal view to the same command and storage session boundary. */
    static TuiDocumentSession adopt(TabSession view) {
        CoreDocumentBackend backend = CoreDocumentBackend.adopt(new DocumentId(UUID.randomUUID().toString()), view);
        EditorDocumentSession document = EditorDocumentSession.connect(backend);
        return new TuiDocumentSession(document, backend, view, document.defaultViewId());
    }

    /** A second terminal projection shares the session/history but owns its cursor and layout. */
    TuiDocumentSession attachView(EditorClipboard clipboard) {
        TabSession attached = view.engine().document().attach(TerminalEditorTextLayout::new, clipboard, new JgitLineDiff());
        ViewId id = new ViewId(attached.state().viewId());
        TuiDocumentSession prepared = new TuiDocumentSession(document, backend, attached, id);
        backend.registerView(attached);
        return prepared;
    }

    /** Publish an already decoded document without accessing its file again. */
    TuiDocumentSession openPrepared(DocumentLoad prepared) {
        document.openPrepared(prepared);
        return this;
    }

    ViewId viewId() {
        return viewId;
    }

    DocumentCommandResult.Applied execute(DocumentCommand command) {
        DocumentCommandResult result = document.execute(viewId(), command);
        if (result instanceof DocumentCommandResult.Applied applied) return applied;
        throw new IllegalStateException("Terminal command was rejected: "
            + ((DocumentCommandResult.Rejected) result).reason());
    }

    void navigate(Position position, boolean selecting) {
        execute(new DocumentCommand.MoveTo(new TextPosition(position.line(), position.col()), selecting));
    }

    EditorDocumentSession document() {
        return document;
    }

    RenderPlanBuilder newRenderPlanBuilder(LineHighlighter highlighter) {
        return new RenderPlanBuilder(highlighter, view.textLayout(), document.documentId());
    }

    /** Stable view identity is also used by recovery and Save As draft tracking. */
    TabSession view() {
        return view;
    }

}
