package dev.tuink.editor.session.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.WorkspaceEdit;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceEditValidatorTest {
    @Test
    void rejectsStaleOutOfBoundsAndSurrogateSplittingEditsBeforeMutation() {
        DocumentId id = new DocumentId("document-1");
        DocumentSnapshot current = new DocumentSnapshot(id, 4, List.of("A😀B"));

        assertEquals(
            WorkspaceEditValidator.Code.STALE_REVISION,
            WorkspaceEditValidator.validate(
                current,
                edit(id, 3, new TextPosition(0, 0), new TextPosition(0, 1))
            ).get(0).code()
        );
        assertEquals(
            WorkspaceEditValidator.Code.RANGE_OUT_OF_BOUNDS,
            WorkspaceEditValidator.validate(
                current,
                edit(id, 4, new TextPosition(0, 0), new TextPosition(0, 5))
            ).get(0).code()
        );
        assertEquals(
            WorkspaceEditValidator.Code.SPLIT_SURROGATE_PAIR,
            WorkspaceEditValidator.validate(
                current,
                edit(id, 4, new TextPosition(0, 2), new TextPosition(0, 3))
            ).get(0).code()
        );
        assertTrue(
            WorkspaceEditValidator.validate(
                current,
                edit(id, 4, new TextPosition(0, 1), new TextPosition(0, 3))
            ).isEmpty()
        );
    }

    private static WorkspaceEdit edit(
        DocumentId id,
        long revision,
        TextPosition start,
        TextPosition end
    ) {
        return new WorkspaceEdit(
            id,
            revision,
            List.of(new TextEdit(new TextRange(start, end), "x"))
        );
    }
}
