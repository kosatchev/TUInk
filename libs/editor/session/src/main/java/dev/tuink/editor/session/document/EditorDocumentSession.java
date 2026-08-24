package dev.tuink.editor.session.document;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.LogicalEditorTextLayout;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Single-threaded owner of one mutable headless editor document.
 *
 * <p>Only the thread that creates a session may mutate it. Immutable snapshots
 * are safely published and may be read by background providers; their results
 * return as revision-guarded {@link WorkspaceEdit}s.</p>
 */
public final class EditorDocumentSession {
    private final DocumentId documentId;
    private final EditorEngine engine;
    private final Thread ownerThread;
    private volatile DocumentSnapshot publishedSnapshot;

    private EditorDocumentSession(DocumentId documentId, EditorEngine engine) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.engine = Objects.requireNonNull(engine, "engine");
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
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        return new EditorDocumentSession(documentId, engine);
    }

    /** Returns the latest immutable snapshot; this read is safe from any thread. */
    public DocumentSnapshot snapshot() {
        return publishedSnapshot;
    }

    /**
     * Validates and atomically applies an edit on the owner thread.
     * Rejections never access or mutate the underlying editor model.
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

        List<WorkspaceEditValidator.Violation> violations =
            WorkspaceEditValidator.validate(current, proposed);
        if (!violations.isEmpty()) {
            return new WorkspaceEditResult.Rejected(
                WorkspaceEditResult.RejectionReason.VALIDATION_FAILED,
                current,
                violations
            );
        }

        List<TextReplacement> replacements = new ArrayList<>(proposed.edits().size());
        for (TextEdit edit : proposed.edits()) {
            replacements.add(new TextReplacement(
                position(edit.range().start()),
                position(edit.range().end()),
                edit.replacement()
            ));
        }
        boolean changed = engine.applyTextReplacements(replacements);
        DocumentSnapshot after = captureSnapshot();
        publishedSnapshot = after;
        return new WorkspaceEditResult.Applied(after, changed);
    }

    /** Undoes exactly one committed session mutation. */
    public boolean undo() {
        requireOwnerThread();
        boolean undone = engine.undo();
        if (undone) {
            publishedSnapshot = captureSnapshot();
        }
        return undone;
    }

    private DocumentSnapshot captureSnapshot() {
        TextModel model = engine.model();
        return new DocumentSnapshot(documentId, model.revision(), model.snapshotLines());
    }

    private boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    private void requireOwnerThread() {
        if (!isOwnerThread()) {
            throw new IllegalStateException("Editor document session mutation requires its owner thread");
        }
    }

    private static Position position(TextPosition position) {
        return new Position(position.line(), position.utf16Offset());
    }
}
