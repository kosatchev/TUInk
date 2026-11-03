package dev.tuink.tui;

import dev.tuink.editor.session.document.DocumentSaveSubject;
import dev.tuink.storage.jvm.JvmFileAdvisory;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** One resource-ownership check for live-document saves and closed-source exports. */
final class WorkspaceSaveConflictPolicy {
    private WorkspaceSaveConflictPolicy() { }

    record OpenDocument(DocumentSaveSubject subject, int displayNumber) {
        OpenDocument {
            Objects.requireNonNull(subject, "subject");
            if (displayNumber < 1) throw new IllegalArgumentException("Display number must be positive");
        }
    }

    static String conflict(List<OpenDocument> documents, DocumentSaveSubject saving, Path target) {
        if (target == null) return null;
        // Recovery may legitimately leave two editing documents bound to one file.
        // Preserve the saving live owner's existing identity before considering
        // another tab. Frozen snapshots are absent from this live-owner list.
        for (OpenDocument document : documents) {
            if (document.subject() == saving && JvmFileAdvisory.matchesOpenResource(saving.binding(), target)) {
                return null;
            }
        }
        for (OpenDocument document : documents) {
            if (document.subject() != saving && JvmFileAdvisory.matchesOpenResource(document.subject().binding(), target)) {
                return "Save failed: target is already open in tab " + document.displayNumber()
                    + ". Switch to that tab or choose another name.";
            }
        }
        return null;
    }
}
