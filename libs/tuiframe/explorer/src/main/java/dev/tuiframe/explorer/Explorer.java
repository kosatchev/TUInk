package dev.tuiframe.explorer;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.explorer.file.ExplorerFileService;
import dev.tuiframe.explorer.file.ExplorerOperations;
import dev.tuiframe.explorer.file.ExplorerNavigation;
import dev.tuiframe.explorer.file.FileTreeModel;
import dev.tuiframe.explorer.ui.FileTreeView;
import dev.tuiframe.explorer.ui.PathPresentation;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalPlainText;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusRequest;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.PlacedPointerCapture;
import dev.tuiframe.widget.PointerCaptureCancellation;
import dev.tuiframe.widget.PointerCaptureRequest;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.TreeView;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;
import java.nio.file.Path;
import java.util.Objects;

/** Reusable filesystem explorer with instance permissions and retained gesture ownership. */
public final class Explorer implements ExplorerNavigation, AutoCloseable {
    public enum Access { READ_ONLY, MANAGE_FILES }

    private static final long HOLD_NANOS = 1_000_000_000L;
    private static final long EXPAND_NANOS = 700_000_000L;
    private static final long SCROLL_NANOS = 80_000_000L;
    private static final long NOTICE_NANOS = 3_000_000_000L;
    private final FileTreeModel model;
    private final Access access;
    private final ExplorerOperations operations;
    private FileTreeView.State state = FileTreeView.State.initial();
    private FileTreeView presentation;
    private FileTreeModel.Snapshot presentedSnapshot;
    private FileTreeView.Placement rawPlacement;
    private Path pendingSelection;
    private Rect allocation;
    private Rect interactionClip;
    private Gesture gesture;
    private FileTreeModel.EntryId lastClick;
    private long lastClickAt;
    private long doubleClickNanos = 450_000_000L;
    private Path hoveredFolder;
    private long hoveredSince;
    private long nextScrollAt;
    private Notice notice;
    private long currentNanos;

    public Explorer(Path root, boolean showSizeColumn, boolean showDateColumn) {
        this(root, showSizeColumn, showDateColumn, Access.READ_ONLY);
    }

    private Explorer(Path root, boolean showSizeColumn, boolean showDateColumn, Access access) {
        this(root, showSizeColumn, showDateColumn, access, new ExplorerFileService());
    }

    public Explorer(Path root, boolean showSizeColumn, boolean showDateColumn, Access access,
        ExplorerFileService files) {
        this.access = Objects.requireNonNull(access);
        model = new FileTreeModel(root, showSizeColumn, showDateColumn,
            access == Access.READ_ONLY, access == Access.MANAGE_FILES);
        operations = new ExplorerOperations(access, Objects.requireNonNull(files));
        operations.setProtectedRoot(model.root());
    }

    public static Explorer sidebar(Path root) { return new Explorer(root, false, false, Access.MANAGE_FILES); }
    public Access access() { return access; }
    public ExplorerOperations operations() { return operations; }
    public Path root() { return model.root(); }
    public boolean showHiddenFiles() { return model.showHiddenFiles(); }

    public void toggleHiddenFiles() {
        cancelPointerInteraction(PointerCaptureCancellation.SURFACE_CHANGED);
        model.setShowHiddenFiles(!model.showHiddenFiles());
    }

    public void setRoot(Path root) {
        cancelPointerInteraction(PointerCaptureCancellation.SURFACE_CHANGED);
        model.setRoot(Objects.requireNonNull(root));
        operations.setProtectedRoot(model.root());
        state = FileTreeView.State.initial();
        pendingSelection = null;
        invalidatePresentation();
    }

    public void moveToParentRoot() {
        Path parent = access == Access.MANAGE_FILES ? null : model.root().getParent();
        if (parent != null) setRoot(parent);
        else cancelPointerInteraction(PointerCaptureCancellation.SURFACE_CHANGED);
    }

    public void refresh() {
        cancelPointerInteraction(PointerCaptureCancellation.SURFACE_CHANGED);
        model.refresh();
        invalidatePresentation();
    }

    public void refreshDirectory(Path directory) {
        cancelPointerInteraction(PointerCaptureCancellation.SURFACE_CHANGED);
        model.refreshDirectory(directory);
        invalidatePresentation();
    }

    public FileTreeModel.Entry selectedEntry() { return findEntry(state.selectedId()); }
    public FileTreeModel.Snapshot snapshot() { return model.snapshot(); }

    public boolean reveal(Path path) {
        Path target = Objects.requireNonNull(path).toAbsolutePath().normalize();
        if (!model.reveal(target)) return false;
        pendingSelection = target;
        cancelPointerInteraction(PointerCaptureCancellation.SURFACE_CHANGED);
        invalidatePresentation();
        return true;
    }

    public boolean select(Path path) {
        Path target = Objects.requireNonNull(path).toAbsolutePath().normalize();
        var snapshot = model.snapshot();
        for (int index = 0; index < snapshot.entries().size(); index++) {
            var entry = snapshot.entries().get(index);
            if (entry.path().equals(target) && entry.kind() != FileTreeModel.Kind.ERROR) {
                state = new FileTreeView.State(entry.id(), new TreeView.State(index, index, 0),
                    snapshot.revision(), null, 0L);
                return true;
            }
        }
        return false;
    }

    public void requestExpansion(FileTreeModel.EntryId id, boolean expand) {
        cancelPointerInteraction(PointerCaptureCancellation.SURFACE_CHANGED);
        expand(id, expand);
    }

    private void expand(FileTreeModel.EntryId id, boolean expand) {
        var entry = findEntry(Objects.requireNonNull(id));
        if (entry != null && entry.kind() == FileTreeModel.Kind.DIRECTORY && model.setDirectoryExpanded(id, expand))
            invalidatePresentation();
    }

    public boolean consumeMetadataChanged() { return model.consumeMetadataChanged(); }
    public boolean loading() { return model.snapshot().loading(); }
    public FileTreeModel.DirectoryFailure directoryFailure() {
        var failures = model.snapshot().failures();
        return failures.isEmpty() ? null : failures.getFirst();
    }

    public FileTreeView.Placement place(Rect bounds, Rect clip, boolean focused, boolean visible) {
        Objects.requireNonNull(bounds); Objects.requireNonNull(clip);
        if (!visible || allocation != null && (!allocation.equals(bounds) || !interactionClip.equals(clip)))
            cancelPointerInteraction(visible ? PointerCaptureCancellation.GEOMETRY_CHANGED : PointerCaptureCancellation.HIDDEN);
        allocation = bounds;
        interactionClip = clip;
        var snapshot = model.snapshot();
        if (pendingSelection != null && select(pendingSelection)) pendingSelection = null;
        if (pendingSelection != null && !snapshot.loading()) pendingSelection = null;
        if (presentation == null || presentedSnapshot != snapshot) {
            presentation = FileTreeView.fromSnapshot(snapshot, model.showSizeColumn(), model.showDateColumn(),
                access == Access.MANAGE_FILES ? FileTreeView.DirectoryActivation.TOGGLE_EXPANSION
                    : FileTreeView.DirectoryActivation.NAVIGATE);
            presentedSnapshot = snapshot;
        }
        int footer = visible && notice != null && bounds.height() > 0 ? 1 : 0;
        Rect treeBounds = new Rect(bounds.x(), bounds.y(), bounds.width(), Math.max(0, bounds.height() - footer));
        rawPlacement = presentation.place(treeBounds, state, clip, focused, visible);
        state = rawPlacement.state();
        if (!rawPlacement.enabled()) cancelPointerInteraction(PointerCaptureCancellation.DISABLED);
        if (gesture != null && findEntry(gesture.source.id()) == null)
            cancelPointerInteraction(PointerCaptureCancellation.SURFACE_CHANGED);
        if (access == Access.READ_ONLY || !visible) return rawPlacement;
        updateHover(currentNanos);
        return rawPlacement.withInteraction(bounds, new ExplorerInteraction(rawPlacement, bounds));
    }

    public void adopt(FileTreeView.State nextState) {
        state = Objects.requireNonNull(nextState);
        pendingSelection = null;
    }
    public FileTreeView.State state() { return state; }
    public boolean dragging() { return gesture != null && gesture.dragging; }
    public boolean pointerCaptureActive() {
        return gesture != null || state.widgetState().drag() != null;
    }

    /** Routes both ordinary and captured events through the same explorer gesture owner. */
    public InteractionResult<FileTreeView.Action> handle(TuiInputEvent event, long nowNanos, long doubleClickWindowNanos) {
        Objects.requireNonNull(event);
        if (doubleClickWindowNanos < 0) throw new IllegalArgumentException("negative double-click interval");
        doubleClickNanos = doubleClickWindowNanos;
        currentNanos = nowNanos;
        if (rawPlacement == null || !rawPlacement.enabled()) return InteractionResult.ignored();
        if (access == Access.READ_ONLY) return adoptResult(rawPlacement.handle(event, nowNanos, doubleClickWindowNanos));
        if (event instanceof ResizeEvent) {
            cancelPointerInteraction(PointerCaptureCancellation.RESIZE);
            return InteractionResult.<FileTreeView.Action>handled().withPointerCapture(PointerCaptureRequest.RELEASE);
        }
        if (!(event instanceof PointerEvent pointer)) {
            boolean captured = gesture != null;
            cancelPointerInteraction(PointerCaptureCancellation.KEYBOARD_INPUT);
            if (captured && event instanceof KeyStroke key && key.key() == KeyStroke.Key.ESCAPE)
                return InteractionResult.<FileTreeView.Action>handled().withPointerCapture(PointerCaptureRequest.RELEASE);
            return adoptResult(rawPlacement.handle(event, nowNanos, doubleClickWindowNanos));
        }
        if (gesture != null) {
            if (pointer.kind() == PointerEvent.Kind.RELEASE) return release(pointer, nowNanos);
            if (pointer.kind() == PointerEvent.Kind.DRAG || pointer.kind() == PointerEvent.Kind.MOVE) {
                if (pointer.button() != PointerEvent.Button.LEFT) return InteractionResult.handled();
                gesture.row = pointer.row(); gesture.column = pointer.column();
                arm(nowNanos);
                updateHover(nowNanos);
                return InteractionResult.<FileTreeView.Action>handled().withPointerCapture(PointerCaptureRequest.ACQUIRE);
            }
            if (pointer.kind() == PointerEvent.Kind.SCROLL_UP || pointer.kind() == PointerEvent.Kind.SCROLL_DOWN) {
                scroll(pointer.kind() == PointerEvent.Kind.SCROLL_UP ? -1 : 1);
                hoveredFolder = null;
                return changed(PointerCaptureRequest.ACQUIRE);
            }
            if (pointer.kind() == PointerEvent.Kind.PRESS) cancelPointerInteraction(PointerCaptureCancellation.NEW_PRESS);
            else return InteractionResult.handled();
        }
        var result = adoptResult(rawPlacement.handle(event, nowNanos, 0L));
        if (pointer.kind() != PointerEvent.Kind.PRESS || pointer.button() != PointerEvent.Button.LEFT
            || !pointer.modifiers().isEmpty() || result.output().orElse(null) instanceof FileTreeView.ToggleRequested)
            return result;
        var entry = entryAt(pointer.row(), pointer.column());
        if (entry == null || entry.kind() == FileTreeModel.Kind.ERROR || state.widgetState().drag() != null) return result;
        gesture = new Gesture(entry, pointer.row(), pointer.column(), nowNanos);
        // FileTreeView's press tracker is deliberately replaced by completed short clicks.
        state = state.resetClickTracking();
        return changed(PointerCaptureRequest.ACQUIRE);
    }

    private InteractionResult<FileTreeView.Action> release(PointerEvent pointer, long now) {
        var completed = gesture;
        completed.row = pointer.row(); completed.column = pointer.column();
        arm(now);
        updateHover(now);
        var destination = completed.dragging ? validDropTarget() : null;
        var clicked = entryAt(pointer.row(), pointer.column());
        gesture = null; hoveredFolder = null; nextScrollAt = 0L;
        state = state.cancelPointerInteraction();
        if (completed.dragging) {
            lastClick = null;
            if (destination != null) return InteractionResult.<FileTreeView.Action>output(new FileTreeView.MoveRequested(
                completed.source.path(), destination.path(), completed.source.target(), state))
                .withPointerCapture(PointerCaptureRequest.RELEASE);
            return changed(PointerCaptureRequest.RELEASE);
        }
        if (clicked != null && clicked.id().equals(completed.source.id()) && now - completed.pressedAt < HOLD_NANOS) {
            boolean twice = clicked.id().equals(lastClick) && now - lastClickAt >= 0L && now - lastClickAt <= doubleClickNanos;
            lastClick = twice ? null : clicked.id(); lastClickAt = now;
            FileTreeView.Action action = new FileTreeView.StateChanged(state);
            if (twice) {
                if (clicked.kind() == FileTreeModel.Kind.FILE) action = new FileTreeView.FileRequested(clicked.path(), state);
                else if (clicked.kind() == FileTreeModel.Kind.DIRECTORY)
                    action = new FileTreeView.ToggleRequested(clicked.id(), !clicked.expanded(), state);
            }
            return InteractionResult.output(action).withPointerCapture(PointerCaptureRequest.RELEASE);
        }
        lastClick = null;
        return changed(PointerCaptureRequest.RELEASE);
    }

    private boolean arm(long now) {
        if (gesture == null || gesture.armed || now - gesture.pressedAt < HOLD_NANOS) return false;
        gesture.armed = true;
        return true;
    }

    private void updateHover(long now) {
        if (gesture == null || rawPlacement == null) return;
        if (gesture.armed && gesture.source.target() != null && !gesture.source.path().equals(root())
            && (gesture.row != gesture.startRow || gesture.column != gesture.startColumn)) gesture.dragging = true;
        var destination = gesture.dragging ? validDropTarget() : null;
        Path next = destination == null ? null : destination.path();
        if (!Objects.equals(next, hoveredFolder)) { hoveredFolder = next; hoveredSince = now; }
        if (edgeDirection() == 0) nextScrollAt = 0L;
        else if (nextScrollAt == 0L) nextScrollAt = now + SCROLL_NANOS;
    }

    public boolean tick(long now) {
        currentNanos = now;
        boolean changed = false;
        if (notice != null && notice.expiresAt != 0L && now - notice.expiresAt >= 0L) { notice = null; changed = true; }
        changed |= arm(now);
        updateHover(now);
        if (gesture != null && gesture.dragging) {
            var destination = validDropTarget();
            if (destination != null && !destination.expanded() && !loading() && now - hoveredSince >= EXPAND_NANOS) {
                expand(destination.id(), true);
                changed = true;
            }
            int direction = edgeDirection();
            if (direction != 0 && nextScrollAt != 0L && now - nextScrollAt >= 0L) {
                changed |= scroll(direction);
                nextScrollAt = now + SCROLL_NANOS;
            }
        }
        return changed;
    }

    public long nanosUntilWakeup(long now) {
        long next = notice == null || notice.expiresAt == 0L ? -1L : Math.max(0L, notice.expiresAt - now);
        if (gesture == null) return next;
        if (!gesture.armed) next = earlier(next, Math.max(0L, gesture.pressedAt + HOLD_NANOS - now));
        if (gesture.dragging) {
            var destination = validDropTarget();
            if (destination != null && !destination.expanded() && !loading())
                next = earlier(next, Math.max(0L, hoveredSince + EXPAND_NANOS - now));
            if (nextScrollAt != 0L) next = earlier(next, Math.max(0L, nextScrollAt - now));
        }
        return next;
    }

    public void cancelPointerInteraction(PointerCaptureCancellation reason) {
        Objects.requireNonNull(reason);
        gesture = null; hoveredFolder = null; nextScrollAt = 0L; lastClick = null;
        state = state.cancelPointerInteraction();
    }

    /** Accepts a worker receipt on the UI owner, independently of the active document presentation. */
    public void acceptOperation(ExplorerFileService.Result result, String historyAction,
        boolean showHistoryNotices, long now) {
        Objects.requireNonNull(result);
        cancelPointerInteraction(PointerCaptureCancellation.SURFACE_CHANGED);
        if (!(result.action() == ExplorerFileService.Action.RENAME && result.succeeded() && !result.destinationPublished())) {
            if (result.action() != ExplorerFileService.Action.COPY && result.source() != null && result.source().getParent() != null)
                refreshDirectory(result.source().getParent());
            if (result.destination() != null && result.destination().getParent() != null
                && (result.action() == ExplorerFileService.Action.COPY || result.source() == null
                    || !result.destination().getParent().equals(result.source().getParent())))
                refreshDirectory(result.destination().getParent());
            if (result.succeeded() && result.destination() != null && result.destination().startsWith(root())) {
                if (result.destination().getFileName().toString().startsWith(".") && !showHiddenFiles()) toggleHiddenFiles();
                reveal(result.destination());
            }
        }
        if (!result.succeeded()) {
            String message = result.failure() == null ? "File operation failed" : result.failure().getMessage();
            notice = new Notice(TerminalPlainText.singleLine(message), true, 0L, false);
        } else if (result.action() == ExplorerFileService.Action.COPY && !operations.canUndo() && operations.historyBarrier() != null) {
            notice = new Notice(TerminalPlainText.singleLine("Copied; undo unavailable: " + operations.historyBarrier()), false, 0L, false);
        } else if (historyAction != null && showHistoryNotices) {
            notice = new Notice(TerminalPlainText.singleLine(historyAction), false, now + NOTICE_NANOS, true);
        } else notice = null;
    }

    public String noticeText() { return notice == null ? null : notice.text; }
    public boolean noticeIsError() { return notice != null && notice.error; }
    public void clearHistoryNotice() { if (notice != null && notice.history) notice = null; }

    private InteractionResult<FileTreeView.Action> changed(PointerCaptureRequest capture) {
        return InteractionResult.<FileTreeView.Action>output(new FileTreeView.StateChanged(state)).withPointerCapture(capture);
    }

    private InteractionResult<FileTreeView.Action> adoptResult(InteractionResult<FileTreeView.Action> result) {
        result.output().ifPresent(action -> adopt(action.state()));
        return result;
    }

    private FileTreeModel.Entry entryAt(int row, int column) {
        if (rawPlacement == null || !rawPlacement.contentBounds().contains(column, row)) return null;
        int index = state.widgetState().firstVisibleIndex() + row - rawPlacement.contentBounds().y();
        var entries = model.snapshot().entries();
        return index < 0 || index >= entries.size() ? null : entries.get(index);
    }

    private FileTreeModel.Entry validDropTarget() {
        if (gesture == null) return null;
        var entry = entryAt(gesture.row, gesture.column);
        if (entry == null || entry.kind() != FileTreeModel.Kind.DIRECTORY) return null;
        Path source = gesture.source.path(), destination = entry.path();
        if (destination.equals(source) || destination.equals(source.getParent())
            || gesture.source.kind() == FileTreeModel.Kind.DIRECTORY && destination.startsWith(source)) return null;
        return entry;
    }

    private int edgeDirection() {
        if (gesture == null || !gesture.dragging || rawPlacement == null
            || gesture.column < rawPlacement.bounds().x() || gesture.column >= rawPlacement.bounds().rightExclusive()) return 0;
        Rect content = rawPlacement.contentBounds();
        if (content.height() == 0) return 0;
        if (gesture.row <= content.y()) return -1;
        return gesture.row >= content.bottomExclusive() - 1 ? 1 : 0;
    }

    private boolean scroll(int direction) {
        var widget = state.widgetState();
        int maximum = Math.max(0, model.snapshot().entries().size() - rawPlacement.contentBounds().height());
        int next = Math.max(0, Math.min(maximum, widget.firstVisibleIndex() + direction));
        if (next == widget.firstVisibleIndex()) return false;
        state = new FileTreeView.State(state.selectedId(), new TreeView.State(widget.selectedIndex(), next,
            widget.horizontalOffset()), model.snapshot().revision(), null, 0L);
        return true;
    }

    private FileTreeModel.Entry findEntry(FileTreeModel.EntryId id) {
        if (id == null) return null;
        for (var entry : model.snapshot().entries()) if (entry.id().equals(id)) return entry;
        return null;
    }

    private void invalidatePresentation() { presentation = null; presentedSnapshot = null; }
    private static long earlier(long left, long right) { return left < 0L ? right : right < 0L ? left : Math.min(left, right); }

    @Override public void close() {
        cancelPointerInteraction(PointerCaptureCancellation.HIDDEN);
        model.close(); presentation = null; presentedSnapshot = null; rawPlacement = null; pendingSelection = null;
    }

    private record Notice(String text, boolean error, long expiresAt, boolean history) {}

    private static final class Gesture {
        final FileTreeModel.Entry source;
        final int startRow, startColumn;
        final long pressedAt;
        int row, column;
        boolean armed, dragging;
        Gesture(FileTreeModel.Entry source, int row, int column, long now) {
            this.source = source; startRow = this.row = row; startColumn = this.column = column; pressedAt = now;
        }
    }

    private final class ExplorerInteraction implements FileTreeView.Interaction {
        private final FileTreeView.Placement base;
        private final Rect bounds;
        ExplorerInteraction(FileTreeView.Placement base, Rect bounds) { this.base = base; this.bounds = bounds; }
        @Override public InteractionResult<FileTreeView.Action> handle(TuiInputEvent event, long now, long doubleClickWindow) {
            return Explorer.this.handle(event, now, doubleClickWindow);
        }
        @Override public PlacedPointerCapture<FileTreeView.Action> captureTarget() {
            return new PlacedPointerCapture<>() {
                @Override public Rect bounds() { return bounds; }
                @Override public boolean interactionReady() { return base.enabled(); }
                @Override public void paint(Canvas canvas, Theme theme, WidgetState state) { base.paint(canvas, theme); ExplorerInteraction.this.paint(canvas, theme); }
                @Override public boolean pointerCaptureActive() { return Explorer.this.pointerCaptureActive(); }
                @Override public InteractionResult<FileTreeView.Action> cancelPointerCapture(PointerCaptureCancellation reason) {
                    cancelPointerInteraction(reason);
                    return changed(PointerCaptureRequest.RELEASE);
                }
                @Override public InteractionResult<FileTreeView.Action> handle(TuiInputEvent event, FocusContext context) {
                    return Explorer.this.handle(event, System.nanoTime(), doubleClickNanos);
                }
            };
        }
        @Override public void paint(Canvas canvas, Theme theme) {
            int mark = canvas.save();
            try {
                canvas.clip(bounds);
                if (notice != null && bounds.height() > 0) {
                    CellStyle style = theme.resolveStyle(notice.error ? ExplorerStyleKeys.ERROR : ExplorerStyleKeys.WARNING,
                        theme.resolveStyle(WidgetThemeKeys.MODAL_BODY));
                    Rect footer = new Rect(bounds.x(), bounds.bottomExclusive() - 1, bounds.width(), 1);
                    canvas.fill(footer, style);
                    CellText.drawClipped(canvas, footer.x(), footer.y(), notice.text, style, footer.width());
                }
                if (!dragging()) return;
                var destination = validDropTarget();
                if (destination != null) {
                    int index = model.snapshot().entries().indexOf(destination);
                    int row = base.contentBounds().y() + index - state.widgetState().firstVisibleIndex();
                    Rect highlight = new Rect(base.contentBounds().x(), row, base.contentBounds().width(), 1);
                    int targetMark = canvas.save();
                    try {
                        canvas.clip(highlight);
                        var selected = new FileTreeView.State(destination.id(), new TreeView.State(index,
                            state.widgetState().firstVisibleIndex(), state.widgetState().horizontalOffset()),
                            model.snapshot().revision(), null, 0L);
                        var target = presentation.place(base.bounds(), selected, interactionClip, true, true);
                        CellStyle targetStyle = theme.resolveStyle(ExplorerStyleKeys.DROP_TARGET,
                            theme.resolveStyle(WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED));
                        target.paint(canvas, role -> WidgetThemeKeys.LIST_ITEM_SELECTED.equals(role)
                            || WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED.equals(role) ? targetStyle : theme.style(role));
                    } finally { canvas.restore(targetMark); }
                }
                Rect content = base.contentBounds();
                if (content.width() > 0 && content.height() > 0) {
                    int row = gesture.row + 1 < content.bottomExclusive() ? Math.max(content.y(), gesture.row + 1)
                        : Math.max(content.y(), Math.min(content.bottomExclusive() - 1, gesture.row - 1));
                    int column = Math.max(content.x(), Math.min(content.rightExclusive() - 1, gesture.column + 1));
                    String label = "→ " + PathPresentation.name(gesture.source.path());
                    CellStyle style = theme.resolveStyle(ExplorerStyleKeys.DRAG_LABEL,
                        theme.resolveStyle(WidgetThemeKeys.MODAL_BORDER));
                    CellText.drawClipped(canvas, column, row, label, style, content.rightExclusive() - column);
                }
            } finally { canvas.restore(mark); }
        }
    }
}
