package dev.tuink.core;

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
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Editor document views must attach on the document owner thread");
        }
        Objects.requireNonNull(layoutFactory, "layoutFactory");
        EditorState viewState = new EditorState(state, new EditorViewState());
        EditorTextLayout layout = layoutFactory.create(model);
        EditorEngine engine = new EditorEngine(this, viewState, layout, clipboard);
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
        List<String> openLines = List.of("");
        List<String> savedLines = List.of("");
        long generation;
    }
}
