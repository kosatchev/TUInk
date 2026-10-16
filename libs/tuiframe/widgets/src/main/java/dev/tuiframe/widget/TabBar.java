package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immediate-mode, single-row tab bar with an application-owned item viewport.
 *
 * <p>The application owns the active item and {@link State}. Items contain
 * presentation text only: file paths, dirty/search markers, close policy, and
 * application commands remain outside this widget. Layout returns an
 * immutable exact-frame snapshot used by both painting and pointer routing.</p>
 */
public final class TabBar implements InteractiveWidget<TabBar.Action> {
    private static final int TAB_WIDTH = 4;
    private static final String LEFT_ARROW = "<";
    private static final String RIGHT_ARROW = ">";
    private static final String OPEN = "[";
    private static final String CLOSE = "]";
    private static final String CLOSE_SUFFIX = " x]";
    private static final int SCROLL_CONTROL_COUNT = 2;
    private static final int MIN_WIDTH_WITH_SCROLL_CONTROLS = 3;

    private final List<Item> items;
    private final int activeIndex;
    private final State state;

    public TabBar(List<Item> items, int activeIndex, State state) {
        this.items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (activeIndex < -1 || activeIndex >= this.items.size()) {
            throw new IllegalArgumentException("activeIndex must identify an item or be -1");
        }
        this.activeIndex = activeIndex;
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public Size measure(Size available) {
        Size required = Objects.requireNonNull(available, "available");
        return new Size(required.width(), Math.min(1, required.height()));
    }

    @Override
    public Placement layout(Rect bounds) {
        return layout(bounds, false);
    }

    /**
     * Reveals the active item with the smallest viewport shift needed. Call when
     * the active item or available width changes; ordinary layout preserves a
     * viewport that the user scrolled deliberately.
     */
    public Placement layoutRevealingActive(Rect bounds) {
        return layout(bounds, true);
    }

    /**
     * Keeps a previously visible active item visible when labels change. A
     * changed viewport state takes precedence, so manually browsing away from
     * the active item is preserved. Activation and resize can still request
     * an unconditional reveal with {@link #layoutRevealingActive(Rect)}.
     */
    public Placement layoutKeepingActiveVisible(Rect bounds, Placement previous) {
        Objects.requireNonNull(previous, "previous");
        Placement placement = layout(bounds);
        if (previous.activeIndex == activeIndex && previous.activeItemVisible()
            && previous.state.equals(normalize(state, items.size()))
            && !placement.activeItemVisible()) {
            return layoutRevealingActive(bounds);
        }
        return placement;
    }

    private Placement layout(Rect bounds, boolean revealActive) {
        Rect requested = Objects.requireNonNull(bounds, "bounds");
        Rect barBounds = requested.height() == 0
            ? new Rect(requested.x(), requested.y(), requested.width(), 0)
            : new Rect(requested.x(), requested.y(), requested.width(), 1);
        State normalized = normalize(state, items.size());
        if (barBounds.isEmpty() || items.isEmpty()) {
            return new Placement(
                barBounds,
                items.size(),
                activeIndex,
                normalized,
                List.of(),
                null,
                null,
                false,
                false
            );
        }

        Pack fullWidth = pack(0, barBounds.x(), barBounds.width(), false);
        boolean allItemsFit = fullWidth.nextItemIndex() == items.size();
        if (allItemsFit) {
            State reset = State.initial();
            return new Placement(
                barBounds,
                items.size(),
                activeIndex,
                reset,
                fullWidth.items(),
                null,
                null,
                false,
                false
            );
        }

        boolean reserveControls = items.size() > 1
            && barBounds.width() >= MIN_WIDTH_WITH_SCROLL_CONTROLS;
        int contentOffset = reserveControls ? 1 : 0;
        int contentWidth = barBounds.width() - (reserveControls ? SCROLL_CONTROL_COUNT : 0);
        int startX = Math.addExact(barBounds.x(), contentOffset);
        if (revealActive && activeIndex >= 0) {
            normalized = revealActive(normalized, startX, contentWidth);
        }
        Pack visible = pack(
            normalized.firstVisibleIndex(),
            startX,
            contentWidth,
            true
        );
        boolean canScrollLeft = normalized.firstVisibleIndex() > 0;
        boolean canScrollRight = visible.nextItemIndex() < items.size();
        Point leftControl = reserveControls && canScrollLeft
            ? new Point(barBounds.x(), barBounds.y())
            : null;
        Point rightControl = reserveControls && canScrollRight
            ? new Point(barBounds.rightExclusive() - 1, barBounds.y())
            : null;
        return new Placement(
            barBounds,
            items.size(),
            activeIndex,
            normalized,
            visible.items(),
            leftControl,
            rightControl,
            canScrollLeft,
            canScrollRight
        );
    }

    private State revealActive(State normalized, int startX, int contentWidth) {
        int first = normalized.firstVisibleIndex();
        if (activeIndex < first) {
            return new State(activeIndex);
        }
        if (pack(first, startX, contentWidth, true).nextItemIndex() > activeIndex) {
            return normalized;
        }
        int last = activeIndex;
        while (first < last) {
            int candidate = first + (last - first) / 2;
            if (pack(candidate, startX, contentWidth, true).nextItemIndex() > activeIndex) {
                last = candidate;
            } else {
                first = candidate + 1;
            }
        }
        return new State(first);
    }

    private Pack pack(int firstIndex, int startX, int maxWidth, boolean includeTruncatedFirst) {
        if (maxWidth <= 0 || firstIndex >= items.size()) {
            return new Pack(List.of(), firstIndex);
        }
        List<PreparedItem> visible = new ArrayList<>();
        int used = 0;
        int itemIndex = firstIndex;
        while (itemIndex < items.size()) {
            int separatorWidth = visible.isEmpty() ? 0 : 1;
            int remaining = maxWidth - used - separatorWidth;
            if (remaining <= 0) {
                break;
            }
            PreparedText prepared = prepare(items.get(itemIndex), remaining);
            if (!prepared.complete()) {
                if (visible.isEmpty() && includeTruncatedFirst && prepared.width() > 0) {
                    visible.add(
                        new PreparedItem(
                            itemIndex,
                            startX,
                            prepared.text(),
                            prepared.width(),
                            prepared.closeOffset()
                        )
                    );
                    itemIndex++;
                }
                break;
            }
            used += separatorWidth;
            int itemX = Math.addExact(startX, used);
            visible.add(
                new PreparedItem(
                    itemIndex,
                    itemX,
                    prepared.text(),
                    prepared.width(),
                    prepared.closeOffset()
                )
            );
            used += prepared.width();
            itemIndex++;
        }
        return new Pack(List.copyOf(visible), itemIndex);
    }

    private static PreparedText prepare(Item item, int maxWidth) {
        if (maxWidth <= 0) {
            return PreparedText.EMPTY;
        }
        boolean hasAffixes = !item.prefix().isEmpty() || !item.suffix().isEmpty();
        CellText.FittedText fittedPrefix = CellText.fitMiddle(item.prefix(), maxWidth, 0, OPEN.length());
        CellText.FittedText fittedSuffix = CellText.fitMiddle(item.suffix(), maxWidth, 0, 0);
        String prefix = fittedPrefix.text();
        String suffix = fittedSuffix.text();
        int affixWidth = CellText.width(prefix) + CellText.width(suffix);
        boolean showClose = item.closable();
        if (hasAffixes && maxWidth < OPEN.length() + CLOSE_SUFFIX.length() + affixWidth + 1) {
            showClose = false;
        }
        int suffixWidth = showClose ? CLOSE_SUFFIX.length() : CLOSE.length();
        int chromeWidth = OPEN.length() + suffixWidth;
        if (maxWidth < chromeWidth) {
            return hasAffixes ? compactAffixes(prefix, suffix, maxWidth) : compact(item.closable(), maxWidth);
        }
        if (hasAffixes && maxWidth < chromeWidth + affixWidth) {
            return compactAffixes(prefix, suffix, maxWidth);
        }
        int labelCapacity = maxWidth - chromeWidth - affixWidth;
        CellText.FittedText label = item.labelPolicy().fitMiddle(item.label(), labelCapacity, item.preferredTailCells(),
            OPEN.length() + CellText.width(prefix));
        boolean complete = label.complete() && fittedPrefix.complete() && fittedSuffix.complete()
            && (!item.closable() || showClose);
        String rendered = OPEN + prefix + label.text() + suffix + (showClose ? CLOSE_SUFFIX : CLOSE);
        int width = TerminalText.visualWidth(rendered, TAB_WIDTH);
        int closeOffset = showClose ? width - 2 : -1;
        return new PreparedText(rendered, width, closeOffset, complete);
    }

    private static PreparedText compactAffixes(String prefix, String suffix, int maxWidth) {
        // Trailing affixes have priority when the viewport cannot hold both.
        String trailing = CellText.ellipsizeMiddle(suffix, maxWidth, 0);
        String leading = CellText.ellipsizeMiddle(prefix, maxWidth - CellText.width(trailing), 0);
        String text = leading + trailing;
        return new PreparedText(text, CellText.width(text), -1, false);
    }

    private static PreparedText compact(boolean closable, int maxWidth) {
        String text;
        int closeOffset = -1;
        if (closable && maxWidth >= 3) {
            text = "[x]";
            closeOffset = 1;
        } else if (maxWidth >= 2) {
            text = "[]";
        } else {
            text = OPEN;
        }
        return new PreparedText(text, text.length(), closeOffset, false);
    }

    private static State normalize(State state, int itemCount) {
        if (itemCount == 0) {
            return State.initial();
        }
        return new State(Math.min(state.firstVisibleIndex(), itemCount - 1));
    }

    /**
     * Generic presentation. Affixes remain outside the shortened label. The tail preference
     * reserves trailing label cells where possible, without interpreting their content.
     */
    public record Item(String label, boolean closable, String prefix, String suffix, int preferredTailCells,
                       LabelPolicy labelPolicy) {
        public Item(String label, boolean closable, String prefix, String suffix, int preferredTailCells) {
            this(label, closable, prefix, suffix, preferredTailCells, LabelPolicy.DEFAULT);
        }

        public Item(String label, boolean closable) {
            this(label, closable, "", "", 0);
        }

        public Item(String label, boolean closable, String prefix, String suffix) {
            this(label, closable, prefix, suffix, 0);
        }

        public Item {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(suffix, "suffix");
            Objects.requireNonNull(labelPolicy, "labelPolicy");
            if (preferredTailCells < 0) throw new IllegalArgumentException("Tail preference must be non-negative");
        }
    }

    /** Application-owned first visible item. Stale values are clamped during layout. */
    public record State(int firstVisibleIndex) {
        public State {
            if (firstVisibleIndex < 0) {
                throw new IllegalArgumentException("firstVisibleIndex must not be negative");
            }
        }

        public static State initial() {
            return new State(0);
        }
    }

    /** Semantic tab-bar actions independent of application commands and close policy. */
    public sealed interface Action permits StateChanged, TabPressed, ClosePressed {
    }

    /** Pointer scrolling produced a new application-owned viewport state. */
    public record StateChanged(State state) implements Action {
        public StateChanged {
            Objects.requireNonNull(state, "state");
        }
    }

    /** A left pointer press hit a tab body. */
    public record TabPressed(int tabIndex, State state) implements Action {
        public TabPressed {
            if (tabIndex < 0) {
                throw new IllegalArgumentException("tabIndex must not be negative");
            }
            Objects.requireNonNull(state, "state");
        }
    }

    /** A left pointer press hit a rendered close cell. */
    public record ClosePressed(int tabIndex, State state) implements Action {
        public ClosePressed {
            if (tabIndex < 0) {
                throw new IllegalArgumentException("tabIndex must not be negative");
            }
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * Immutable exact-frame placement. Rebuild after items, active index, state,
     * or bounds change; an older placement intentionally keeps its old geometry.
     */
    public static final class Placement implements PlacedInteractiveWidget<Action> {
        private final Rect bounds;
        private final int itemCount;
        private final int activeIndex;
        private final State state;
        private final List<PreparedItem> items;
        private final Point leftControl;
        private final Point rightControl;
        private final boolean canScrollLeft;
        private final boolean canScrollRight;

        private Placement(
            Rect bounds,
            int itemCount,
            int activeIndex,
            State state,
            List<PreparedItem> items,
            Point leftControl,
            Point rightControl,
            boolean canScrollLeft,
            boolean canScrollRight
        ) {
            this.bounds = bounds;
            this.itemCount = itemCount;
            this.activeIndex = activeIndex;
            this.state = state;
            this.items = items;
            this.leftControl = leftControl;
            this.rightControl = rightControl;
            this.canScrollLeft = canScrollLeft;
            this.canScrollRight = canScrollRight;
        }

        @Override
        public Rect bounds() {
            return bounds;
        }

        public State state() {
            return state;
        }

        private boolean activeItemVisible() {
            for (PreparedItem item : items) {
                if (item.index() == activeIndex) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState widgetState) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(widgetState, "widgetState");
            if (bounds.isEmpty()) {
                return;
            }
            CellStyle barStyle = theme.resolveStyle(WidgetThemeKeys.TAB_BAR);
            CellStyle itemStyle = widgetState.enabled()
                ? theme.resolveStyle(WidgetThemeKeys.TAB_ITEM, barStyle)
                : theme.resolveStyle(WidgetThemeKeys.TAB_ITEM_DISABLED, barStyle);
            CellStyle activeStyle = widgetState.enabled()
                ? theme.resolveStyle(WidgetThemeKeys.TAB_ITEM_ACTIVE, itemStyle)
                : itemStyle;
            int mark = canvas.save();
            try {
                if (!canvas.clip(bounds)) {
                    return;
                }
                Cell blank = new Cell(" ", barStyle);
                canvas.fillCell(bounds, blank);
                if (leftControl != null) {
                    canvas.put(leftControl.x(), leftControl.y(), LEFT_ARROW, barStyle);
                }
                if (rightControl != null) {
                    canvas.put(rightControl.x(), rightControl.y(), RIGHT_ARROW, barStyle);
                }
                for (PreparedItem item : items) {
                    CellStyle style = item.index() == activeIndex ? activeStyle : itemStyle;
                    CellText.drawClipped(
                        canvas,
                        item.x(),
                        bounds.y(),
                        item.text(),
                        style,
                        item.width()
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
            if (!context.enabled() || bounds.isEmpty() || itemCount == 0
                || !(event instanceof PointerEvent pointer)) {
                return InteractionResult.ignored();
            }
            if (!pointer.modifiers().isEmpty()) {
                return InteractionResult.ignored();
            }
            Point local = context.localPoint(pointer).orElse(null);
            if (local == null || !bounds.contains(local)) {
                return InteractionResult.ignored();
            }
            int scrollDirection = scrollDirection(pointer.kind());
            if (scrollDirection != 0) {
                boolean canScroll = scrollDirection < 0 ? canScrollLeft : canScrollRight;
                if (!canScroll) {
                    return InteractionResult.handled();
                }
                State next = scrollDirection < 0 ? previousState() : nextState();
                return InteractionResult.output(new StateChanged(next));
            }
            if (pointer.kind() != PointerEvent.Kind.PRESS
                || pointer.button() != PointerEvent.Button.LEFT) {
                return InteractionResult.ignored();
            }
            if (leftControl != null && leftControl.equals(local)) {
                return InteractionResult.output(new StateChanged(previousState()));
            }
            if (rightControl != null && rightControl.equals(local)) {
                return InteractionResult.output(new StateChanged(nextState()));
            }
            for (PreparedItem item : items) {
                if (!item.contains(local)) {
                    continue;
                }
                return item.isClose(local)
                    ? InteractionResult.output(new ClosePressed(item.index(), state))
                    : InteractionResult.output(new TabPressed(item.index(), state));
            }
            return InteractionResult.ignored();
        }

        private State previousState() {
            return new State(Math.max(0, state.firstVisibleIndex() - 1));
        }

        private State nextState() {
            return new State(Math.min(itemCount - 1, state.firstVisibleIndex() + 1));
        }

        private static int scrollDirection(PointerEvent.Kind kind) {
            return switch (kind) {
                case SCROLL_UP, SCROLL_LEFT -> -1;
                case SCROLL_DOWN, SCROLL_RIGHT -> 1;
                default -> 0;
            };
        }
    }

    private record Pack(List<PreparedItem> items, int nextItemIndex) {
    }

    private record PreparedText(
        String text,
        int width,
        int closeOffset,
        boolean complete
    ) {
        private static final PreparedText EMPTY = new PreparedText("", 0, -1, false);
    }

    private record PreparedItem(
        int index,
        int x,
        String text,
        int width,
        int closeOffset
    ) {
        private boolean contains(Point point) {
            long right = (long) x + width;
            return point.x() >= x
                && point.x() < right;
        }

        private boolean isClose(Point point) {
            return closeOffset >= 0 && point.x() == (long) x + closeOffset;
        }
    }
}
