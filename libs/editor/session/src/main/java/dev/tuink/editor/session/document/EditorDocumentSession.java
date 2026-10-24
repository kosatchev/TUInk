package dev.tuink.editor.session.document;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorDocument;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.DocumentEditLimitException;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.LineText;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.core.TextReplacement;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.session.edit.WorkspaceEditValidator;
import java.util.AbstractList;
import java.util.List;
import java.util.Objects;

/**
 * Single-threaded session for one document with headless and attached editing views.
 *
 * <p>Only the thread that creates a session may mutate it. Immutable snapshots
 * are safely published and may be read by background providers; their results
 * return as revision-guarded {@link WorkspaceEdit}s. Headless workspace commands and
 * attached frontend commands use the same underlying document and history.</p>
 */
public final class EditorDocumentSession {
    private final DocumentId documentId;
    private final EditorDocument document;
    private final EditorEngine engine;
    private final Thread ownerThread;
    private volatile DocumentSnapshot publishedSnapshot;

    EditorDocumentSession(DocumentId documentId, EditorDocument document) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.document = Objects.requireNonNull(document, "document");
        this.engine = document.attach(LogicalEditorTextLayout::new, new EditorClipboard()).engine();
        this.ownerThread = Thread.currentThread();
        this.publishedSnapshot = captureSnapshot();
    }

    /** Creates a headless session whose initial content starts at revision zero. */
    public static EditorDocumentSession create(DocumentId documentId, List<String> initialLines) {
        DocumentSnapshot checked = new DocumentSnapshot(documentId, 0, initialLines);
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, checked.line(0));
        for (int line = 1; line < checked.lineCount(); line++) {
            buffer.insertLine(line, checked.line(line));
        }
        TextModel model = new TextModel(buffer);
        return new EditorDocumentSession(documentId, new EditorDocument(model));
    }

    /** Stable logical identity; reading it never captures or publishes document text. */
    public DocumentId documentId() {
        return documentId;
    }

    /**
     * On the owner thread, returns and publishes the current document on demand.
     * Unchanged revisions reuse the same immutable snapshot. Background callers receive the
     * last published snapshot without reading mutable document state; it may precede recent UI edits.
     */
    public DocumentSnapshot snapshot() {
        return isOwnerThread() ? publishIfChanged() : publishedSnapshot;
    }

    /**
     * Validates and atomically applies an edit on the owner thread.
     * Owner-thread validation first publishes the current revision. Rejected edits never mutate
     * document content; foreign-thread rejections use only the last published snapshot.
     */
    public WorkspaceEditResult applyWorkspaceEdit(WorkspaceEdit proposed) {
        Objects.requireNonNull(proposed, "proposed");
        DocumentSnapshot current = publishedSnapshot;
        if (!isOwnerThread()) {
            return new WorkspaceEditResult.Rejected(
                WorkspaceEditResult.RejectionReason.WRONG_OWNER_THREAD,
                current,
                List.of()
            );
        }
        current = publishIfChanged();

        List<WorkspaceEditValidator.Violation> violations =
            WorkspaceEditValidator.validate(current, proposed);
        if (!violations.isEmpty()) {
            return new WorkspaceEditResult.Rejected(
                WorkspaceEditResult.RejectionReason.VALIDATION_FAILED,
                current,
                violations
            );
        }

        // The contract already owns a canonical immutable edit list. Adapt it
        // lazily rather than duplicating every range before core admission.
        List<TextReplacement> replacements = new AbstractList<>() {
            @Override public int size() { return proposed.edits().size(); }
            @Override public TextReplacement get(int index) {
                TextEdit edit = proposed.edits().get(index);
                return new TextReplacement(position(edit.range().start()), position(edit.range().end()), edit.replacement());
            }
        };
        try {
            WorkspaceEditResult.Applied result = engine.applyTextReplacements(replacements,
                this::prepareMutationResult);
            publishedSnapshot = result.snapshot();
            return result;
        } catch (DocumentEditLimitException tooLarge) {
            return new WorkspaceEditResult.Rejected(
                WorkspaceEditResult.RejectionReason.EDIT_LIMIT_EXCEEDED, current, List.of());
        }
    }

    /** Undoes exactly one committed session mutation. */
    public boolean undo() {
        requireOwnerThread();
        WorkspaceEditResult.Applied result = engine.undo(this::prepareMutationResult);
        publishedSnapshot = result.snapshot();
        return result.changed();
    }

    /** Redoes exactly one mutation from the same document history as every attached view. */
    public boolean redo() {
        requireOwnerThread();
        WorkspaceEditResult.Applied result = engine.redo(this::prepareMutationResult);
        publishedSnapshot = result.snapshot();
        return result.changed();
    }

    private WorkspaceEditResult.Applied prepareMutationResult(boolean changed) {
        return new WorkspaceEditResult.Applied(changed ? captureSnapshot() : publishedSnapshot, changed);
    }

    EditorDocument document() {
        return document;
    }

    DocumentSnapshot publishIfChanged() {
        requireOwnerThread();
        if (publishedSnapshot.revision() != document.model().revision()) {
            publishedSnapshot = captureSnapshot();
        }
        return publishedSnapshot;
    }

    private DocumentSnapshot captureSnapshot() {
        TextModel model = engine.model();
        return DocumentSnapshot.fromImmutableLines(documentId, model.revision(),
            model.snapshotTextLines().stream().map(LineText::immutableText).toList());
    }

    private boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    void requireOwnerThread() {
        if (!isOwnerThread()) {
            throw new IllegalStateException("Editor document session mutation requires its owner thread");
        }
    }

    private static Position position(TextPosition position) {
        return new Position(position.line(), position.utf16Offset());
    }
}
