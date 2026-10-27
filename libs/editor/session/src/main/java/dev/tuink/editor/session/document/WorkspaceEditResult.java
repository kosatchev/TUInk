package dev.tuink.editor.session.document;

import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.session.edit.WorkspaceEditValidator;
import java.util.List;
import java.util.Objects;

/** Typed outcome of submitting a revision-guarded workspace edit to a session. */
public sealed interface WorkspaceEditResult
    permits WorkspaceEditResult.Applied, WorkspaceEditResult.Rejected {

    /** The edit was accepted; {@code changed} distinguishes a logical no-op. */
    record Applied(DocumentSnapshot snapshot, boolean changed) implements WorkspaceEditResult {
        public Applied {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** The edit was rejected before any mutable editor state was touched. */
    record Rejected(
        RejectionReason reason,
        DocumentSnapshot currentSnapshot,
        List<WorkspaceEditValidator.Violation> violations
    ) implements WorkspaceEditResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(currentSnapshot, "currentSnapshot");
            violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
            if (reason == RejectionReason.VALIDATION_FAILED && violations.isEmpty()) {
                throw new IllegalArgumentException("A validation rejection must contain violations");
            }
            if (reason != RejectionReason.VALIDATION_FAILED && !violations.isEmpty()) {
                throw new IllegalArgumentException("An admission rejection must not contain edit violations");
            }
        }
    }

    enum RejectionReason {
        WRONG_OWNER_THREAD,
        DOCUMENT_BUSY,
        EDIT_LIMIT_EXCEEDED,
        VALIDATION_FAILED
    }
}
