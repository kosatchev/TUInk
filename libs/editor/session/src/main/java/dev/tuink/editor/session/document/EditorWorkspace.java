package dev.tuink.editor.session.document;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.PresentationGroupId;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.document.ViewId;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owner-thread registry of presentation order, selection and open editing documents.
 * Editing-only collections are projections of this same immutable state. A generic
 * projection owns no document session, editing position, history or I/O coordinator.
 */
public final class EditorWorkspace<T> {
    public sealed interface Binding permits Editing, Projection {}

    public record Editing(EditorDocumentSession document, ViewId viewId) implements Binding {
        public Editing {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(viewId, "viewId");
        }
    }

    /** The opaque frontend attachment supplies the projection's content and local UI state. */
    public enum Projection implements Binding { INSTANCE }

    public record Presentation<T>(PresentationId id, PresentationGroupId groupId, Binding binding, T attachment) {
        public Presentation {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(attachment, "attachment");
        }
        public Presentation<T> withAttachment(T next) { return new Presentation<>(id, groupId, binding, next); }
    }

    /** Editing-only membership, also used to admit a batch of source views. */
    public record Entry<T>(EditorDocumentSession document, ViewId viewId, T attachment) {
        public Entry {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(viewId, "viewId");
            Objects.requireNonNull(attachment, "attachment");
        }
        public DocumentId documentId() { return document.documentId(); }
    }

    private final Thread ownerThread = Thread.currentThread();
    private State<T> state = new State<>(List.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(), null);
    private final List<T> attachments = attachmentProjection(false);
    private final List<T> presentationAttachments = attachmentProjection(true);

    private List<T> attachmentProjection(boolean allPresentations) {
        return java.util.Collections.unmodifiableList(new AbstractList<>() {
            @Override public T get(int index) {
                requireOwnerThread();
                return allPresentations ? state.presentations().get(index).attachment() : state.entries().get(index).attachment();
            }
            @Override public int size() {
                requireOwnerThread();
                return allPresentations ? state.presentations().size() : state.entries().size();
            }
        });
    }

    /** Read-only live projections; neither collection owns another mutable membership list. */
    public List<T> attachments() { requireOwnerThread(); return attachments; }
    public List<T> presentationAttachments() { requireOwnerThread(); return presentationAttachments; }
    public List<Entry<T>> entries() { requireOwnerThread(); return state.entries(); }
    public List<Presentation<T>> presentations() { requireOwnerThread(); return state.presentations(); }

    public Presentation<T> activePresentation() {
        requireOwnerThread();
        return state.selection() == null ? null : state.byPresentation().get(state.selection());
    }

    /** No editing document is active while an independent projection is selected. */
    public Entry<T> active() {
        Presentation<T> selected = activePresentation();
        return selected != null && selected.binding() instanceof Editing editing ? state.byView().get(editing.viewId()) : null;
    }

    public int activeIndex() { Entry<T> selected = active(); return selected == null ? -1 : indexOf(selected.viewId()); }
    public int activePresentationIndex() { requireOwnerThread(); return indexOf(state.selection()); }
    public Entry<T> find(ViewId id) { requireOwnerThread(); return state.byView().get(Objects.requireNonNull(id, "viewId")); }
    public Presentation<T> find(PresentationId id) {
        requireOwnerThread(); return state.byPresentation().get(Objects.requireNonNull(id, "presentationId"));
    }
    public Presentation<T> findPresentation(ViewId id) {
        requireOwnerThread();
        PresentationId presentation = state.byEditingView().get(Objects.requireNonNull(id, "viewId"));
        return presentation == null ? null : state.byPresentation().get(presentation);
    }
    public EditorDocumentSession document(DocumentId id) {
        requireOwnerThread(); return state.documents().get(Objects.requireNonNull(id, "documentId"));
    }

    public int indexOf(ViewId id) {
        requireOwnerThread();
        for (int index = 0; index < state.entries().size(); index++) if (state.entries().get(index).viewId().equals(id)) return index;
        return -1;
    }
    public int indexOf(PresentationId id) {
        requireOwnerThread();
        for (int index = 0; index < state.presentations().size(); index++) if (state.presentations().get(index).id().equals(id)) return index;
        return -1;
    }
    public void select(ViewId id) {
        Presentation<T> target = findPresentation(id);
        if (target == null) throw new IllegalArgumentException("Unknown workspace view");
        select(target.id());
    }
    public void select(PresentationId id) {
        if (find(id) == null) throw new IllegalArgumentException("Unknown workspace presentation");
        if (!id.equals(state.selection())) state = state.withSelection(id);
    }
    public void selectIndex(int index) { requireOwnerThread(); select(state.entries().get(index).viewId()); }
    public void selectPresentationIndex(int index) { requireOwnerThread(); select(state.presentations().get(index).id()); }

    /** Admits an editing view with independent presentation and group identities. */
    public void add(EditorDocumentSession document, ViewId viewId, T attachment) {
        requireOwnerThread();
        Presentation<T> added = editingPresentation(new Entry<>(document, viewId, attachment));
        insert(state.presentations().size(), added, true);
    }
    public void addAll(List<Entry<T>> additions) {
        requireOwnerThread();
        Objects.requireNonNull(additions, "additions");
        if (additions.isEmpty()) return;
        List<Presentation<T>> next = new ArrayList<>(state.presentations());
        for (Entry<T> entry : additions) next.add(editingPresentation(Objects.requireNonNull(entry, "entry")));
        replacePresentations(next, state.selection());
    }
    private Presentation<T> editingPresentation(Entry<T> entry) {
        return new Presentation<>(PresentationId.create(), PresentationGroupId.create(),
            new Editing(entry.document(), entry.viewId()), entry.attachment());
    }

    public void insert(int index, Presentation<T> presentation, boolean select) {
        requireOwnerThread();
        List<Presentation<T>> next = new ArrayList<>(state.presentations());
        next.add(index, Objects.requireNonNull(presentation, "presentation"));
        replacePresentations(next, select ? presentation.id() : state.selection());
    }
    public void updateAttachment(ViewId id, T attachment) {
        Presentation<T> target = findPresentation(id);
        if (target == null) throw new IllegalArgumentException("Unknown workspace view");
        updatePresentationAttachment(target.id(), attachment);
    }
    public void updatePresentationAttachment(PresentationId id, T attachment) {
        requireOwnerThread();
        int index = indexOf(id);
        if (index < 0) throw new IllegalArgumentException("Unknown workspace presentation");
        List<Presentation<T>> next = new ArrayList<>(state.presentations());
        next.set(index, next.get(index).withAttachment(attachment));
        replacePresentations(next, state.selection());
    }
    public Entry<T> remove(ViewId id) {
        Entry<T> removed = find(id);
        if (removed != null) removePresentation(findPresentation(id).id());
        return removed;
    }
    public Presentation<T> removePresentation(PresentationId id) {
        requireOwnerThread();
        Presentation<T> removed = find(id);
        if (removed == null) return null;
        int index = indexOf(id);
        List<Presentation<T>> next = new ArrayList<>(state.presentations());
        next.remove(index);
        PresentationId selected = state.selection();
        if (id.equals(selected)) selected = next.isEmpty() ? null : next.get(Math.min(index, next.size() - 1)).id();
        replacePresentations(next, selected);
        return removed;
    }

    /**
     * Atomically publishes prepared order, bindings, attachments and selection. A rewrite may
     * retire at most one editing view: backend retirement can allocate, so multi-backend
     * retirement requires a separate preparation contract. Any number of projections or
     * incoming editing views may be admitted. Group ordering is frontend policy.
     *
     * All validation/allocation precedes the one retirement. If it fails, no incoming document
     * has been claimed and workspace state is unchanged. Remaining ownership handshakes
     * are preflighted owner-thread operations with no allocation or provider calls.
     */
    public void replacePresentations(List<Presentation<T>> presentations, PresentationId selected) {
        requireOwnerThread();
        State<T> next = prepare(Objects.requireNonNull(presentations, "presentations"), selected);
        Entry<T> retired = null;
        for (Entry<T> previous : state.entries()) {
            Entry<T> replacement = next.byView().get(previous.viewId());
            if (replacement != null && replacement.document() != previous.document()) {
                throw new IllegalArgumentException("An editing view cannot switch document identity");
            }
            if (replacement == null) {
                if (retired != null) throw new IllegalArgumentException("A rewrite may retire at most one editing view");
                retired = previous;
            }
        }
        EditorDocumentSession[] incoming = next.documents().values().toArray(EditorDocumentSession[]::new);
        for (EditorDocumentSession document : incoming) {
            EditorDocumentSession previous = state.documents().get(document.documentId());
            if (previous != null && previous != document) throw new IllegalArgumentException("Duplicate document identity");
            EditorWorkspace<?> owner = document.workspace();
            if (owner != null && owner != this) throw new IllegalStateException("Document already belongs to another workspace");
        }
        boolean lastView = retired != null && !next.documents().containsKey(retired.documentId());
        if (lastView && retired.document().io().busy()) throw new IllegalStateException("Document is busy saving");
        if (retired != null) {
            retired.document().detachWorkspaceView(this, retired.viewId());
            if (lastView) retired.document().releaseWorkspace(this);
        }
        for (int index = 0; index < incoming.length; index++) incoming[index].claimWorkspace(this);
        state = next;
    }

    public boolean isOwnerThread() { return Thread.currentThread() == ownerThread; }
    private void requireOwnerThread() {
        if (!isOwnerThread()) throw new IllegalStateException("Workspace mutation requires its owner thread");
    }

    private State<T> prepare(List<Presentation<T>> presentations, PresentationId selected) {
        List<Presentation<T>> immutable = List.copyOf(presentations);
        Map<PresentationId, Presentation<T>> byPresentation = new HashMap<>();
        List<Entry<T>> entries = new ArrayList<>();
        Map<ViewId, Entry<T>> byView = new HashMap<>();
        Map<ViewId, PresentationId> byEditingView = new HashMap<>();
        Map<DocumentId, EditorDocumentSession> documents = new HashMap<>();
        for (Presentation<T> presentation : immutable) {
            if (byPresentation.putIfAbsent(presentation.id(), presentation) != null) throw new IllegalArgumentException("Duplicate presentation identity");
            if (!(presentation.binding() instanceof Editing editing)) continue;
            EditorDocumentSession document = editing.document();
            if (!document.isOwnerThread()) throw new IllegalStateException("Document belongs to another owner thread");
            if (!document.ownsView(editing.viewId())) throw new IllegalArgumentException("Unknown document view");
            Entry<T> entry = new Entry<>(document, editing.viewId(), presentation.attachment());
            if (byView.putIfAbsent(editing.viewId(), entry) != null) throw new IllegalArgumentException("Duplicate workspace view");
            byEditingView.put(editing.viewId(), presentation.id());
            entries.add(entry);
            EditorDocumentSession previous = documents.putIfAbsent(document.documentId(), document);
            if (previous != null && previous != document) throw new IllegalArgumentException("Duplicate document identity");
        }
        if (selected == null && !immutable.isEmpty()) selected = immutable.get(0).id();
        if (selected != null && !byPresentation.containsKey(selected)) throw new IllegalArgumentException("Selection must address a workspace presentation");
        return new State<>(immutable, Map.copyOf(byPresentation), List.copyOf(entries), Map.copyOf(byView),
            Map.copyOf(byEditingView), Map.copyOf(documents), selected);
    }

    private record State<T>(List<Presentation<T>> presentations, Map<PresentationId, Presentation<T>> byPresentation,
        List<Entry<T>> entries, Map<ViewId, Entry<T>> byView, Map<ViewId, PresentationId> byEditingView,
        Map<DocumentId, EditorDocumentSession> documents, PresentationId selection) {
        State<T> withSelection(PresentationId id) {
            return new State<>(presentations, byPresentation, entries, byView, byEditingView, documents, id);
        }
    }
}
