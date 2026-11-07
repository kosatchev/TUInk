package dev.tuiframe.explorer.ui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.scroll.ScrollLayout;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.InteractionVisibility;
import dev.tuiframe.widget.PlacedPointerCapture;
import dev.tuiframe.widget.PointerCaptureCancellation;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.explorer.file.FileTreeModel;
import dev.tuiframe.explorer.file.ExplorerFileService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reusable presentation and policy adapter over the reusable flat tree widget.
 *
 * <p>One instance projects one immutable {@link FileTreeModel.Snapshot}. The
 * projection contains no filesystem traversal and no retained hierarchy. Its
 * prepared generic descriptors are reused while selection, focus and viewport
 * state change; a new adapter is built only for a new model revision or column
 * policy.</p>
 */
public final class FileTreeView {
    /** File-picker navigation and fixed-root explorer activation share the same tree widget. */
    public enum DirectoryActivation { NAVIGATE, TOGGLE_EXPANSION }

    private static final int MIN_LABEL_WIDTH_WITH_METADATA = 12;
    private static final int DEFAULT_SIZE_COLUMN_WIDTH = 11;
    private static final int MIN_SIZE_COLUMN_WIDTH = 7;
    private static final int DATE_COLUMN_WIDTH = 14;
    private static final int TREE_INDENT_WIDTH = 2;
    private static final int TREE_DISCLOSURE_WIDTH = 2;

    private final FileTreeModel.Snapshot snapshot;
    private final List<FileTreeModel.Entry> entries;
    private final Map<FileTreeModel.EntryId, Integer> indexById;
    private final boolean showSizeColumn;
    private final boolean showDateColumn;
    private final DirectoryActivation directoryActivation;
    private final int leadingExtent;
    private final boolean hasMetadataRows;

    // UI-thread-confined presentation caches. They contain no mutable widget state.
    private final Map<MetadataColumns, dev.tuiframe.widget.TreeView<FileTreeModel.EntryId>>
        treesByColumns = new HashMap<>();
    private final Map<BoundsSize, MetadataColumns> columnsByBounds =
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<BoundsSize, MetadataColumns> eldest) {
                return size() > 16;
            }
        };

    private FileTreeView(
        FileTreeModel.Snapshot snapshot,
        boolean showSizeColumn,
        boolean showDateColumn,
        DirectoryActivation directoryActivation
    ) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.directoryActivation = Objects.requireNonNull(directoryActivation, "directoryActivation");
        this.entries = snapshot.entries();
        HashMap<FileTreeModel.EntryId, Integer> indexes = new HashMap<>(entries.size());
        int widestLeading = 0;
        boolean metadataRows = false;
        for (int index = 0; index < entries.size(); index++) {
            FileTreeModel.Entry entry = entries.get(index);
            indexes.put(entry.id(), index);
            long prefix = (long) widgetDepth(entry) * TREE_INDENT_WIDTH
                + (widgetKind(entry) == dev.tuiframe.widget.TreeView.Kind.PLAIN
                    ? 0L
                    : TREE_DISCLOSURE_WIDTH);
            long width = prefix + CellText.width(displayLabel(entry));
            widestLeading = (int) Math.max(
                widestLeading,
                Math.min((long) Integer.MAX_VALUE, width)
            );
            metadataRows = metadataRows
                || entry.kind() == FileTreeModel.Kind.FILE
                || entry.kind() == FileTreeModel.Kind.DIRECTORY;
        }
        this.indexById = Map.copyOf(indexes);
        this.showSizeColumn = showSizeColumn;
        this.showDateColumn = showDateColumn;
        this.leadingExtent = widestLeading;
        this.hasMetadataRows = metadataRows;
    }

    public static FileTreeView fromSnapshot(
        FileTreeModel.Snapshot snapshot,
        boolean showSizeColumn,
        boolean showDateColumn
    ) {
        return fromSnapshot(snapshot, showSizeColumn, showDateColumn, DirectoryActivation.NAVIGATE);
    }

    public static FileTreeView fromSnapshot(
        FileTreeModel.Snapshot snapshot,
        boolean showSizeColumn,
        boolean showDateColumn,
        DirectoryActivation directoryActivation
    ) {
        return new FileTreeView(snapshot, showSizeColumn, showDateColumn, directoryActivation);
    }

    public FileTreeModel.Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Creates the exact placement shared by paint, hit routing, cursor and
     * scrollbar capture for one frame.
     */
    public Placement place(
        Rect bounds,
        State state,
        Rect interactionClip,
        boolean focused,
        boolean visible
    ) {
        Rect requiredBounds = Objects.requireNonNull(bounds, "bounds");
        State reconciled = reconcile(Objects.requireNonNull(state, "state"));
        Rect requiredClip = Objects.requireNonNull(interactionClip, "interactionClip");
        Rect widgetBounds = visible
            ? requiredBounds
            : new Rect(requiredBounds.x(), requiredBounds.y(), 0, 0);
        MetadataColumns columns = visible
            ? metadataColumnsFor(widgetBounds)
            : MetadataColumns.NONE;
        dev.tuiframe.widget.TreeView<FileTreeModel.EntryId> tree = treeFor(columns);
        dev.tuiframe.widget.TreeView.Placement<FileTreeModel.EntryId> placed =
            tree.layout(widgetBounds, reconciled.widgetState());
        State normalized = visible ? adopt(placed.state(), reconciled, false) : reconciled;
        boolean enabled = visible
            && InteractionVisibility.FULL_BOUNDS.allows(placed.bounds(), requiredClip);
        if (!enabled) {
            normalized = normalized.cancelPointerInteraction();
        }
        FocusContext context = new FocusContext(
            focused && enabled,
            enabled,
            FocusSpace.root(requiredClip)
        );
        return new Placement(placed, context, normalized, columns);
    }

    public Placement place(
        Rect bounds,
        State state,
        Rect interactionClip,
        boolean focused
    ) {
        return place(bounds, state, interactionClip, focused, true);
    }

    private State reconcile(State state) {
        int selectedIndex = findIndex(state.selectedId());
        if (selectedIndex < 0) {
            selectedIndex = state.widgetState().selectedIndex();
        }
        if (entries.isEmpty()) {
            selectedIndex = -1;
        } else {
            selectedIndex = Math.max(0, Math.min(entries.size() - 1, selectedIndex));
        }
        dev.tuiframe.widget.TreeView.State widgetState =
            new dev.tuiframe.widget.TreeView.State(
                selectedIndex,
                state.widgetState().firstVisibleIndex(),
                state.widgetState().horizontalOffset(),
                state.widgetState().drag()
            );
        boolean keepClick = state.lastPressedId() != null
            && findIndex(state.lastPressedId()) >= 0;
        return new State(
            idAt(selectedIndex),
            widgetState,
            snapshot.revision(),
            keepClick ? state.lastPressedId() : null,
            keepClick ? state.lastPressedAtNanos() : 0L
        );
    }

    private State adopt(
        dev.tuiframe.widget.TreeView.State widgetState,
        State previous,
        boolean resetClick
    ) {
        FileTreeModel.EntryId selectedId = idAt(widgetState.selectedIndex());
        return new State(
            selectedId,
            widgetState,
            snapshot.revision(),
            resetClick ? null : previous.lastPressedId(),
            resetClick ? 0L : previous.lastPressedAtNanos()
        );
    }

    private MetadataColumns metadataColumnsFor(Rect bounds) {
        if (!hasMetadataRows) {
            return MetadataColumns.NONE;
        }
        BoundsSize key = new BoundsSize(bounds.width(), bounds.height());
        MetadataColumns cached = columnsByBounds.get(key);
        if (cached != null) {
            return cached;
        }
        MetadataColumns selected = MetadataColumns.NONE;
        for (MetadataColumns candidate : metadataProfiles()) {
            if (candidate.trailingWidth() == 0
                || resolvedLeadingWidth(bounds, candidate) >= MIN_LABEL_WIDTH_WITH_METADATA) {
                selected = candidate;
                break;
            }
        }
        columnsByBounds.put(key, selected);
        return selected;
    }

    private int resolvedLeadingWidth(Rect bounds, MetadataColumns columns) {
        return ScrollLayout.calculate(
            bounds,
            entries.size(),
            leadingExtent,
            hasMetadataRows ? columns.trailingWidth() : 0
        ).leadingBounds().width();
    }

    private List<MetadataColumns> metadataProfiles() {
        ArrayList<MetadataColumns> profiles = new ArrayList<>();
        if (showSizeColumn && showDateColumn) {
            for (int size = DEFAULT_SIZE_COLUMN_WIDTH; size >= MIN_SIZE_COLUMN_WIDTH; size--) {
                profiles.add(new MetadataColumns(size, DATE_COLUMN_WIDTH));
            }
            profiles.add(new MetadataColumns(MIN_SIZE_COLUMN_WIDTH, 0));
        } else if (showSizeColumn) {
            for (int size = DEFAULT_SIZE_COLUMN_WIDTH; size >= MIN_SIZE_COLUMN_WIDTH; size--) {
                profiles.add(new MetadataColumns(size, 0));
            }
        } else if (showDateColumn) {
            profiles.add(new MetadataColumns(0, DATE_COLUMN_WIDTH));
        }
        profiles.add(MetadataColumns.NONE);
        return List.copyOf(profiles);
    }

    private dev.tuiframe.widget.TreeView<FileTreeModel.EntryId> treeFor(
        MetadataColumns columns
    ) {
        dev.tuiframe.widget.TreeView<FileTreeModel.EntryId> cached =
            treesByColumns.get(columns);
        if (cached != null) {
            return cached;
        }
        ArrayList<dev.tuiframe.widget.TreeView.Item<FileTreeModel.EntryId>> projected =
            new ArrayList<>(entries.size());
        for (FileTreeModel.Entry entry : entries) {
            projected.add(new dev.tuiframe.widget.TreeView.Item<>(
                entry.id(),
                displayLabel(entry),
                widgetDepth(entry),
                widgetKind(entry),
                formatMetadata(entry, columns), PathPresentation.LABEL_POLICY
            ));
        }
        dev.tuiframe.widget.TreeView<FileTreeModel.EntryId> tree =
            new dev.tuiframe.widget.TreeView<>(
            List.copyOf(projected),
            dev.tuiframe.widget.TreeView.State.initial()
        );
        treesByColumns.put(columns, tree);
        return tree;
    }

    private int findIndex(FileTreeModel.EntryId id) {
        return id == null ? -1 : indexById.getOrDefault(id, -1);
    }

    private FileTreeModel.EntryId idAt(int index) {
        return index < 0 || index >= entries.size() ? null : entries.get(index).id();
    }

    private FileTreeModel.Entry entryAt(int index) {
        return index < 0 || index >= entries.size() ? null : entries.get(index);
    }

    private static String displayLabel(FileTreeModel.Entry entry) {
        return switch (entry.kind()) {
            case FILE -> PathPresentation.name(entry.path());
            case DIRECTORY -> PathPresentation.name(entry.path()) + (entry.path().getFileName() == null ? "" : "/");
            case PARENT, ERROR -> entry.name();
        };
    }

    private static dev.tuiframe.widget.TreeView.Kind widgetKind(
        FileTreeModel.Entry entry
    ) {
        if (entry.kind() == FileTreeModel.Kind.PARENT
            || entry.kind() == FileTreeModel.Kind.ERROR) {
            return dev.tuiframe.widget.TreeView.Kind.PLAIN;
        }
        if (entry.kind() != FileTreeModel.Kind.DIRECTORY) {
            return dev.tuiframe.widget.TreeView.Kind.LEAF;
        }
        return entry.expanded()
            ? dev.tuiframe.widget.TreeView.Kind.EXPANDED
            : dev.tuiframe.widget.TreeView.Kind.COLLAPSED;
    }

    private static int widgetDepth(FileTreeModel.Entry entry) {
        return entry.kind() == FileTreeModel.Kind.PARENT
            ? 0
            : entry.depth();
    }

    private static String formatMetadata(
        FileTreeModel.Entry entry,
        MetadataColumns columns
    ) {
        if (columns.trailingWidth() == 0
            || entry.kind() == FileTreeModel.Kind.PARENT
            || entry.kind() == FileTreeModel.Kind.ERROR) {
            return "";
        }
        StringBuilder detail = new StringBuilder(columns.trailingWidth());
        if (columns.sizeWidth() > 0) {
            detail.append(' ')
                .append(CellText.alignRight(entry.sizeValue(), columns.sizeWidth()));
        }
        if (columns.dateWidth() > 0) {
            detail.append(" |")
                .append(CellText.alignRight(entry.dateValue(), columns.dateWidth()));
        }
        return detail.toString();
    }

    private static int trailingWidth(int sizeWidth, int dateWidth) {
        return (sizeWidth > 0 ? sizeWidth + 1 : 0)
            + (dateWidth > 0 ? dateWidth + 2 : 0);
    }

    /** Application-owned viewport, stable selection and click tracker. */
    public record State(
        FileTreeModel.EntryId selectedId,
        dev.tuiframe.widget.TreeView.State widgetState,
        long snapshotRevision,
        FileTreeModel.EntryId lastPressedId,
        long lastPressedAtNanos
    ) {
        public State {
            Objects.requireNonNull(widgetState, "widgetState");
            if (snapshotRevision < -1L) {
                throw new IllegalArgumentException("snapshotRevision must be -1 or greater");
            }
            if (lastPressedId == null && lastPressedAtNanos != 0L) {
                throw new IllegalArgumentException("a missing lastPressedId requires a zero timestamp");
            }
        }

        public static State initial() {
            return new State(
                null,
                dev.tuiframe.widget.TreeView.State.initial(),
                -1L,
                null,
                0L
            );
        }

        public State resetClickTracking() {
            if (lastPressedId == null) {
                return this;
            }
            return new State(selectedId, widgetState, snapshotRevision, null, 0L);
        }

        /** Cancels pointer capture when ownership moves to another control or model. */
        public State cancelPointerInteraction() {
            if (widgetState.drag() == null) {
                return resetClickTracking();
            }
            return new State(
                selectedId,
                new dev.tuiframe.widget.TreeView.State(
                    widgetState.selectedIndex(),
                    widgetState.firstVisibleIndex(),
                    widgetState.horizontalOffset()
                ),
                snapshotRevision,
                null,
                0L
            );
        }
    }

    /** Explorer semantic outputs; generic presentation never sees paths or file policy. */
    public sealed interface Action
        permits StateChanged, ToggleRequested, RootRequested, FileRequested, MoveRequested {
        State state();
    }

    public record StateChanged(State state) implements Action {
        public StateChanged {
            Objects.requireNonNull(state, "state");
        }
    }

    public record ToggleRequested(
        FileTreeModel.EntryId id,
        boolean expand,
        State state
    ) implements Action {
        public ToggleRequested {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(state, "state");
        }
    }

    public record RootRequested(Path root, State state) implements Action {
        public RootRequested {
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            Objects.requireNonNull(state, "state");
        }
    }

    public record FileRequested(Path file, State state) implements Action {
        public FileRequested {
            file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
            Objects.requireNonNull(state, "state");
        }
    }

    /** A drop intent carries the identity observed by the scanner before the gesture. */
    public record MoveRequested(Path source, Path destination, ExplorerFileService.Target expected,
        State state) implements Action {
        public MoveRequested {
            Objects.requireNonNull(source); Objects.requireNonNull(destination);
            Objects.requireNonNull(expected); Objects.requireNonNull(state);
        }
    }

    /** Optional composite interaction owned by a containing Explorer. */
    public interface Interaction {
        InteractionResult<Action> handle(TuiInputEvent event, long nowNanos, long doubleClickWindowNanos);
        PlacedPointerCapture<Action> captureTarget();
        void paint(Canvas canvas, Theme theme);
    }

    /** Exact current-frame generic placement plus filesystem snapshot policy. */
    public final class Placement {
        private final dev.tuiframe.widget.TreeView.Placement<FileTreeModel.EntryId> placed;
        private final FocusContext context;
        private final State state;
        private final MetadataColumns columns;
        private final Interaction interaction;
        private final Rect allocation;

        private Placement(
            dev.tuiframe.widget.TreeView.Placement<FileTreeModel.EntryId> placed,
            FocusContext context,
            State state,
            MetadataColumns columns
        ) {
            this(placed, context, state, columns, null, null);
        }

        private Placement(dev.tuiframe.widget.TreeView.Placement<FileTreeModel.EntryId> placed,
            FocusContext context, State state, MetadataColumns columns, Interaction interaction, Rect allocation) {
            this.placed = Objects.requireNonNull(placed, "placed");
            this.context = Objects.requireNonNull(context, "context");
            this.state = Objects.requireNonNull(state, "state");
            this.columns = Objects.requireNonNull(columns, "columns");
            this.interaction = interaction;
            this.allocation = allocation;
        }

        public Placement withInteraction(Rect allocation, Interaction interaction) {
            return new Placement(placed, context, state, columns,
                Objects.requireNonNull(interaction), Objects.requireNonNull(allocation));
        }

        public Rect bounds() {
            return allocation == null ? placed.bounds() : allocation;
        }

        /**
         * Gesture-only binding for common pointer delivery. Entry presses keep
         * using {@link #handle(TuiInputEvent, long, long)} with the event's clock;
         * drag/release and cancellation cannot activate entries or double-click.
         */
        public PlacedPointerCapture<Action> captureTarget() {
            if (interaction != null) return interaction.captureTarget();
            return new PlacedPointerCapture<>() {
                @Override
                public Rect bounds() {
                    return placed.bounds();
                }

                @Override
                public boolean interactionReady() {
                    return placed.interactionReady();
                }

                @Override
                public void paint(Canvas canvas, Theme theme, WidgetState widgetState) {
                    placed.paint(canvas, theme, widgetState);
                }

                @Override
                public boolean pointerCaptureActive() {
                    return placed.pointerCaptureActive();
                }

                @Override
                public InteractionResult<Action> cancelPointerCapture(PointerCaptureCancellation reason) {
                    return captureResult(placed.cancelPointerCapture(reason));
                }

                @Override
                public InteractionResult<Action> handle(TuiInputEvent event, FocusContext ignored) {
                    if (!(event instanceof PointerEvent pointer)
                        || !(pointer.kind() == PointerEvent.Kind.RELEASE
                            || pointer.kind() == PointerEvent.Kind.DRAG
                                && pointer.button() == PointerEvent.Button.LEFT)) {
                        return InteractionResult.ignored();
                    }
                    return captureResult(placed.handle(event, context));
                }
            };
        }

        private InteractionResult<Action> captureResult(
            InteractionResult<dev.tuiframe.widget.TreeView.Action<FileTreeModel.EntryId>> result
        ) {
            return result.mapOutput(action -> {
                if (action instanceof dev.tuiframe.widget.TreeView.StateChanged<?> changed) {
                    return new StateChanged(adopt(changed.state(), state, true));
                }
                throw new IllegalStateException("captured pointer delivery cannot activate file entries");
            });
        }

        public Rect contentBounds() {
            return placed.contentBounds();
        }

        public Rect leadingBounds() {
            return placed.leadingBounds();
        }

        public Optional<Rect> trailingBounds() {
            return placed.trailingBounds();
        }

        public Optional<Rect> verticalScrollbarBounds() {
            return placed.verticalScrollbarBounds();
        }

        public Optional<Rect> horizontalScrollbarBounds() {
            return placed.horizontalScrollbarBounds();
        }

        public State state() {
            return state;
        }

        public Optional<FileTreeModel.Entry> selectedEntry() {
            return Optional.ofNullable(entryAt(state.widgetState().selectedIndex()));
        }

        public int itemCount() {
            return placed.itemCount();
        }

        public int horizontalExtent() {
            return placed.horizontalExtent();
        }

        public int sizeColumnWidth() {
            return columns.sizeWidth();
        }

        public int dateColumnWidth() {
            return columns.dateWidth();
        }

        public boolean enabled() {
            return context.enabled();
        }

        public boolean loading() {
            return snapshot.loading();
        }

        public void paint(Canvas canvas, Theme theme) {
            placed.paint(
                Objects.requireNonNull(canvas, "canvas"),
                Objects.requireNonNull(theme, "theme"),
                context.widgetState()
            );
            if (interaction != null) interaction.paint(canvas, theme);
        }

        /** Returns the exact selected-row cursor in terminal coordinates. */
        public Optional<Point> cursorPosition() {
            return placed.cursorPosition(context).flatMap(context.space()::terminalPoint);
        }

        public boolean contains(PointerEvent event) {
            Objects.requireNonNull(event, "event");
            return context.localPoint(event).filter(placed.bounds()::contains).isPresent();
        }

        /** Routes an event and applies Explorer activation/double-click policy. */
        public InteractionResult<Action> handle(
            TuiInputEvent event,
            long nowNanos,
            long doubleClickWindowNanos
        ) {
            Objects.requireNonNull(event, "event");
            if (interaction != null) return interaction.handle(event, nowNanos, doubleClickWindowNanos);
            if (doubleClickWindowNanos < 0L) {
                throw new IllegalArgumentException("doubleClickWindowNanos must not be negative");
            }
            InteractionResult<dev.tuiframe.widget.TreeView.Action<FileTreeModel.EntryId>>
                interaction = placed.handle(event, context);
            return interaction.mapOutput(action -> mapAction(action, nowNanos, doubleClickWindowNanos));
        }

        private Action mapAction(
            dev.tuiframe.widget.TreeView.Action<FileTreeModel.EntryId> action,
            long nowNanos,
            long doubleClickWindowNanos
        ) {
            if (action instanceof dev.tuiframe.widget.TreeView.StateChanged<?> changed) {
                return new StateChanged(adopt(changed.state(), state, true));
            }
            if (action instanceof dev.tuiframe.widget.TreeView.ExpansionRequested<?> request) {
                FileTreeModel.Entry entry = entryAt(request.itemIndex());
                State next = adopt(request.state(), state, true);
                if (entry == null || entry.kind() != FileTreeModel.Kind.DIRECTORY) {
                    return new StateChanged(next);
                }
                return new ToggleRequested(entry.id(), request.expand(), next);
            }
            if (action instanceof dev.tuiframe.widget.TreeView.Activated<?> activated) {
                State next = state.resetClickTracking();
                return keyboardActivation(entryAt(activated.itemIndex()), next);
            }
            if (action instanceof dev.tuiframe.widget.TreeView.ItemPressed<?> pressed) {
                FileTreeModel.Entry entry = entryAt(pressed.itemIndex());
                State selected = adopt(pressed.state(), state, false);
                if (entry == null) {
                    return new StateChanged(selected.resetClickTracking());
                }
                long clickDelta = nowNanos - state.lastPressedAtNanos();
                boolean doubleClick = entry.id().equals(state.lastPressedId())
                    && clickDelta >= 0L
                    && clickDelta <= doubleClickWindowNanos;
                State clicked = new State(
                    entry.id(),
                    selected.widgetState(),
                    snapshot.revision(),
                    entry.id(),
                    nowNanos
                );
                return doubleClick
                    ? doubleClickActivation(entry, clicked.resetClickTracking())
                    : new StateChanged(clicked);
            }
            throw new IllegalStateException("Unsupported tree action: " + action);
        }

        private Action keyboardActivation(FileTreeModel.Entry entry, State next) {
            if (entry == null || entry.kind() == FileTreeModel.Kind.ERROR) {
                return new StateChanged(next);
            }
            if (entry.kind() == FileTreeModel.Kind.FILE) {
                return new FileRequested(entry.path(), next);
            }
            if (entry.kind() == FileTreeModel.Kind.DIRECTORY
                && directoryActivation == DirectoryActivation.TOGGLE_EXPANSION) {
                return new ToggleRequested(entry.id(), !entry.expanded(), next);
            }
            return new RootRequested(entry.path(), next);
        }

        private Action doubleClickActivation(FileTreeModel.Entry entry, State next) {
            if (entry.kind() == FileTreeModel.Kind.ERROR) {
                return new StateChanged(next);
            }
            if (entry.kind() == FileTreeModel.Kind.DIRECTORY) {
                return new ToggleRequested(entry.id(), !entry.expanded(), next);
            }
            if (entry.kind() == FileTreeModel.Kind.FILE) {
                return new FileRequested(entry.path(), next);
            }
            return new RootRequested(entry.path(), next);
        }
    }

    private record MetadataColumns(int sizeWidth, int dateWidth) {
        private static final MetadataColumns NONE = new MetadataColumns(0, 0);

        private MetadataColumns {
            if (sizeWidth < 0 || dateWidth < 0) {
                throw new IllegalArgumentException("metadata widths must not be negative");
            }
        }

        private int trailingWidth() {
            return FileTreeView.trailingWidth(sizeWidth, dateWidth);
        }
    }

    private record BoundsSize(int width, int height) {
    }
}
