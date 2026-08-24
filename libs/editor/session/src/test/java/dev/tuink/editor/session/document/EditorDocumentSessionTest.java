package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.session.edit.WorkspaceEditValidator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EditorDocumentSessionTest {
    private static final DocumentId DOCUMENT_ID = new DocumentId("document-1");
    private static final List<String> INITIAL_LINES = List.of("one", "A😀B", "three");

    @Test
    void staleWrongDocumentOutOfBoundsAndSurrogateEditsNeverMutate() {
        EditorDocumentSession session = session();
        DocumentSnapshot initial = session.snapshot();

        assertValidationRejection(
            session,
            workspace(DOCUMENT_ID, initial.revision() + 1, edit(0, 0, 0, 1, "x")),
            WorkspaceEditValidator.Code.STALE_REVISION,
            initial
        );
        assertValidationRejection(
            session,
            workspace(new DocumentId("other"), initial.revision(), edit(0, 0, 0, 1, "x")),
            WorkspaceEditValidator.Code.DOCUMENT_MISMATCH,
            initial
        );
        assertValidationRejection(
            session,
            workspace(
                DOCUMENT_ID,
                initial.revision(),
                edit(0, 0, 0, 1, "valid-but-must-not-apply"),
                edit(2, 0, 2, 6, "invalid")
            ),
            WorkspaceEditValidator.Code.RANGE_OUT_OF_BOUNDS,
            initial
        );
        assertValidationRejection(
            session,
            workspace(DOCUMENT_ID, initial.revision(), edit(1, 2, 1, 3, "x")),
            WorkspaceEditValidator.Code.SPLIT_SURROGATE_PAIR,
            initial
        );

        assertEquals(initial, session.snapshot());
        assertFalse(session.undo());
    }

    @Test
    void multiEditCommitsAtomicallyAndUndoesOnce() {
        EditorDocumentSession session = session();
        DocumentSnapshot initial = session.snapshot();
        WorkspaceEdit edit = new WorkspaceEdit(
            DOCUMENT_ID,
            initial.revision(),
            List.of(
                edit(2, 5, 2, 5, "!"),
                edit(0, 2, 0, 3, "END"),
                edit(0, 0, 0, 1, "BEGIN"),
                edit(1, 1, 1, 3, "x")
            )
        );

        WorkspaceEditResult.Applied applied = assertInstanceOf(
            WorkspaceEditResult.Applied.class,
            session.applyWorkspaceEdit(edit)
        );

        assertTrue(applied.changed());
        assertEquals(List.of("BEGINnEND", "AxB", "three!"), applied.snapshot().lines());
        assertTrue(applied.snapshot().revision() > initial.revision());
        assertEquals(applied.snapshot(), session.snapshot());

        assertTrue(session.undo());
        assertEquals(INITIAL_LINES, session.snapshot().lines());
        assertTrue(session.snapshot().revision() > applied.snapshot().revision());
        assertFalse(session.undo());
    }

    @Test
    void foreignThreadIsRejectedWithoutReadingOrMutatingCoreState() throws InterruptedException {
        EditorDocumentSession session = session();
        DocumentSnapshot initial = session.snapshot();
        WorkspaceEdit proposed = workspace(
            DOCUMENT_ID,
            initial.revision(),
            edit(0, 0, 0, 3, "ONE")
        );
        AtomicReference<WorkspaceEditResult> result = new AtomicReference<>();
        Thread foreign = new Thread(() -> result.set(session.applyWorkspaceEdit(proposed)));

        foreign.start();
        foreign.join();

        WorkspaceEditResult.Rejected rejected = assertInstanceOf(
            WorkspaceEditResult.Rejected.class,
            result.get()
        );
        assertEquals(
            WorkspaceEditResult.RejectionReason.WRONG_OWNER_THREAD,
            rejected.reason()
        );
        assertTrue(rejected.violations().isEmpty());
        assertEquals(initial, rejected.currentSnapshot());
        assertEquals(initial, session.snapshot());
        assertFalse(session.undo());

        assertInstanceOf(
            WorkspaceEditResult.Applied.class,
            session.applyWorkspaceEdit(proposed)
        );
    }

    private static void assertValidationRejection(
        EditorDocumentSession session,
        WorkspaceEdit proposed,
        WorkspaceEditValidator.Code expectedCode,
        DocumentSnapshot expectedSnapshot
    ) {
        WorkspaceEditResult.Rejected rejected = assertInstanceOf(
            WorkspaceEditResult.Rejected.class,
            session.applyWorkspaceEdit(proposed)
        );
        assertEquals(
            WorkspaceEditResult.RejectionReason.VALIDATION_FAILED,
            rejected.reason()
        );
        assertTrue(rejected.violations().stream().anyMatch(v -> v.code() == expectedCode));
        assertEquals(expectedSnapshot, rejected.currentSnapshot());
        assertEquals(expectedSnapshot, session.snapshot());
    }

    private static EditorDocumentSession session() {
        return EditorDocumentSession.create(DOCUMENT_ID, INITIAL_LINES);
    }

    private static WorkspaceEdit workspace(
        DocumentId documentId,
        long revision,
        TextEdit... edits
    ) {
        return new WorkspaceEdit(documentId, revision, List.of(edits));
    }

    private static TextEdit edit(
        int startLine,
        int startOffset,
        int endLine,
        int endOffset,
        String replacement
    ) {
        return new TextEdit(
            new TextRange(
                new TextPosition(startLine, startOffset),
                new TextPosition(endLine, endOffset)
            ),
            replacement
        );
    }
}
