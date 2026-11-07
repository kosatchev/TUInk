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
import dev.tuiframe.text.TerminalLineLayout;
import dev.tuiframe.text.TerminalText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final int itemCount;
    private final IntFunction<String> itemText;
    private final State state;
    private final boolean selectable;
    private final int wheelStep;

    public ListView(int itemCount, IntFunction<String> itemText, State state) {
        this(itemCount, itemText, state, true, 1);
    }

    /** A read-only text viewport with scrolling but no selected or activated row. */
    public static ListView text(int itemCount, IntFunction<String> itemText, State state) {
        return new ListView(itemCount, itemText, state, false, 3);
    }

    /**
     * Snapshots logical lines for a word-wrapped, read-only text viewport.
     * Unlike a virtual list, this source measures every logical line once;
     * keep it while scrolling and relayout it when the viewport changes.
     */
    public static WrappedText wrappedText(List<String> lines) {
        return new WrappedText(lines);
    }

    /** Immutable logical text with a bounded cache of grapheme-safe visual rows. */
    public static final class WrappedText {
        private final List<TerminalText.Metrics> lines;
        private final Map<Integer, WrappedRows> cache = new LinkedHashMap<>();

        private WrappedText(List<String> lines) {
            this.lines = List.copyOf(Objects.requireNonNull(lines, "lines")).stream()
                .map(line -> TerminalText.metrics(line, 4))
                .toList();
        }

        public Placement layout(Rect bounds, State state) {
            return layout(bounds, state, null);
        }

        /**
         * Reflows at the available content width, including scrollbar space.
         * A previous placement from this source preserves the logical line
         * and grapheme at the top when the width changes. The supplied state
         * still takes precedence over the previous placement's scroll offset.
         */
        public Placement layout(Rect bounds, State state, Placement previous) {
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(state, "state");
            WrappedRows rows = rowsForWidth(bounds.width());
            ScrollLayout geometry = ScrollLayout.vertical(bounds, rows.text().size());
            if (geometry.contentBounds().width() != bounds.width()) {
                rows = rowsForWidth(geometry.contentBounds().width());
            }
            State reflowed = state;
            if (previous != null && previous.wrappedText == this) {
                if (previous.wrappedRows.width() != rows.width() && !previous.wrappedRows.text().isEmpty()) {
                    int oldRow = Math.min(state.firstVisibleIndex(), previous.wrappedRows.text().size() - 1);
                    TextPosition anchor = previous.wrappedRows.positions().get(oldRow);
                    reflowed = new State(-1, rows.rowAt(anchor));
                } else if (!previous.bounds().equals(bounds) && state.drag() != null) {
                    reflowed = new State(-1, state.firstVisibleIndex());
                }
            }
            Placement placement = ListView.text(rows.text().size(), rows.text()::get, reflowed).layout(bounds);
            return new Placement(
                placement.bounds, placement.contentBounds, placement.scrollbarBounds,
                placement.itemCount, placement.state, placement.visibleRows, false, 3, this, rows
            );
        }

        private synchronized WrappedRows rowsForWidth(int width) {
            int safeWidth = Math.max(1, width);
            WrappedRows existing = cache.get(safeWidth);
            if (existing != null) {
                return existing;
            }
            List<String> text = new ArrayList<>();
            List<TextPosition> positions = new ArrayList<>();
            for (int line = 0; line < lines.size(); line++) {
                TerminalText.Metrics metrics = lines.get(line);
                List<Integer> starts = TerminalLineLayout.wrapStarts(metrics, safeWidth, true);
                int grapheme = 0;
                for (int row = 0; row < starts.size(); row++) {
                    int start = starts.get(row);
                    int end = row + 1 < starts.size() ? starts.get(row + 1) : metrics.text().length();
                    positions.add(new TextPosition(line, start));
                    StringBuilder rendered = new StringBuilder();
                    while (grapheme < metrics.graphemeCount() && metrics.start(grapheme) < end) {
                        if (metrics.isTab(grapheme)) {
                            // Keep the logical line's tab stops after a wrap.
                            int advance = metrics.visualColumnAtBoundary(grapheme + 1)
                                - metrics.visualColumnAtBoundary(grapheme);
                            rendered.append(" ".repeat(advance));
                        } else if (metrics.width(grapheme) > safeWidth) {
                            // A two-cell glyph cannot fit a one-cell viewport.
                            // Preserve its source anchor and show a replacement.
                            rendered.append('\uFFFD');
                        } else {
                            // The expanded tab is no longer a grapheme boundary.
                            // Prepare orphan marks/formats as display cells before
                            // concatenation, just like CellText clipping and Canvas.
                            rendered.append(TerminalText.sanitizeGrapheme(
                                metrics.text().substring(metrics.start(grapheme), metrics.end(grapheme))));
                        }
                        grapheme++;
                    }
                    text.add(rendered.toString());
                }
            }
            WrappedRows wrapped = new WrappedRows(safeWidth, List.copyOf(text), List.copyOf(positions));
            if (cache.size() == 2) {
                cache.remove(cache.keySet().iterator().next());
            }
            cache.put(safeWidth, wrapped);
            return wrapped;
        }
    }

    private record TextPosition(int line, int offset) implements Comparable<TextPosition> {
        @Override
        public int compareTo(TextPosition other) {
            int lineOrder = Integer.compare(line, other.line);
            return lineOrder != 0 ? lineOrder : Integer.compare(offset, other.offset);
        }
    }

    private record WrappedRows(int width, List<String> text, List<TextPosition> positions) {
        private int rowAt(TextPosition anchor) {
            int low = 0;
            int high = positions.size();
            while (low < high) {
                int middle = low + (high - low) / 2;
                if (positions.get(middle).compareTo(anchor) <= 0) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return Math.max(0, low - 1);
        }
    }

    private ListView(int itemCount, IntFunction<String> itemText, State state, boolean selectable, int wheelStep) {
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must not be negative");
        }
        this.itemCount = itemCount;
        this.itemText = Objects.requireNonNull(itemText, "itemText");
        this.state = Objects.requireNonNull(state, "state");
        this.selectable = selectable;
        this.wheelStep = wheelStep;
    }

    @Override
    public Size measure(Size available) {
        return Objects.requireNonNull(available, "available");
    }

    @Override
    public Placement layout(Rect bounds) {
        Rect requiredBounds = Objects.requireNonNull(bounds, "bounds");
        ScrollLayout geometry = ScrollLayout.vertical(requiredBounds, itemCount);
        Rect contentBounds = geometry.contentBounds();
        int contentWidth = contentBounds.width();
        Rect scrollbarBounds = geometry.verticalScrollbarBounds();
        State normalized = normalize(state, itemCount, requiredBounds.height(), selectable);
        if (normalized.drag() != null && scrollbarBounds == null) {
            normalized = new State(normalized.selectedIndex(), normalized.firstVisibleIndex());
        }
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
            visibleRows,
            selectable,
            wheelStep
        );
    }

    /**
     * Application-owned selection and vertical viewport state. A selected
     * index of {@code -1} is a bootstrap/empty value; a non-empty selectable
     * placement normalizes it to the first item. A text viewport keeps it at
     * {@code -1}. Drag capture belongs to this state across placements.
     */
    public record State(int selectedIndex, int firstVisibleIndex, Scrollbar.Drag drag) {
        public State {
            if (selectedIndex < -1) {
                throw new IllegalArgumentException("selectedIndex must be -1 or greater");
            }
            if (firstVisibleIndex < 0) {
                throw new IllegalArgumentException("firstVisibleIndex must not be negative");
            }
            if (drag != null && drag.axis() != Scrollbar.Axis.VERTICAL) {
                throw new IllegalArgumentException("a list can only capture its vertical scrollbar");
            }
        }

        public State(int selectedIndex, int firstVisibleIndex) {
            this(selectedIndex, firstVisibleIndex, null);
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
    public static final class Placement implements PlacedPointerCapture<Action> {
        private final Rect bounds;
        private final Rect contentBounds;
        private final Rect scrollbarBounds;
        private final int itemCount;
        private final State state;
        private final String[] visibleRows;
        private final boolean selectable;
        private final int wheelStep;
        private final WrappedText wrappedText;
        private final WrappedRows wrappedRows;

        private Placement(
            Rect bounds,
            Rect contentBounds,
            Rect scrollbarBounds,
            int itemCount,
            State state,
            String[] visibleRows,
            boolean selectable,
            int wheelStep
        ) {
            this(bounds, contentBounds, scrollbarBounds, itemCount, state, visibleRows, selectable, wheelStep, null, null);
        }

        private Placement(
            Rect bounds,
            Rect contentBounds,
            Rect scrollbarBounds,
            int itemCount,
            State state,
            String[] visibleRows,
            boolean selectable,
            int wheelStep,
            WrappedText wrappedText,
            WrappedRows wrappedRows
        ) {
            this.bounds = bounds;
            this.contentBounds = contentBounds;
            this.scrollbarBounds = scrollbarBounds;
            this.itemCount = itemCount;
            this.state = state;
            this.visibleRows = visibleRows;
            this.selectable = selectable;
            this.wheelStep = wheelStep;
            this.wrappedText = wrappedText;
            this.wrappedRows = wrappedRows;
        }

        @Override
        public Rect bounds() {
            return bounds;
        }

        public State state() {
            return state;
        }

        /** Total visual rows, including continuation rows of wrapped text. */
        public int rowCount() {
            return itemCount;
        }

        public Rect contentBounds() {
            return contentBounds;
        }

        public Optional<Rect> scrollbarBounds() {
            return Optional.ofNullable(scrollbarBounds);
        }

        public Optional<Scrollbar> scrollbar() {
            return Optional.ofNullable(scrollbarBounds)
                .map(track -> Scrollbar.vertical(track, itemCount, state.firstVisibleIndex()));
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState widgetState) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(widgetState, "widgetState");
            if (bounds.isEmpty()) {
                return;
            }
            CellStyle inheritedStyle = theme.resolveStyle(WidgetThemeKeys.LIST_ITEM,
                theme.resolveStyle(WidgetThemeKeys.MODAL_BODY));
            CellStyle normalStyle = widgetState.enabled() ? inheritedStyle
                : theme.resolveStyle(WidgetThemeKeys.LIST_ITEM_DISABLED, inheritedStyle);
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
                scrollbar().ifPresent(bar -> bar.draw(canvas, theme.scrollStyle(normalStyle)));
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
        public boolean pointerCaptureActive() {
            return state.drag() != null;
        }

        @Override
        public InteractionResult<Action> cancelPointerCapture(PointerCaptureCancellation reason) {
            Objects.requireNonNull(reason, "reason");
            return (state.drag() == null ? InteractionResult.<Action>handled()
                : InteractionResult.<Action>output(new StateChanged(
                    new State(state.selectedIndex(), state.firstVisibleIndex()))))
                .withPointerCapture(PointerCaptureRequest.RELEASE);
        }

        @Override
        public InteractionResult<Action> handle(TuiInputEvent event, FocusContext context) {
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
            if (!context.focused() || !(event instanceof KeyStroke stroke)
                || !stroke.modifiers().isEmpty()) {
                return InteractionResult.ignored();
            }
            return handleKey(stroke.key());
        }

        private InteractionResult<Action> handleKey(KeyStroke.Key key) {
            if (!selectable) {
                return handleTextKey(key);
            }
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

        private InteractionResult<Action> handleTextKey(KeyStroke.Key key) {
            int rows = Math.max(1, contentBounds.height());
            int first = switch (key) {
                case ARROW_UP -> ScrollInteraction.scrollBy(state.firstVisibleIndex(), -1, rows, itemCount);
                case ARROW_DOWN -> ScrollInteraction.scrollBy(state.firstVisibleIndex(), 1, rows, itemCount);
                case PAGE_UP -> ScrollInteraction.scrollBy(state.firstVisibleIndex(), -rows, rows, itemCount);
                case PAGE_DOWN -> ScrollInteraction.scrollBy(state.firstVisibleIndex(), rows, rows, itemCount);
                case HOME -> 0;
                case END -> maxScroll(itemCount, rows);
                default -> -1;
            };
            if (first < 0) {
                return InteractionResult.ignored();
            }
            State next = new State(-1, first);
            return next.equals(state)
                ? InteractionResult.handled()
                : InteractionResult.output(new StateChanged(next));
        }

        private InteractionResult<Action> handlePointer(PointerEvent pointer, FocusContext context) {
            if (state.drag() != null) {
                if (pointer.kind() == PointerEvent.Kind.DRAG && pointer.button() == PointerEvent.Button.LEFT) {
                    Point point = new Point(
                        signedInt((long) pointer.column() - context.space().terminalOrigin().x()),
                        signedInt((long) pointer.row() - context.space().terminalOrigin().y())
                    );
                    int first = scrollbar().orElseThrow().drag(point, state.drag());
                    return InteractionResult.output(new StateChanged(
                        new State(state.selectedIndex(), first, state.drag())));
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
            if (pointer.kind() == PointerEvent.Kind.SCROLL_UP
                || pointer.kind() == PointerEvent.Kind.SCROLL_DOWN) {
                int delta = pointer.kind() == PointerEvent.Kind.SCROLL_UP ? -wheelStep : wheelStep;
                State next = scrollBy(delta);
                return next.equals(state)
                    ? InteractionResult.handled()
                    : InteractionResult.output(new StateChanged(next));
            }
            if (pointer.kind() != PointerEvent.Kind.PRESS
                || pointer.button() != PointerEvent.Button.LEFT) {
                return InteractionResult.ignored();
            }
            Optional<Scrollbar.Press> scrollbarPress = scrollbar().flatMap(bar -> bar.press(point));
            if (scrollbarPress.isPresent()) {
                Scrollbar.Press press = scrollbarPress.orElseThrow();
                return withSelf(new StateChanged(new State(state.selectedIndex(), press.offset(), press.drag())))
                    .withPointerCapture(press.drag() == null
                        ? PointerCaptureRequest.NONE : PointerCaptureRequest.ACQUIRE);
            }
            if (!contentBounds.contains(point)) {
                return InteractionResult.focus(FocusRequest.SELF);
            }
            if (!selectable) {
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
            int first = ScrollInteraction.scrollBy(state.firstVisibleIndex(), delta, rows, itemCount);
            return new State(state.selectedIndex(), first);
        }
    }

    private static State normalize(State state, int itemCount, int visibleRows, boolean selectable) {
        if (itemCount == 0) {
            return State.initial();
        }
        int selected = selectable ? Math.max(0, Math.min(itemCount - 1, state.selectedIndex())) : -1;
        int rows = Math.max(1, visibleRows);
        int first = Math.max(0, Math.min(maxScroll(itemCount, rows), state.firstVisibleIndex()));
        return new State(selected, first, state.drag());
    }

    private static int maxScroll(int itemCount, int visibleRows) {
        return Math.max(0, itemCount - Math.max(1, visibleRows));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static int signedInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }
}
