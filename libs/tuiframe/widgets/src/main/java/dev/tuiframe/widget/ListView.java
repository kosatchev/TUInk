package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.scroll.ScrollInteraction;
import dev.tuiframe.scroll.VerticalScrollbar;
import dev.tuiframe.style.CellStyle;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Immediate-mode, vertically virtualized text list.
 *
 * <p>The application owns both the item source and {@link State}. Layout reads
 * only the visible rows and returns an immutable frame snapshot; handling an
 * event produces a typed action instead of mutating application state.</p>
 */
public final class ListView implements InteractiveWidget<ListView.Action> {
    private static final String TRACK_GRAPHEME = "│";
    private static final String THUMB_GRAPHEME = "█";

    private final int itemCount;
    private final IntFunction<String> itemText;
    private final State state;

    public ListView(int itemCount, IntFunction<String> itemText, State state) {
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must not be negative");
        }
        this.itemCount = itemCount;
        this.itemText = Objects.requireNonNull(itemText, "itemText");
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public Size measure(Size available) {
        return Objects.requireNonNull(available, "available");
    }

    @Override
    public Placement layout(Rect bounds) {
        Rect requiredBounds = Objects.requireNonNull(bounds, "bounds");
        boolean showScrollbar = requiredBounds.width() >= 2
            && requiredBounds.height() >= 2
            && itemCount > requiredBounds.height();
        int contentWidth = Math.max(0, requiredBounds.width() - (showScrollbar ? 1 : 0));
        Rect contentBounds = new Rect(
            requiredBounds.x(),
            requiredBounds.y(),
            contentWidth,
            requiredBounds.height()
        );
        Rect scrollbarBounds = showScrollbar
            ? new Rect(requiredBounds.rightExclusive() - 1, requiredBounds.y(), 1, requiredBounds.height())
            : null;
        State normalized = normalize(state, itemCount, requiredBounds.height());
        int visibleCount = contentWidth <= 0
            ? 0
            : Math.min(requiredBounds.height(), Math.max(0, itemCount - normalized.firstVisibleIndex()));
        String[] visibleRows = new String[visibleCount];
        for (int row = 0; row < visibleCount; row++) {
            int itemIndex = normalized.firstVisibleIndex() + row;
            visibleRows[row] = Objects.requireNonNull(
                itemText.apply(itemIndex),
                "itemText returned null for index " + itemIndex
            );
        }
        return new Placement(
            requiredBounds,
            contentBounds,
            scrollbarBounds,
            itemCount,
            normalized,
            visibleRows
        );
    }

    /**
     * Application-owned selection and vertical viewport state. A selected
     * index of {@code -1} is a bootstrap/empty value; a non-empty placement
     * normalizes it to the first item.
     */
    public record State(int selectedIndex, int firstVisibleIndex) {
        public State {
            if (selectedIndex < -1) {
                throw new IllegalArgumentException("selectedIndex must be -1 or greater");
            }
            if (firstVisibleIndex < 0) {
                throw new IllegalArgumentException("firstVisibleIndex must not be negative");
            }
        }

        public static State initial() {
            return new State(-1, 0);
        }
    }

    /** Semantic list actions independent of an application command model. */
    public sealed interface Action permits StateChanged, ItemPressed, Activated {
    }

    /** Keyboard, wheel, or scrollbar navigation produced a new state. */
    public record StateChanged(State state) implements Action {
        public StateChanged {
            Objects.requireNonNull(state, "state");
        }
    }

    /** A pointer press selected an item; click timing remains application-owned. */
    public record ItemPressed(int itemIndex, State state) implements Action {
        public ItemPressed {
            if (itemIndex < 0) {
                throw new IllegalArgumentException("itemIndex must not be negative");
            }
            Objects.requireNonNull(state, "state");
        }
    }

    /** The currently selected item was activated by the keyboard. */
    public record Activated(int itemIndex) implements Action {
        public Activated {
            if (itemIndex < 0) {
                throw new IllegalArgumentException("itemIndex must not be negative");
            }
        }
    }

    /** Immutable exact-frame placement; rebuild it after data or state changes. */
    public static final class Placement implements PlacedInteractiveWidget<Action> {
        private final Rect bounds;
        private final Rect contentBounds;
        private final Rect scrollbarBounds;
        private final int itemCount;
        private final State state;
        private final String[] visibleRows;

        private Placement(
            Rect bounds,
            Rect contentBounds,
            Rect scrollbarBounds,
            int itemCount,
            State state,
            String[] visibleRows
        ) {
            this.bounds = bounds;
            this.contentBounds = contentBounds;
            this.scrollbarBounds = scrollbarBounds;
            this.itemCount = itemCount;
            this.state = state;
            this.visibleRows = visibleRows;
        }

        @Override
        public Rect bounds() {
            return bounds;
        }

        public State state() {
            return state;
        }

        public Rect contentBounds() {
            return contentBounds;
        }

        public Optional<Rect> scrollbarBounds() {
            return Optional.ofNullable(scrollbarBounds);
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
                CellStyle unfocusedSelected = theme.resolveStyle(
                    WidgetThemeKeys.LIST_ITEM_SELECTED,
                    normalStyle
                );
                selectedStyle = widgetState.focused()
                    ? theme.resolveStyle(
                        WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED,
                        unfocusedSelected
                    )
                    : unfocusedSelected;
            }
            int mark = canvas.save();
            try {
                if (!canvas.clip(bounds)) {
                    return;
                }
                paintRows(canvas, normalStyle, selectedStyle);
                if (scrollbarBounds != null) {
                    VerticalScrollbar.draw(
                        canvas,
                        scrollbarBounds,
                        itemCount,
                        state.firstVisibleIndex(),
                        TRACK_GRAPHEME,
                        theme.resolveStyle(WidgetThemeKeys.SCROLLBAR_TRACK, normalStyle),
                        THUMB_GRAPHEME,
                        theme.resolveStyle(WidgetThemeKeys.SCROLLBAR_THUMB, selectedStyle)
                    );
                }
            } finally {
                canvas.restore(mark);
            }
        }

        private void paintRows(Canvas canvas, CellStyle normalStyle, CellStyle selectedStyle) {
            if (contentBounds.isEmpty()) {
                return;
            }
            int mark = canvas.save();
            try {
                if (!canvas.clip(contentBounds)) {
                    return;
                }
                canvas.fill(contentBounds, normalStyle);
                for (int rowOffset = 0; rowOffset < visibleRows.length; rowOffset++) {
                    int itemIndex = state.firstVisibleIndex() + rowOffset;
                    CellStyle rowStyle = itemIndex == state.selectedIndex()
                        ? selectedStyle
                        : normalStyle;
                    int row = contentBounds.y() + rowOffset;
                    if (!rowStyle.equals(normalStyle)) {
                        canvas.fill(contentBounds.x(), row, contentBounds.width(), 1, rowStyle);
                    }
                    CellText.drawClipped(
                        canvas,
                        contentBounds.x(),
                        row,
                        visibleRows[rowOffset],
                        rowStyle,
                        contentBounds.width()
                    );
                }
            } finally {
                canvas.restore(mark);
            }
        }

        @Override
        public InteractionResult<Action> handle(TuiInputEvent event, FocusContext context) {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(context, "context");
            if (!context.enabled() || bounds.isEmpty()) {
                return InteractionResult.ignored();
            }
            if (event instanceof PointerEvent pointer) {
                return handlePointer(pointer, context);
            }
            if (!context.focused() || !(event instanceof KeyStroke stroke)
                || !stroke.modifiers().isEmpty()) {
                return InteractionResult.ignored();
            }
            return handleKey(stroke.key());
        }

        private InteractionResult<Action> handleKey(KeyStroke.Key key) {
            if (key == KeyStroke.Key.ENTER) {
                return itemCount == 0
                    ? InteractionResult.handled()
                    : InteractionResult.output(new Activated(state.selectedIndex()));
            }
            State next = switch (key) {
                case ARROW_UP -> moveSelection(-1);
                case ARROW_DOWN -> moveSelection(1);
                case PAGE_UP -> moveSelection(-Math.max(1, contentBounds.height()));
                case PAGE_DOWN -> moveSelection(Math.max(1, contentBounds.height()));
                case HOME -> select(0);
                case END -> select(Math.max(0, itemCount - 1));
                default -> null;
            };
            if (next == null) {
                return InteractionResult.ignored();
            }
            return next.equals(state)
                ? InteractionResult.handled()
                : InteractionResult.output(new StateChanged(next));
        }

        private InteractionResult<Action> handlePointer(PointerEvent pointer, FocusContext context) {
            if (!pointer.modifiers().isEmpty()) {
                return InteractionResult.ignored();
            }
            Optional<Point> local = context.localPoint(pointer).filter(bounds::contains);
            if (local.isEmpty()) {
                return InteractionResult.ignored();
            }
            Point point = local.orElseThrow();
            if (pointer.kind() == PointerEvent.Kind.SCROLL_UP
                || pointer.kind() == PointerEvent.Kind.SCROLL_DOWN) {
                int delta = pointer.kind() == PointerEvent.Kind.SCROLL_UP ? -1 : 1;
                State next = scrollBy(delta);
                return next.equals(state)
                    ? InteractionResult.handled()
                    : InteractionResult.output(new StateChanged(next));
            }
            if (pointer.kind() != PointerEvent.Kind.PRESS
                || pointer.button() != PointerEvent.Button.LEFT) {
                return InteractionResult.ignored();
            }
            if (scrollbarBounds != null && scrollbarBounds.contains(point)) {
                ScrollInteraction.Hit hit = VerticalScrollbar.hit(
                    point.y(),
                    point.x(),
                    scrollbarBounds.y(),
                    scrollbarBounds.x(),
                    scrollbarBounds.height(),
                    itemCount,
                    state.firstVisibleIndex()
                );
                if (hit == ScrollInteraction.Hit.TRACK) {
                    int nextTop = VerticalScrollbar.pageScrollFromTrackClick(
                        state.firstVisibleIndex(),
                        point.y(),
                        scrollbarBounds.y(),
                        scrollbarBounds.height(),
                        itemCount
                    );
                    State next = new State(state.selectedIndex(), nextTop);
                    return withSelf(new StateChanged(next));
                }
                return InteractionResult.focus(FocusRequest.SELF);
            }
            if (!contentBounds.contains(point)) {
                return InteractionResult.focus(FocusRequest.SELF);
            }
            int itemIndex = state.firstVisibleIndex() + point.y() - contentBounds.y();
            if (itemIndex < 0 || itemIndex >= itemCount) {
                return InteractionResult.focus(FocusRequest.SELF);
            }
            State next = new State(itemIndex, state.firstVisibleIndex());
            return withSelf(new ItemPressed(itemIndex, next));
        }

        private InteractionResult<Action> withSelf(Action action) {
            return new InteractionResult<>(
                true,
                FocusRequest.SELF,
                Optional.of(Objects.requireNonNull(action, "action"))
            );
        }

        private State moveSelection(int delta) {
            if (itemCount == 0) {
                return state;
            }
            long candidate = (long) state.selectedIndex() + delta;
            int selected = (int) Math.max(0L, Math.min((long) itemCount - 1, candidate));
            return ensureVisible(new State(selected, state.firstVisibleIndex()));
        }

        private State select(int selectedIndex) {
            if (itemCount == 0) {
                return state;
            }
            int selected = Math.max(0, Math.min(itemCount - 1, selectedIndex));
            return ensureVisible(new State(selected, state.firstVisibleIndex()));
        }

        private State ensureVisible(State next) {
            int rows = Math.max(1, contentBounds.height());
            int first = next.firstVisibleIndex();
            if (next.selectedIndex() < first) {
                first = next.selectedIndex();
            } else if (next.selectedIndex() >= first + rows) {
                first = next.selectedIndex() - rows + 1;
            }
            return new State(next.selectedIndex(), clamp(first, 0, maxScroll(itemCount, rows)));
        }

        private State scrollBy(int delta) {
            int rows = Math.max(1, contentBounds.height());
            long candidate = (long) state.firstVisibleIndex() + delta;
            int first = (int) Math.max(0L, Math.min((long) maxScroll(itemCount, rows), candidate));
            return new State(state.selectedIndex(), first);
        }
    }

    private static State normalize(State state, int itemCount, int visibleRows) {
        if (itemCount == 0) {
            return State.initial();
        }
        int selected = Math.max(0, Math.min(itemCount - 1, state.selectedIndex()));
        int rows = Math.max(1, visibleRows);
        int first = Math.max(0, Math.min(maxScroll(itemCount, rows), state.firstVisibleIndex()));
        return new State(selected, first);
    }

    private static int maxScroll(int itemCount, int visibleRows) {
        return Math.max(0, itemCount - Math.max(1, visibleRows));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
