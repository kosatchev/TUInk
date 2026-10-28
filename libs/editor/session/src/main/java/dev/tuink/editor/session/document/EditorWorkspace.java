package dev.tuink.editor.session.document;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.ViewId;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owner-thread membership and selection for documents and their editing views.
 * Frontends supply their view-local presentation as an opaque attachment; they
 * do not maintain a second mutable list or active-document pointer. Background
 * work retains document/view IDs and resolves them here when it returns.
 */
public final class EditorWorkspace<T> {
    private final Thread ownerThread = Thread.currentThread();
    private State<T> state = new State<>(List.of(), Map.of(), Map.of(), null);
    private final List<T> attachments = java.util.Collections.unmodifiableList(new AbstractList<>() {
        @Override public T get(int index) {
            requireOwnerThread();
            return state.entries().get(index).attachment();
        }
        @Override public int size() {
            requireOwnerThread();
            return state.entries().size();
        }
    });

    public record Entry<T>(EditorDocumentSession document, ViewId viewId, T attachment) {
        public Entry {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(viewId, "viewId");
            Objects.requireNonNull(attachment, "attachment");
        }
        public DocumentId documentId() { return document.documentId(); }
    }

    /** A read-only live projection of the sole workspace membership. */
    public List<T> attachments() { requireOwnerThread(); return attachments; }

    /** An immutable membership snapshot; its document sessions still belong to the owner. */
    public List<Entry<T>> entries() { requireOwnerThread(); return state.entries(); }

    public Entry<T> active() {
        requireOwnerThread();
        return state.selection() == null ? null : state.byView().get(state.selection());
    }

    public int activeIndex() {
        requireOwnerThread();
        return indexOf(state.selection());
    }

    public Entry<T> find(ViewId viewId) {
        requireOwnerThread();
        return state.byView().get(Objects.requireNonNull(viewId, "viewId"));
    }

    public EditorDocumentSession document(DocumentId documentId) {
        requireOwnerThread();
        return state.documents().get(Objects.requireNonNull(documentId, "documentId"));
    }

    public int indexOf(ViewId viewId) {
        requireOwnerThread();
        for (int index = 0; index < state.entries().size(); index++) {
            if (state.entries().get(index).viewId().equals(viewId)) return index;
        }
        return -1;
    }

    public void select(ViewId viewId) {
        requireOwnerThread();
        if (!state.byView().containsKey(Objects.requireNonNull(viewId, "viewId"))) {
            throw new IllegalArgumentException("Unknown workspace view");
        }
        if (!viewId.equals(state.selection())) state = state.withSelection(viewId);
    }

    public void selectIndex(int index) {
        requireOwnerThread();
        select(state.entries().get(index).viewId());
    }

    /** Appends and selects one attached editing view. */
    public void add(EditorDocumentSession document, ViewId viewId, T attachment) {
        admit(List.of(new Entry<>(document, viewId, attachment)), viewId);
    }

    /**
     * Admits a complete prepared batch atomically. Existing selection is kept;
     * the first item is selected only when the workspace was empty. Validation
     * and allocation finish before any membership or ownership is published.
     */
    public void addAll(List<Entry<T>> additions) {
        requireOwnerThread();
        admit(additions, state.selection());
    }

    private void admit(List<Entry<T>> additions, ViewId selection) {
        requireOwnerThread();
        Objects.requireNonNull(additions, "additions");
        if (additions.isEmpty()) return;
        List<Entry<T>> combined = new ArrayList<>(state.entries());
        combined.addAll(additions);
        State<T> next = prepare(combined, selection);
        // Immutable-map iterators may allocate per item. Materialize the whole
        // handshake set before the first document ownership change.
        EditorDocumentSession[] documents = next.documents().values().toArray(EditorDocumentSession[]::new);
        for (int index = 0; index < documents.length; index++) {
            EditorDocumentSession document = documents[index];
            EditorWorkspace<?> owner = document.workspace();
            if (owner != null && owner != this) throw new IllegalStateException("Document already belongs to another workspace");
        }
        // Owner-thread preflight makes these handshakes allocation-free and infallible.
        for (int index = 0; index < documents.length; index++) documents[index].claimWorkspace(this);
        state = next;
    }

    public void updateAttachment(ViewId viewId, T attachment) {
        requireOwnerThread();
        int index = indexOf(Objects.requireNonNull(viewId, "viewId"));
        if (index < 0) throw new IllegalArgumentException("Unknown workspace view");
        Entry<T> previous = state.entries().get(index);
        List<Entry<T>> updated = new ArrayList<>(state.entries());
        updated.set(index, new Entry<>(previous.document(), viewId, attachment));
        state = prepare(updated, state.selection());
    }

    /**
     * Retires precisely this view. A document's in-flight save may outlive one
     * of its views, but its last workspace view remains until I/O is admitted.
     * Recovery preservation/discard remains an explicit host lifecycle decision.
     */
    public Entry<T> remove(ViewId viewId) {
        requireOwnerThread();
        Entry<T> removed = find(viewId);
        if (removed == null) return null;
        List<Entry<T>> remaining = new ArrayList<>(state.entries());
        int index = indexOf(viewId);
        remaining.remove(index);
        boolean lastDocumentView = remaining.stream().noneMatch(entry -> entry.document() == removed.document());
        if (lastDocumentView && removed.document().io().busy()) {
            throw new IllegalStateException("Document is busy saving");
        }
        ViewId selection = state.selection();
        if (viewId.equals(selection)) {
            selection = remaining.isEmpty() ? null : remaining.get(Math.min(index, remaining.size() - 1)).viewId();
        }
        State<T> next = prepare(remaining, selection);
        // Backend detachment prepares any replacement document engine first.
        removed.document().detachWorkspaceView(this, viewId);
        if (lastDocumentView) removed.document().releaseWorkspace(this);
        state = next;
        return removed;
    }

    public boolean isOwnerThread() { return Thread.currentThread() == ownerThread; }

    private void requireOwnerThread() {
        if (!isOwnerThread()) throw new IllegalStateException("Workspace mutation requires its owner thread");
    }

    private State<T> prepare(List<Entry<T>> entries, ViewId selection) {
        List<Entry<T>> immutable = List.copyOf(entries);
        Map<ViewId, Entry<T>> byView = new HashMap<>();
        Map<DocumentId, EditorDocumentSession> documents = new HashMap<>();
        for (Entry<T> entry : immutable) {
            if (!entry.document().isOwnerThread()) throw new IllegalStateException("Document belongs to another owner thread");
            if (!entry.document().ownsView(entry.viewId())) throw new IllegalArgumentException("Unknown document view");
            if (byView.putIfAbsent(entry.viewId(), entry) != null) throw new IllegalArgumentException("Duplicate workspace view");
            EditorDocumentSession previous = documents.putIfAbsent(entry.documentId(), entry.document());
            if (previous != null && previous != entry.document()) throw new IllegalArgumentException("Duplicate document identity");
        }
        if (selection == null && !immutable.isEmpty()) selection = immutable.get(0).viewId();
        if (selection != null && !byView.containsKey(selection)) throw new IllegalArgumentException("Selection must address a workspace view");
        return new State<>(immutable, Map.copyOf(byView), Map.copyOf(documents), selection);
    }

    private record State<T>(List<Entry<T>> entries, Map<ViewId, Entry<T>> byView,
                            Map<DocumentId, EditorDocumentSession> documents, ViewId selection) {
        State<T> withSelection(ViewId viewId) { return new State<>(entries, byView, documents, viewId); }
    }
}
