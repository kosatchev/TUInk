package dev.tuink.core;

import dev.tuink.editor.diff.LineDiff;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Owns one mutable document, file state and history. Views attach their own positions and layout. */
public final class EditorDocument {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private final Thread ownerThread = Thread.currentThread();
    private final TextModel model;
    final EditorDocumentState state;
    final EditorDocumentHistory history = new EditorDocumentHistory();
    final Baselines baselines = new Baselines();
    private final List<WeakReference<EditorState>> views = new ArrayList<>();
    private boolean initialized;
    long recoveryGeneration;

    public EditorDocument(TextModel model) {
        this(model, new EditorDocumentState());
    }

    EditorDocument(TextModel model, EditorDocumentState state) {
        this.model = Objects.requireNonNull(model, "model");
        this.state = Objects.requireNonNull(state, "state");
        if (state.documentOwnerId != 0) {
            throw new IllegalArgumentException("an editor state already belongs to another document; attach a new view instead");
        }
        model.claimDocument(this);
        state.documentOwnerId = NEXT_ID.incrementAndGet();
        reflectFileMetadata();
    }

    public TextModel model() {
        return model;
    }

    /** Attaching a view never creates history or reinitializes file/savepoint baselines. */
    public TabSession attach(EditorTextLayout.Factory layoutFactory, EditorClipboard clipboard) {
        return attach(layoutFactory, clipboard, LineDiff.none());
    }

    /** Optional frontend services are view-local and never change the shared document/history. */
    public TabSession attach(EditorTextLayout.Factory layoutFactory, EditorClipboard clipboard, LineDiff lineDiff) {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Editor document views must attach on the document owner thread");
        }
        Objects.requireNonNull(layoutFactory, "layoutFactory");
        Objects.requireNonNull(clipboard, "clipboard");
        Objects.requireNonNull(lineDiff, "lineDiff");
        EditorState viewState = new EditorState(state, new EditorViewState());
        EditorTextLayout layout = layoutFactory.create(model);
        EditorEngine engine = new EditorEngine(this, viewState, layout, clipboard, lineDiff);
        return new TabSession(model, viewState, layout, engine);
    }

    boolean register(EditorState viewState) {
        if (viewState.documentState() != state) {
            throw new IllegalArgumentException("view must share its document state");
        }
        views.removeIf(reference -> reference.get() == null);
        views.add(new WeakReference<>(viewState));
        boolean first = !initialized;
        initialized = true;
        return first;
    }

    void reflectFileMetadata() {
        state.fileEncoding = model.fileMetadata().charset();
        state.lineSeparator = model.fileMetadata().defaultLineSeparator();
    }

    /** Other views retain their position; document changes only clamp invalid UTF-16 positions. */
    void changed() {
        recoveryGeneration++;
        reflectFileMetadata();
        views.removeIf(reference -> reference.get() == null);
        for (WeakReference<EditorState> reference : views) {
            EditorState view = reference.get();
            if (view == null) {
                continue;
            }
            Position cursor = clamp(view.getCursor());
            Position anchor = view.getSelectionAnchor() == null ? null : clamp(view.getSelectionAnchor());
            if (!cursor.equals(view.getCursor()) || !Objects.equals(anchor, view.getSelectionAnchor())) {
                view.setCursor(cursor);
                view.setSelectionAnchor(anchor);
                view.invalidateNavigation();
            }
        }
    }

    /** Allocates every other view's clamp result while a local command is still isolated. */
    PreparedChange prepareChange() {
        views.removeIf(reference -> reference.get() == null);
        List<ViewChange> changes = new ArrayList<>();
        for (int index = 0; index < views.size(); index++) {
            EditorState view = views.get(index).get();
            if (view == null) continue;
            Position cursor = clamp(view.getCursor());
            Position anchor = view.getSelectionAnchor() == null ? null : clamp(view.getSelectionAnchor());
            if (!cursor.equals(view.getCursor()) || !Objects.equals(anchor, view.getSelectionAnchor())) {
                changes.add(new ViewChange(view, cursor, anchor));
            }
        }
        return new PreparedChange(changes);
    }

    void publishChange(PreparedChange prepared) {
        recoveryGeneration++;
        reflectFileMetadata();
        for (int index = 0; index < prepared.views().size(); index++) {
            ViewChange change = prepared.views().get(index);
            change.view().setCursor(change.cursor());
            change.view().setSelectionAnchor(change.anchor());
            change.view().invalidateNavigation();
        }
    }

    record PreparedChange(List<ViewChange> views) {}
    private record ViewChange(EditorState view, Position cursor, Position anchor) {}

    private Position clamp(Position position) {
        Position bounded = model.clamp(position);
        String text = model.getLine(bounded.line());
        int column = bounded.col();
        if (column > 0 && column < text.length() && Character.isHighSurrogate(text.charAt(column - 1))
            && Character.isLowSurrogate(text.charAt(column))) {
            column--;
        }
        return new Position(bounded.line(), column);
    }

    static final class Baselines {
        List<? extends CharSequence> openLines = List.of("");
        List<? extends CharSequence> savedLines = List.of("");
        long generation;
    }
}
