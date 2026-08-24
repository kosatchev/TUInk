package dev.tuink.editor.session.event;

import dev.tuink.editor.document.DocumentSnapshot;
import java.util.Objects;

/** Rejects asynchronous results computed from another document or stale content. */
public final class RevisionGate {
    private RevisionGate() {
    }

    public static boolean accepts(DocumentSnapshot current, RevisionedExternalEvent<?> event) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(event, "event");
        return current.documentId().equals(event.documentId())
            && current.revision() == event.sourceRevision();
    }
}
