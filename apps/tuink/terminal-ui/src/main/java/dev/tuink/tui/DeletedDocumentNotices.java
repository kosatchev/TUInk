package dev.tuink.tui;

import dev.tuiframe.widget.NoticeBanner;
import dev.tuink.editor.document.DocumentId;
import dev.tuiframe.explorer.ui.PathPresentation;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Document-owned resource-loss notices, independent of transient presentation messages. */
final class DeletedDocumentNotices {
    private static final class Entry {
        private final Object scopeId = new Object();
        private final Path originalPath;
        private final NoticeBanner banner;
        private boolean dismissed;

        private Entry(Path originalPath, String reason) {
            this.originalPath = originalPath;
            banner = new NoticeBanner(reason + ": " + PathPresentation.full(originalPath)
                + ". Text is kept as a draft.", NoticeBanner.Severity.WARNING);
        }
    }

    private final Map<DocumentId, Entry> entries = new HashMap<>();

    void show(DocumentId document, Path originalPath, String reason) {
        Objects.requireNonNull(document); Objects.requireNonNull(originalPath); Objects.requireNonNull(reason);
        Path path = originalPath.toAbsolutePath().normalize();
        var previous = entries.get(document);
        if (previous == null || !previous.originalPath.equals(path)) entries.put(document, new Entry(path, reason));
    }

    boolean visible(DocumentId document) {
        Entry entry = document == null ? null : entries.get(document);
        return entry != null && !entry.dismissed;
    }

    /**
     * Resource loss remains unsaved after Dismiss. Closed-source previews have no editor dirty
     * state, so close and quit guards must use this event rather than banner visibility.
     */
    boolean hasUnsavedRemoval(DocumentId document) {
        return document != null && entries.containsKey(document);
    }

    NoticeBanner banner(DocumentId document) {
        Entry entry = entries.get(document);
        return entry == null || entry.dismissed ? null : entry.banner;
    }

    Path originalPath(DocumentId document) {
        Entry entry = entries.get(document);
        return entry == null ? null : entry.originalPath;
    }

    Object scopeId(DocumentId document) { return entries.get(document).scopeId; }

    void dismiss(DocumentId document) {
        Entry entry = entries.get(document);
        if (entry != null) entry.dismissed = true;
    }

    /** Only an accepted file save clears the event; recovery snapshots never call this method. */
    void saved(DocumentId document) { entries.remove(document); }

    void retain(Set<DocumentId> documents) { entries.keySet().retainAll(documents); }
}
