package dev.tuink.tui;

import dev.tuiframe.geometry.Rect;
import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.ui.FileTreeView;
import java.nio.file.Path;
import java.util.Objects;

/** Owns the open-file model, stable view state, and revision-cached presentation. */
final class OpenFileTreeController implements AutoCloseable {
    private final FileTreeModel model;
    private FileTreeView.State state = FileTreeView.State.initial();
    private FileTreeView presentation;
    private FileTreeModel.Snapshot presentedSnapshot;

    OpenFileTreeController(Path root, boolean showSizeColumn, boolean showDateColumn) {
        model = new FileTreeModel(root, showSizeColumn, showDateColumn);
    }

    Path root() {
        return model.root();
    }

    boolean showHiddenFiles() {
        return model.showHiddenFiles();
    }

    void toggleHiddenFiles() {
        state = state.cancelPointerInteraction();
        model.setShowHiddenFiles(!model.showHiddenFiles());
    }

    void setRoot(Path root) {
        model.setRoot(Objects.requireNonNull(root, "root"));
        state = FileTreeView.State.initial();
        invalidatePresentation();
    }

    void moveToParentRoot() {
        Path parent = model.root().getParent();
        if (parent != null) {
            setRoot(parent);
        } else {
            state = state.cancelPointerInteraction();
        }
    }

    void requestExpansion(FileTreeModel.EntryId id, boolean expand) {
        Objects.requireNonNull(id, "id");
        state = state.cancelPointerInteraction();
        FileTreeModel.Entry entry = findEntry(id);
        if (entry == null || entry.kind() != FileTreeModel.Kind.DIRECTORY) {
            return;
        }
        if (model.setDirectoryExpanded(id, expand)) {
            invalidatePresentation();
        }
    }

    boolean consumeMetadataChanged() {
        return model.consumeMetadataChanged();
    }

    boolean loading() {
        return model.snapshot().loading();
    }

    FileTreeModel.DirectoryFailure directoryFailure() {
        var failures = model.snapshot().failures();
        return failures.isEmpty() ? null : failures.getFirst();
    }

    FileTreeView.Placement place(
        Rect bounds,
        Rect interactionClip,
        boolean focused,
        boolean visible
    ) {
        FileTreeModel.Snapshot snapshot = model.snapshot();
        if (presentation == null || presentedSnapshot != snapshot) {
            presentation = FileTreeView.fromSnapshot(
                snapshot,
                model.showSizeColumn(),
                model.showDateColumn()
            );
            presentedSnapshot = snapshot;
        }
        FileTreeView.Placement placement = presentation.place(
            Objects.requireNonNull(bounds, "bounds"),
            state,
            Objects.requireNonNull(interactionClip, "interactionClip"),
            focused,
            visible
        );
        state = placement.state();
        return placement;
    }

    void adopt(FileTreeView.State nextState) {
        state = Objects.requireNonNull(nextState, "nextState");
    }

    FileTreeView.State state() {
        return state;
    }

    @Override
    public void close() {
        model.close();
        presentation = null;
        presentedSnapshot = null;
    }

    private FileTreeModel.Entry findEntry(FileTreeModel.EntryId id) {
        for (FileTreeModel.Entry entry : model.snapshot().entries()) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private void invalidatePresentation() {
        presentation = null;
        presentedSnapshot = null;
    }
}
