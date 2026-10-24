package dev.tuink.tui;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.TabSession;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.session.document.CoreSessionBridge;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.TerminalEditorTextLayout;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** TUInk composition boundary between the portable document session and its terminal view. */
final class TuiDocumentSession {
    private final EditorDocumentSession document;
    private final TabSession view;

    private TuiDocumentSession(EditorDocumentSession document, TabSession view) {
        this.document = Objects.requireNonNull(document, "document");
        this.view = Objects.requireNonNull(view, "view");
    }

    static TuiDocumentSession create(EditorClipboard clipboard) {
        EditorDocumentSession document = EditorDocumentSession.create(
            new DocumentId(UUID.randomUUID().toString()), List.of(""));
        return new TuiDocumentSession(document,
            CoreSessionBridge.attach(document, TerminalEditorTextLayout::new, clipboard));
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
