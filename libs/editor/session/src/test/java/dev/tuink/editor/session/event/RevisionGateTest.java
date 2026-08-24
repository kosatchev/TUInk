package dev.tuink.editor.session.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class RevisionGateTest {
    @Test
    void acceptsOnlyTheCurrentDocumentRevision() {
        DocumentId id = new DocumentId("document-1");
        DocumentSnapshot current = new DocumentSnapshot(id, 5, List.of("text"));

        assertTrue(RevisionGate.accepts(current, new RevisionedExternalEvent<>(id, 5, "ok")));
        assertFalse(RevisionGate.accepts(current, new RevisionedExternalEvent<>(id, 4, "stale")));
        assertFalse(RevisionGate.accepts(
            current,
            new RevisionedExternalEvent<>(new DocumentId("document-2"), 5, "foreign")
        ));
    }
}
