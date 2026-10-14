package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.scroll.ScrollInteraction;
import dev.tuiframe.scroll.ScrollLayout;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuiframe.style.CellStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immediate-mode tree presentation over an application-owned flat item list.
 *
 * <p>The widget does not retain or traverse a node hierarchy. The application
 * flattens its expanded model into presentation-only {@link Item} descriptors,
 * owns {@link State}, and applies typed expansion requests. One immutable
 * placement snapshots descriptor values, visible rows, layout and scrollbar
 * geometry for both painting and input routing.</p>
 *
 * <p>{@link Item#trailingText()} is an optional, already formatted detail. Its
 * common right-hand region remains pinned while horizontal scrolling affects
 * only indentation, disclosure glyphs and labels.</p>
 */
public final class TreeView<V> implements InteractiveWidget<TreeView.Action<V>> {
    private static final int INDENT_WIDTH = 2;
    private static final int DISCLOSURE_WIDTH = 2;
    private static final int SCROLL_STEP = 2;
    private static final String COLLAPSED_GLYPH = "▶";
    private static final String EXPANDED_GLYPH = "▼";
    private static final Set<KeyStroke.Modifier> SHIFT_ONLY = Set.of(KeyStroke.Modifier.SHIFT);

    private final List<PreparedItem<V>> items;
    private final State state;
    private final int horizontalExtent;
    private final int trailingExtent;

    public TreeView(List<Item<V>> items, State state) {
        List<Item<V>> snapshot = List.copyOf(Objects.requireNonNull(items, "items"));
        ArrayList<PreparedItem<V>> prepared = new ArrayList<>(snapshot.size());
        int widestLeading = 0;
        int widestTrailing = 0;
        for (int index = 0; index < snapshot.size(); index++) {
            Item<V> item = Objects.requireNonNull(snapshot.get(index), "items contains null at " + index);
            int leadingWidth = leadingWidth(item);
            int trailingWidth = CellText.width(item.trailingText());
            prepared.add(new PreparedItem<>(item, leadingWidth, trailingWidth));
            widestLeading = Math.max(widestLeading, leadingWidth);
            widestTrailing = Math.max(widestTrailing, trailingWidth);
        }
        this.items = List.copyOf(prepared);
        this.state = Objects.requireNonNull(state, "state");
        this.horizontalExtent = widestLeading;
        this.trailingExtent = widestTrailing;
    }

    @Override
    public Size measure(Size available) {
        return Objects.requireNonNull(available, "available");
    }

    @Override
    public Placement<V> layout(Rect bounds) {
        return layout(bounds, state);
    }

    /**
     * Reuses the prepared immutable descriptor snapshot with a new
     * application-owned state. State-only frames therefore avoid rescanning
     * labels or rebuilding the flattened model.
     */
    public Placement<V> layout(Rect bounds, State state) {
        Rect requiredBounds = Objects.requireNonNull(bounds, "bounds");
        State requiredState = Objects.requireNonNull(state, "state");
        ScrollLayout geometry = ScrollLayout.calculate(requiredBounds, items.size(), horizontalExtent, trailingExtent);
        State normalized = normalize(
            requiredState,
            items.size(),
            geometry.contentBounds().height(),
            geometry.leadingBounds().width(),
            horizontalExtent
        );
        if (normalized.drag() != null
            && (normalized.drag().axis() == Scrollbar.Axis.VERTICAL
                ? geometry.verticalScrollbarBounds() == null
                : geometry.horizontalScrollbarBounds() == null)) {
            normalized = new State(normalized.selectedIndex(), normalized.firstVisibleIndex(), normalized.horizontalOffset());
        }
        int visibleCount = geometry.contentBounds().width() <= 0
            ? 0
            : Math.min(
                geometry.contentBounds().height(),
                Math.max(0, items.size() - normalized.firstVisibleIndex())
            );
        ArrayList<VisibleItem<V>> visible = new ArrayList<>(visibleCount);
        for (int rowOffset = 0; rowOffset < visibleCount; rowOffset++) {
            int itemIndex = normalized.firstVisibleIndex() + rowOffset;
            visible.add(new VisibleItem<>(itemIndex, items.get(itemIndex)));
        }
        return new Placement<>(
            requiredBounds,
            geometry,
            items,
            List.copyOf(visible),
            normalized,
            horizontalExtent
        );
    }

    private static int leadingWidth(Item<?> item) {
        long prefix = (long) item.depth() * INDENT_WIDTH + disclosureWidth(item.kind());
        long total = prefix + CellText.width(item.label());
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** Flat presentation descriptor. The opaque value remains consumer-owned. */
    public record Item<V>(V value, String label, int depth, Kind kind, String trailingText) {
        public Item {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(label, "label");
            if (depth < 0) {
                throw new IllegalArgumentException("depth must not be negative");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(trailingText, "trailingText");
        }

        public Item(V value, String label, int depth, Kind kind) {
            this(value, label, depth, kind, "");
        }
    }

    /** Presentation state of a flat tree row. Expansion ownership stays outside the widget. */
    public enum Kind {
        /** Plain text without a disclosure slot, for synthetic or informational rows. */
        PLAIN,
        /** A leaf aligned with branch labels by reserving the disclosure slot. */
        LEAF,
        COLLAPSED,
        EXPANDED
    }

    /** Application-owned selection and vertical/horizontal viewport. */
    public record State(int selectedIndex, int firstVisibleIndex, int horizontalOffset, Scrollbar.Drag drag) {
        public State {
            if (selectedIndex < -1) {
                throw new IllegalArgumentException("selectedIndex must be -1 or greater");
            }
            if (firstVisibleIndex < 0) {
                throw new IllegalArgumentException("firstVisibleIndex must not be negative");
            }
            if (horizontalOffset < 0) {
                throw new IllegalArgumentException("horizontalOffset must not be negative");
            }
        }

        public State(int selectedIndex, int firstVisibleIndex, int horizontalOffset) {
            this(selectedIndex, firstVisibleIndex, horizontalOffset, null);
        }

        public static State initial() {
            return new State(-1, 0, 0);
        }
    }

    /** Semantic tree actions independent of a consumer's hierarchy or command model. */
    public sealed interface Action<V> permits StateChanged, ItemPressed, Activated, ExpansionRequested {
    }

    /** Keyboard, wheel, or scrollbar navigation produced a new state. */
    public record StateChanged<V>(State state) implements Action<V> {
        public StateChanged {
            Objects.requireNonNull(state, "state");
        }
    }

    /** A pointer press selected a row; click timing remains application-owned. */
    public record ItemPressed<V>(V value, int itemIndex, State state) implements Action<V> {
        public ItemPressed {
            Objects.requireNonNull(value, "value");
            requireItemIndex(itemIndex);
            Objects.requireNonNull(state, "state");
        }
    }

    /** The selected row was activated by the keyboard. */
    public record Activated<V>(V value, int itemIndex) implements Action<V> {
        public Activated {
            Objects.requireNonNull(value, "value");
            requireItemIndex(itemIndex);
        }
    }

    /** A disclosure press or keyboard action requested expansion or collapse. */
    public record ExpansionRequested<V>(
        V value,
        int itemIndex,
        boolean expand,
        State state
    ) implements Action<V> {
        public ExpansionRequested {
            Objects.requireNonNull(value, "value");
            requireItemIndex(itemIndex);
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * Immutable exact-frame placement. Rebuild it after descriptors, state, or
     * bounds change; an older placement intentionally keeps its old snapshot.
     */
    public static final class Placement<V>
        implements PlacedPointerCapture<Action<V>>, PlacedCursorSource {
        private final Rect bounds;
        private final ScrollLayout geometry;
        private final Rect contentBounds;
        private final Rect leadingBounds;
        private final Rect trailingBounds;
        private final Rect verticalScrollbarBounds;
        private final Rect horizontalScrollbarBounds;
        private final List<PreparedItem<V>> items;
        private final List<VisibleItem<V>> visibleItems;
        private final State state;
        private final int horizontalExtent;

        private Placement(
            Rect bounds,
            ScrollLayout geometry,
            List<PreparedItem<V>> items,
            List<VisibleItem<V>> visibleItems,
            State state,
            int horizontalExtent
        ) {
            this.bounds = bounds;
            this.geometry = geometry;
            this.contentBounds = geometry.contentBounds();
            this.leadingBounds = geometry.leadingBounds();
            this.trailingBounds = geometry.trailingBounds();
            this.verticalScrollbarBounds = geometry.verticalScrollbarBounds();
            this.horizontalScrollbarBounds = geometry.horizontalScrollbarBounds();
            this.items = items;
            this.visibleItems = visibleItems;
            this.state = state;
            this.horizontalExtent = horizontalExtent;
        }

        @Override
        public Rect bounds() {
            return bounds;
        }

        public State state() {
            return state;
        }

        /** Complete text-row region, including pinned trailing text. */
        public Rect contentBounds() {
            return contentBounds;
        }

        /** Horizontally scrollable hierarchy-label region. */
        public Rect leadingBounds() {
            return leadingBounds;
        }

        /** Common pinned trailing-text region when at least one detail cell fits. */
        public Optional<Rect> trailingBounds() {
            return Optional.ofNullable(trailingBounds);
        }

        /**
         * Exact track geometry is exposed for consumer-owned presentation.
         * The regular handler retains pointer capture in {@link State#drag()}
         * and handles drags outside the widget until release.
         */
        public Optional<Rect> verticalScrollbarBounds() {
            return Optional.ofNullable(verticalScrollbarBounds);
        }

        public Optional<Rect> horizontalScrollbarBounds() {
            return Optional.ofNullable(horizontalScrollbarBounds);
        }

        public Optional<Scrollbar> verticalScrollbar() {
            return Optional.ofNullable(verticalScrollbarBounds)
                .map(track -> Scrollbar.vertical(track, items.size(), state.firstVisibleIndex()));
        }

        public Optional<Scrollbar> horizontalScrollbar() {
            return Optional.ofNullable(horizontalScrollbarBounds)
                .map(track -> Scrollbar.horizontal(track, horizontalExtent, state.horizontalOffset()));
        }

        public int itemCount() {
            return items.size();
        }

        /** Total logical width of indentation, disclosure, and the widest label. */
        public int horizontalExtent() {
            return horizontalExtent;
        }

        /** Cursor intent for the exact visible selected row, in parent-local coordinates. */
        @Override
        public Optional<Point> cursorPosition(FocusContext context) {
            Objects.requireNonNull(context, "context");
            if (!context.focused() || !context.enabled() || leadingBounds.isEmpty()) {
                return Optional.empty();
            }
            int selectedIndex = state.selectedIndex();
            int rowOffset = selectedIndex - state.firstVisibleIndex();
            if (selectedIndex < 0
                || selectedIndex >= items.size()
                || rowOffset < 0
                || rowOffset >= contentBounds.height()) {
                return Optional.empty();
            }
            PreparedItem<V> prepared = items.get(selectedIndex);
            Item<V> selected = prepared.item();
            long markerColumn = (long) selected.depth() * INDENT_WIDTH;
            long markerX = (long) leadingBounds.x() + markerColumn - state.horizontalOffset();
            long labelX = markerX + disclosureWidth(selected.kind());
            long contentEndX = (long) leadingBounds.x()
                + prepared.leadingWidth()
                - state.horizontalOffset();
            if (contentEndX <= leadingBounds.x()) {
                return Optional.empty();
            }
            long cursorX;
            if (hasDisclosure(selected.kind())
                && markerX >= leadingBounds.x()
                && markerX < leadingBounds.rightExclusive()) {
                cursorX = markerX;
            } else if (labelX < leadingBounds.rightExclusive()) {
                cursorX = Math.max((long) leadingBounds.x(), labelX);
            } else {
                return Optional.empty();
            }
            Point cursor = new Point((int) cursorX, Math.addExact(contentBounds.y(), rowOffset));
            return leadingBounds.contains(cursor) && context.space().parentClip().contains(cursor)
                ? Optional.of(cursor)
                : Optional.empty();
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState widgetState) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(widgetState, "widgetState");
            if (bounds.isEmpty()) {
                return;
            }
            CellStyle normalStyle = theme.resolveStyle(
                widgetState.enabled() ? WidgetThemeKeys.LIST_ITEM : WidgetThemeKeys.LIST_ITEM_DISABLED
            );
            CellStyle selectedStyle = normalStyle;
            if (widgetState.enabled()) {
                CellStyle selected = theme.resolveStyle(WidgetThemeKeys.LIST_ITEM_SELECTED, normalStyle);
                selectedStyle = widgetState.focused()
                    ? theme.resolveStyle(WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED, selected)
                    : selected;
            }
            int mark = canvas.save();
            try {
                if (!canvas.clip(bounds)) {
                    return;
                }
                canvas.fill(bounds, normalStyle);
                paintRows(canvas, normalStyle, selectedStyle);
                paintScrollbars(canvas, theme, normalStyle);
            } finally {
                canvas.restore(mark);
            }
        }

        private void paintRows(Canvas canvas, CellStyle normalStyle, CellStyle selectedStyle) {
            if (contentBounds.isEmpty()) {
                return;
            }
            for (int rowOffset = 0; rowOffset < visibleItems.size(); rowOffset++) {
                VisibleItem<V> visible = visibleItems.get(rowOffset);
                CellStyle rowStyle = visible.itemIndex() == state.selectedIndex()
                    ? selectedStyle
                    : normalStyle;
                int row = Math.addExact(contentBounds.y(), rowOffset);
                if (!rowStyle.equals(normalStyle)) {
                    canvas.fill(contentBounds.x(), row, contentBounds.width(), 1, rowStyle);
                }
                paintLeading(canvas, visible.item(), row, rowStyle);
                paintTrailing(canvas, visible.item(), row, rowStyle);
            }
        }

        private void paintLeading(Canvas canvas, PreparedItem<V> prepared, int row, CellStyle style) {
            if (leadingBounds.width() <= 0) {
                return;
            }
            Item<V> item = prepared.item();
            long markerColumn = (long) item.depth() * INDENT_WIDTH;
            long markerX = (long) leadingBounds.x() + markerColumn - state.horizontalOffset();
            if (hasDisclosure(item.kind())
                && markerX >= leadingBounds.x()
                && markerX < leadingBounds.rightExclusive()) {
                canvas.put(
                    (int) markerX,
                    row,
                    item.kind() == Kind.EXPANDED ? EXPANDED_GLYPH : COLLAPSED_GLYPH,
                    style
                );
            }

            long labelColumn = markerColumn + disclosureWidth(item.kind());
            long labelX = (long) leadingBounds.x() + labelColumn - state.horizontalOffset();
            long visibleLeft = Math.max((long) leadingBounds.x(), labelX);
            long visibleRight = leadingBounds.rightExclusive();
            if (visibleLeft >= visibleRight) {
                return;
            }
            int hiddenCells = labelX < leadingBounds.x()
                ? saturatedInt((long) leadingBounds.x() - labelX)
                : 0;
            int available = (int) (visibleRight - visibleLeft);
            String text = hiddenCells == 0
                ? item.label()
                : CellText.clip(item.label(), hiddenCells, available);
            CellText.drawClipped(canvas, (int) visibleLeft, row, text, style, available);
        }

        private void paintTrailing(Canvas canvas, PreparedItem<V> prepared, int row, CellStyle style) {
            if (trailingBounds == null || prepared.trailingWidth() == 0) {
                return;
            }
            int capacity = trailingBounds.width();
            String detail = prepared.item().trailingText();
            if (prepared.trailingWidth() <= capacity) {
                int start = trailingBounds.rightExclusive() - prepared.trailingWidth();
                CellText.drawClipped(canvas, start, row, detail, style, prepared.trailingWidth());
                return;
            }
            int hidden = prepared.trailingWidth() - capacity;
            String clipped = CellText.clip(detail, hidden, capacity);
            CellText.drawClipped(canvas, trailingBounds.x(), row, clipped, style, capacity);
        }

        private void paintScrollbars(
            Canvas canvas,
            Theme theme,
            CellStyle normalStyle
        ) {
            geometry.paintCorner(canvas, theme.scrollStyle(normalStyle));
            verticalScrollbar().ifPresent(bar -> bar.draw(canvas, theme.scrollStyle(normalStyle)));
            horizontalScrollbar().ifPresent(bar -> bar.draw(canvas, theme.scrollStyle(normalStyle)));
        }

        @Override
        public boolean pointerCaptureActive() {
            return state.drag() != null;
        }

        @Override
        public InteractionResult<Action<V>> cancelPointerCapture(PointerCaptureCancellation reason) {
            Objects.requireNonNull(reason, "reason");
            return stateChange(new State(state.selectedIndex(), state.firstVisibleIndex(), state.horizontalOffset()))
                .withPointerCapture(PointerCaptureRequest.RELEASE);
        }

        @Override
        public InteractionResult<Action<V>> handle(TuiInputEvent event, FocusContext context) {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(context, "context");
            if (state.drag() != null && (event instanceof ResizeEvent
                || event instanceof PointerEvent pointer && pointer.kind() == PointerEvent.Kind.RELEASE)) {
                return cancelPointerCapture(event instanceof ResizeEvent
                    ? PointerCaptureCancellation.RESIZE : PointerCaptureCancellation.RELEASE);
            }
            if (!context.enabled() || bounds.isEmpty()) {
                return InteractionResult.ignored();
            }
            if (event instanceof PointerEvent pointer) {
                return handlePointer(pointer, context);
            }
            if (!context.focused() || !(event instanceof KeyStroke stroke)) {
                return InteractionResult.ignored();
            }
            if (stroke.modifiers().isEmpty()) {
                return handleKey(stroke.key());
            }
            if (stroke.modifiers().equals(SHIFT_ONLY)) {
                return switch (stroke.key()) {
                    case ARROW_LEFT -> horizontalBy(-SCROLL_STEP);
                    case ARROW_RIGHT -> horizontalBy(SCROLL_STEP);
                    default -> InteractionResult.ignored();
                };
            }
            return InteractionResult.ignored();
        }

        private InteractionResult<Action<V>> handleKey(KeyStroke.Key key) {
            return switch (key) {
                case ARROW_UP -> stateChange(moveSelection(-1));
                case ARROW_DOWN -> stateChange(moveSelection(1));
                case PAGE_UP -> stateChange(moveSelection(-Math.max(1, contentBounds.height())));
                case PAGE_DOWN -> stateChange(moveSelection(Math.max(1, contentBounds.height())));
                case HOME -> stateChange(select(0));
                case END -> stateChange(select(Math.max(0, items.size() - 1)));
                case ARROW_LEFT -> handleLeft();
                case ARROW_RIGHT -> handleRight();
                case ENTER -> activateSelected();
                default -> InteractionResult.ignored();
            };
        }

        private InteractionResult<Action<V>> handleLeft() {
            PreparedItem<V> selected = selectedItem();
            if (selected == null) {
                return InteractionResult.handled();
            }
            if (selected.item().kind() == Kind.EXPANDED) {
                return InteractionResult.output(expansion(selected, state.selectedIndex(), false, state));
            }
            int depth = selected.item().depth();
            for (int index = state.selectedIndex() - 1; index >= 0; index--) {
                if (items.get(index).item().depth() < depth) {
                    return stateChange(select(index));
                }
            }
            return InteractionResult.handled();
        }

        private InteractionResult<Action<V>> handleRight() {
            PreparedItem<V> selected = selectedItem();
            if (selected == null) {
                return InteractionResult.handled();
            }
            return selected.item().kind() == Kind.COLLAPSED
                ? InteractionResult.output(expansion(selected, state.selectedIndex(), true, state))
                : InteractionResult.handled();
        }

        private InteractionResult<Action<V>> activateSelected() {
            PreparedItem<V> selected = selectedItem();
            return selected == null
                ? InteractionResult.handled()
                : InteractionResult.output(new Activated<>(selected.item().value(), state.selectedIndex()));
        }

        private InteractionResult<Action<V>> handlePointer(PointerEvent pointer, FocusContext context) {
            if (state.drag() != null) {
                if (pointer.kind() == PointerEvent.Kind.DRAG && pointer.button() == PointerEvent.Button.LEFT) {
                    Point point = capturedPoint(pointer, context);
                    Optional<Scrollbar> bar = state.drag().axis() == Scrollbar.Axis.VERTICAL
                        ? verticalScrollbar()
                        : horizontalScrollbar();
                    if (bar.isPresent()) {
                        int offset = bar.orElseThrow().drag(point, state.drag());
                        State next = state.drag().axis() == Scrollbar.Axis.VERTICAL
                            ? new State(state.selectedIndex(), offset, state.horizontalOffset(), state.drag())
                            : new State(state.selectedIndex(), state.firstVisibleIndex(), offset, state.drag());
                        return InteractionResult.output(new StateChanged<>(next));
                    }
                }
            }
            if (!pointer.modifiers().isEmpty()) {
                return InteractionResult.ignored();
            }
            Optional<Point> local = context.localPoint(pointer).filter(bounds::contains);
            if (local.isEmpty()) {
                return InteractionResult.ignored();
            }
            Point point = local.orElseThrow();
            return switch (pointer.kind()) {
                case SCROLL_UP -> verticalBy(-1);
                case SCROLL_DOWN -> verticalBy(1);
                case SCROLL_LEFT -> horizontalBy(-SCROLL_STEP);
                case SCROLL_RIGHT -> horizontalBy(SCROLL_STEP);
                case PRESS -> pointer.button() == PointerEvent.Button.LEFT
                    ? handlePress(point)
                    : InteractionResult.ignored();
                default -> InteractionResult.ignored();
            };
        }

        private static Point capturedPoint(PointerEvent pointer, FocusContext context) {
            return new Point(
                signedInt((long) pointer.column() - context.space().terminalOrigin().x()),
                signedInt((long) pointer.row() - context.space().terminalOrigin().y())
            );
        }

        private InteractionResult<Action<V>> handlePress(Point point) {
            Optional<Scrollbar.Press> vertical = verticalScrollbar().flatMap(bar -> bar.press(point));
            if (vertical.isPresent()) {
                Scrollbar.Press press = vertical.orElseThrow();
                return withSelf(new StateChanged<>(new State(
                    state.selectedIndex(), press.offset(), state.horizontalOffset(), press.drag()
                ))).withPointerCapture(press.drag() == null
                    ? PointerCaptureRequest.NONE : PointerCaptureRequest.ACQUIRE);
            }
            Optional<Scrollbar.Press> horizontal = horizontalScrollbar().flatMap(bar -> bar.press(point));
            if (horizontal.isPresent()) {
                Scrollbar.Press press = horizontal.orElseThrow();
                return withSelf(new StateChanged<>(new State(
                    state.selectedIndex(), state.firstVisibleIndex(), press.offset(), press.drag()
                ))).withPointerCapture(press.drag() == null
                    ? PointerCaptureRequest.NONE : PointerCaptureRequest.ACQUIRE);
            }
            if (!contentBounds.contains(point)) {
                return InteractionResult.focus(FocusRequest.SELF);
            }
            int itemIndex = state.firstVisibleIndex() + point.y() - contentBounds.y();
            if (itemIndex < 0 || itemIndex >= items.size()) {
                return InteractionResult.focus(FocusRequest.SELF);
            }
            PreparedItem<V> item = items.get(itemIndex);
            State pressedState = new State(itemIndex, state.firstVisibleIndex(), state.horizontalOffset());
            if (disclosureHit(item.item(), point)) {
                boolean expand = item.item().kind() == Kind.COLLAPSED;
                return withSelf(expansion(item, itemIndex, expand, pressedState));
            }
            return withSelf(new ItemPressed<>(item.item().value(), itemIndex, pressedState));
        }

        private boolean disclosureHit(Item<V> item, Point point) {
            if (!hasDisclosure(item.kind()) || !leadingBounds.contains(point)) {
                return false;
            }
            long disclosureX = (long) leadingBounds.x()
                + (long) item.depth() * INDENT_WIDTH
                - state.horizontalOffset();
            return disclosureX == point.x();
        }

        private InteractionResult<Action<V>> verticalBy(int delta) {
            int rows = Math.max(1, contentBounds.height());
            int first = ScrollInteraction.scrollBy(state.firstVisibleIndex(), delta, rows, items.size());
            return stateChange(withFirstVisible(first));
        }

        private InteractionResult<Action<V>> horizontalBy(int delta) {
            int offset = ScrollInteraction.scrollBy(state.horizontalOffset(), delta, leadingBounds.width(), horizontalExtent);
            return stateChange(withHorizontalOffset(offset));
        }

        private State moveSelection(int delta) {
            if (items.isEmpty()) {
                return state;
            }
            long candidate = (long) state.selectedIndex() + delta;
            int selected = (int) Math.max(0L, Math.min((long) items.size() - 1, candidate));
            return ensureVisible(new State(selected, state.firstVisibleIndex(), state.horizontalOffset()));
        }

        private State select(int itemIndex) {
            if (items.isEmpty()) {
                return state;
            }
            int selected = clamp(itemIndex, 0, items.size() - 1);
            return ensureVisible(new State(selected, state.firstVisibleIndex(), state.horizontalOffset()));
        }

        private State ensureVisible(State next) {
            int rows = Math.max(1, contentBounds.height());
            int first = next.firstVisibleIndex();
            if (next.selectedIndex() < first) {
                first = next.selectedIndex();
            } else if (next.selectedIndex() >= first + rows) {
                first = next.selectedIndex() - rows + 1;
            }
            return new State(
                next.selectedIndex(),
                clamp(first, 0, maxScroll(items.size(), rows)),
                next.horizontalOffset()
            );
        }

        private PreparedItem<V> selectedItem() {
            int selected = state.selectedIndex();
            return selected < 0 || selected >= items.size() ? null : items.get(selected);
        }

        private int maxHorizontalOffset() {
            return leadingBounds.width() <= 0
                ? 0
                : Math.max(0, horizontalExtent - leadingBounds.width());
        }

        private State withFirstVisible(int firstVisible) {
            int rows = Math.max(1, contentBounds.height());
            return new State(
                state.selectedIndex(),
                clamp(firstVisible, 0, maxScroll(items.size(), rows)),
                state.horizontalOffset()
            );
        }

        private State withHorizontalOffset(int horizontalOffset) {
            return new State(
                state.selectedIndex(),
                state.firstVisibleIndex(),
                clamp(horizontalOffset, 0, maxHorizontalOffset())
            );
        }

        private InteractionResult<Action<V>> stateChange(State next) {
            return next.equals(state)
                ? InteractionResult.handled()
                : InteractionResult.output(new StateChanged<>(next));
        }

        private InteractionResult<Action<V>> withSelf(Action<V> action) {
            return new InteractionResult<>(
                true,
                FocusRequest.SELF,
                Optional.of(Objects.requireNonNull(action, "action"))
            );
        }

        private ExpansionRequested<V> expansion(
            PreparedItem<V> item,
            int itemIndex,
            boolean expand,
            State nextState
        ) {
            return new ExpansionRequested<>(item.item().value(), itemIndex, expand, nextState);
        }
    }

    private static State normalize(
        State state,
        int itemCount,
        int visibleRows,
        int visibleColumns,
        int horizontalExtent
    ) {
        if (itemCount == 0) {
            return State.initial();
        }
        int selected = clamp(state.selectedIndex(), 0, itemCount - 1);
        int rows = Math.max(1, visibleRows);
        int first = clamp(state.firstVisibleIndex(), 0, maxScroll(itemCount, rows));
        int horizontal = visibleColumns <= 0
            ? 0
            : clamp(state.horizontalOffset(), 0, Math.max(0, horizontalExtent - visibleColumns));
        return new State(selected, first, horizontal, state.drag());
    }

    private static int maxScroll(int itemCount, int visibleRows) {
        return Math.max(0, itemCount - Math.max(1, visibleRows));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static int saturatedInt(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }

    private static int signedInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private static boolean hasDisclosure(Kind kind) {
        return kind == Kind.COLLAPSED || kind == Kind.EXPANDED;
    }

    private static int disclosureWidth(Kind kind) {
        return kind == Kind.PLAIN ? 0 : DISCLOSURE_WIDTH;
    }

    private static void requireItemIndex(int itemIndex) {
        if (itemIndex < 0) {
            throw new IllegalArgumentException("itemIndex must not be negative");
        }
    }

    private record PreparedItem<V>(Item<V> item, int leadingWidth, int trailingWidth) {
    }

    private record VisibleItem<V>(int itemIndex, PreparedItem<V> item) {
    }

}
