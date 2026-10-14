package dev.tuink.editor.session.document;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.TabSession;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import java.util.Objects;

/**
 * Internal TUInk composition boundary, not part of the portable session API.
 * Only the TUI document-session adapter may use this bridge in production.
 */
public final class CoreSessionBridge {
    private CoreSessionBridge() {
    }

    /** Adopts an existing core document without copying text, metadata, history or savepoint baselines. */
    public static EditorDocumentSession adopt(DocumentId documentId, TabSession existingView) {
        Objects.requireNonNull(existingView, "existingView");
        return new EditorDocumentSession(documentId, existingView.engine().document());
    }

    /** Creates a view-local state/layout over the existing single document and history. */
    public static TabSession attach(
        EditorDocumentSession session, EditorTextLayout.Factory layoutFactory, EditorClipboard clipboard
    ) {
        Objects.requireNonNull(session, "session").requireOwnerThread();
        return session.document().attach(layoutFactory, clipboard);
    }

    /** Publishes UI mutations; unchanged revisions reuse the current immutable snapshot. */
    public static DocumentSnapshot publish(EditorDocumentSession session) {
        return Objects.requireNonNull(session, "session").publishIfChanged();
    }
}
