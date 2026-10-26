package dev.tuink.editor.command;

import java.util.Objects;

/** A bounded command receipt. Full immutable text is published separately on demand. */
public sealed interface DocumentCommandResult {
    DocumentCommandTarget target();

    record Applied(DocumentCommandTarget target, long revision, boolean textChanged, boolean viewChanged,
                   boolean performed, int affectedCount, CommentAction commentAction) implements DocumentCommandResult {
        public Applied {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(commentAction, "commentAction");
            if (revision < 0 || affectedCount < 0) throw new IllegalArgumentException("Negative command result");
        }
    }
    record Rejected(DocumentCommandTarget target, RejectionReason reason) implements DocumentCommandResult {
        public Rejected {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(reason, "reason");
        }
    }
    enum CommentAction { NONE, COMMENTED, UNCOMMENTED, UNSUPPORTED, NO_CONTENT }
    enum RejectionReason { WRONG_OWNER_THREAD, WRONG_DOCUMENT, UNKNOWN_VIEW, STALE_DOCUMENT, STALE_DOCUMENT_STATE, STALE_VIEW }
}
