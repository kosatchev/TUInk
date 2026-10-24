package dev.tuink.editor.document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An atomic, revision-guarded set of edits for one document.
 *
 * <p>Edits are canonicalized in ascending range order and must not overlap.
 * An applier validates every range against the original document, prepares
 * the complete result, then publishes it as one change. Coordinates always
 * refer to the original revision. Either all edits commit or none do.</p>
 */
public record WorkspaceEdit(DocumentId documentId, long expectedRevision, List<TextEdit> edits) {
    private static final Comparator<TextEdit> RANGE_ORDER = Comparator
        .comparing((TextEdit edit) -> edit.range().start())
        .thenComparing(edit -> edit.range().end());

    public WorkspaceEdit {
        Objects.requireNonNull(documentId, "documentId");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected revision must be non-negative");
        }
        Objects.requireNonNull(edits, "edits");

        ArrayList<TextEdit> canonical = new ArrayList<>(edits.size());
        for (TextEdit edit : edits) {
            canonical.add(Objects.requireNonNull(edit, "edit"));
        }
        canonical.sort(RANGE_ORDER);
        for (int i = 1; i < canonical.size(); i++) {
            TextRange previous = canonical.get(i - 1).range();
            TextRange current = canonical.get(i).range();
            if (previous.end().compareTo(current.start()) > 0
                || previous.start().equals(current.start())) {
                throw new IllegalArgumentException(
                    "Workspace edits must not overlap or share an insertion point"
                );
            }
        }
        edits = List.copyOf(canonical);
    }
}
