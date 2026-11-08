package dev.tuink.tui;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.session.document.DocumentSaveSubject;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.ResourceBinding;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One unresolved external byte version per document; independent of transient and resource-loss notices. */
final class ExternalDocumentNotices {
    record Notice(ExternalFileObserver.Observation observation, Object scopeId, boolean busy) {
        Notice { Objects.requireNonNull(observation); Objects.requireNonNull(scopeId); }
        DocumentSaveSubject subject() { return observation.subject(); }
        ResourceBinding expected() { return observation.expected(); }
        long generation() { return observation.generation(); }
        Path path() { return observation.path(); }
        DocumentLoad loaded() { return observation.loaded(); }
        Object version() { return observation.version(); }
        String failure() { return observation.failure(); }
    }

    private final Map<DocumentId, Notice> entries = new HashMap<>();

    Notice notice(DocumentId id) { return id == null ? null : entries.get(id); }
    boolean visible(DocumentId id) { return notice(id) != null; }
    boolean busy(DocumentId id) { Notice notice = notice(id); return notice != null && notice.busy(); }
    Object scopeId(DocumentId id) { Notice notice = notice(id); return notice == null ? null : notice.scopeId(); }

    /** Repeated observations of one byte version retain its painted identity and any pending decision. */
    boolean show(ExternalFileObserver.Observation observation) {
        Objects.requireNonNull(observation);
        if (observation.kind() != ExternalFileObserver.Kind.CHANGED || observation.version() == null)
            throw new IllegalArgumentException("Only a changed external version can own this notice");
        DocumentId id = observation.subject().documentId();
        Notice previous = entries.get(id);
        boolean same = previous != null && previous.subject() == observation.subject()
            && previous.expected().equals(observation.expected())
            && ExternalFileObserver.sameVersion(previous.observation(), observation);
        if (same && previous.busy()) return false;
        entries.put(id, new Notice(observation, same ? previous.scopeId() : new Object(), false));
        return !same;
    }

    boolean setBusy(Notice expected, boolean busy) {
        Objects.requireNonNull(expected);
        DocumentId id = expected.subject().documentId();
        Notice current = entries.get(id);
        if (current == null || current.scopeId() != expected.scopeId() || current.subject() != expected.subject()) return false;
        entries.put(id, new Notice(current.observation(), current.scopeId(), busy));
        return true;
    }

    boolean clearIfCurrent(Notice expected) {
        Objects.requireNonNull(expected);
        DocumentId id = expected.subject().documentId();
        Notice current = entries.get(id);
        if (current == null || current.scopeId() != expected.scopeId() || current.subject() != expected.subject()) return false;
        entries.remove(id);
        return true;
    }

    void clear(DocumentId id) { entries.remove(id); }
    void saved(DocumentId id) { clear(id); }
    void retain(Set<DocumentId> documents) { entries.keySet().retainAll(documents); }
}
