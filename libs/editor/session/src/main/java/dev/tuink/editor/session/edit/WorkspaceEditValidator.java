package dev.tuink.editor.session.edit;

import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.WorkspaceEdit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure validation performed before a workspace edit reaches mutable editor state. */
public final class WorkspaceEditValidator {
    private WorkspaceEditValidator() {
    }

    public static List<Violation> validate(DocumentSnapshot current, WorkspaceEdit proposed) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(proposed, "proposed");
        List<Violation> violations = new ArrayList<>();

        if (!current.documentId().equals(proposed.documentId())) {
            violations.add(new Violation(
                Code.DOCUMENT_MISMATCH,
                "Workspace edit targets a different document"
            ));
        }
        if (current.revision() != proposed.expectedRevision()) {
            violations.add(new Violation(
                Code.STALE_REVISION,
                "Expected revision " + proposed.expectedRevision()
                    + " but current revision is " + current.revision()
            ));
        }
        for (int index = 0; index < proposed.edits().size(); index++) {
            TextEdit edit = proposed.edits().get(index);
            if (!current.contains(edit.range())) {
                violations.add(new Violation(
                    Code.RANGE_OUT_OF_BOUNDS,
                    "Edit " + index + " is outside the current document"
                ));
                continue;
            }
            if (!current.isCodePointBoundary(edit.range().start())
                || !current.isCodePointBoundary(edit.range().end())) {
                violations.add(new Violation(
                    Code.SPLIT_SURROGATE_PAIR,
                    "Edit " + index + " splits a UTF-16 surrogate pair"
                ));
            }
        }
        return List.copyOf(violations);
    }

    public enum Code {
        DOCUMENT_MISMATCH,
        STALE_REVISION,
        RANGE_OUT_OF_BOUNDS,
        SPLIT_SURROGATE_PAIR
    }

    public record Violation(Code code, String message) {
        public Violation {
            Objects.requireNonNull(code, "code");
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("Validation message must not be blank");
            }
        }
    }
}
