package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.PointerDragPolicy;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.text.TerminalPlainText;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Reusable palette header, captured reorder gesture and keyboard-accessible operations menu. */
public final class ToolPalette implements InteractiveWidget<ToolPalette.Change> {
    public enum Action { NONE, TOGGLE, MAXIMIZE, MOVE_UP, MOVE_DOWN, TALLER, SHORTER, WIDER, NARROWER, TOGGLE_HINTS, DRAG, DROP }
    private static final List<Action> MENU = List.of(Action.TOGGLE, Action.MAXIMIZE, Action.MOVE_UP, Action.MOVE_DOWN,
        Action.TALLER, Action.SHORTER, Action.WIDER, Action.NARROWER, Action.TOGGLE_HINTS);
    public record Press(PointerDragPolicy.Gesture gesture, Point current, boolean disclosure) {
        public Press {
            Objects.requireNonNull(gesture); Objects.requireNonNull(current);
        }
        public Point start() { return new Point(gesture.startColumn(), gesture.startRow()); }
        public long startedAt() { return gesture.pressedAt(); }
        private Press move(Point point, long now) {
            return new Press(gesture.move(point.y(), point.x(), now), point, disclosure);
        }
        private boolean waiting() { return !disclosure && gesture.waiting(); }
        private Press arm(long now) {
            var next = disclosure ? gesture : gesture.tick(now);
            return next == gesture ? this : new Press(next, current, disclosure);
        }
    }
    public record State(Press press, boolean menuOpen, int menuIndex) {
        public static State initial() { return new State(null, false, 0); }
        public State idle() { return new State(null, menuOpen, menuIndex); }
        public boolean pressed() { return press != null; }
        public boolean dragging() { return pressed() && !press.disclosure() && press.gesture().dragging(); }
        public int column() { return pressed() ? press.start().x() : 0; }
        public int row() { return pressed() ? press.start().y() : 0; }
        public long nanosUntilWakeup(long now) {
            return pressed() && press.waiting() ? press.gesture().nanosUntilWakeup(now) : -1L;
        }
        public Optional<Change> tick(long now) {
            if (!pressed()) return Optional.empty();
            var armed = press.arm(now);
            return armed == press ? Optional.empty() : Optional.of(new Change(Action.NONE,
                new State(armed, false, 0), armed.current().y(), armed.current().x()));
        }
    }
    public record Change(Action action, State state, int row, int column) {}
    private final String title;
    private final boolean expanded, maximized, hintsVisible;
    private final State state;
    private final Set<Action> disabled;

    public ToolPalette(String title, boolean expanded, boolean maximized, boolean hintsVisible, State state, Set<Action> disabled) {
        this.title = TerminalPlainText.singleLine(Objects.requireNonNull(title));
        this.expanded = expanded; this.maximized = maximized; this.hintsVisible = hintsVisible;
        this.state = Objects.requireNonNull(state); this.disabled = Set.copyOf(disabled);
    }
    @Override public Size measure(Size available) { return new Size(available.width(), Math.min(1, available.height())); }
    @Override public Placement layout(Rect bounds) { return layout(bounds, bounds); }
    public Placement layout(Rect bounds, Rect viewport) { return new Placement(bounds, viewport); }

    /** Reserve a help row only while enough content remains usable. Hiding it returns the row to content. */
    public static BodyLayout bodyLayout(Rect bounds, boolean hintsVisible, int minimumContentHeight) {
        Objects.requireNonNull(bounds);
        if (minimumContentHeight < 0) throw new IllegalArgumentException("negative minimum content height");
        int hintHeight = hintsVisible && bounds.height() > minimumContentHeight ? 1 : 0;
        int contentHeight = bounds.height() - hintHeight;
        return new BodyLayout(new Rect(bounds.x(), bounds.y(), bounds.width(), contentHeight),
            new Rect(bounds.x(), bounds.y() + contentHeight, bounds.width(), hintHeight));
    }
    public record BodyLayout(Rect contentBounds, Rect hintBounds) {
        public void paintHint(Canvas canvas, Theme theme, String text) {
            if (hintBounds.isEmpty()) return;
            new NoticeBanner(text, NoticeBanner.Severity.INFO, List.of())
                .place(this, hintBounds, hintBounds, false).paint(canvas, theme, WidgetState.NORMAL);
        }
    }
    public final class Placement implements PlacedPointerCapture<Change> {
        private final Rect bounds, menu;
        private final int firstMenuRow;
        private Placement(Rect allocation, Rect viewport) {
            bounds = new Rect(allocation.x(), allocation.y(), allocation.width(), Math.min(1, allocation.height()));
            menu = placeMenu(allocation, viewport);
            firstMenuRow = Math.max(0, Math.min(state.menuIndex() - menu.height() + 1, MENU.size() - menu.height()));
        }
        private Rect placeMenu(Rect allocation, Rect viewport) {
            if (!state.menuOpen() || viewport.isEmpty()) return new Rect(viewport.x(), viewport.y(), 0, 0);
            var owner = allocation.intersection(viewport);
            var body = new Rect(allocation.x(), bounds.bottomExclusive(), allocation.width(),
                Math.max(0, allocation.bottomExclusive() - bounds.bottomExclusive())).intersection(viewport);
            int readableWidth = 0;
            for (var action : MENU) readableWidth = Math.max(readableWidth, 3 + CellText.width(label(action)));
            int preferredWidth = Math.max(24, readableWidth);
            int width = Math.min(viewport.width(), owner.width() >= readableWidth ? Math.min(preferredWidth, owner.width()) : preferredWidth);
            var horizontal = owner.width() >= width ? owner : viewport;
            int anchorRight = bounds.width() >= 3 ? bounds.rightExclusive() - 1 : bounds.rightExclusive();
            int x = Math.max(horizontal.x(), Math.min(anchorRight - width, horizontal.rightExclusive() - width));
            int height = Math.min(MENU.size(), viewport.height());
            if (body.width() >= width && body.height() >= height) return new Rect(x, body.y(), width, height);
            int belowStart = Math.max(viewport.y(), Math.min(bounds.bottomExclusive(), viewport.bottomExclusive()));
            int aboveEnd = Math.max(viewport.y(), Math.min(bounds.y(), viewport.bottomExclusive()));
            int below = viewport.bottomExclusive() - belowStart, above = aboveEnd - viewport.y();
            if (below >= height) return new Rect(x, belowStart, width, height);
            if (above >= height) return new Rect(x, aboveEnd - height, width, height);
            // Prefer a shorter, keyboard-scrollable menu over covering its own header.
            if (Math.max(below, above) > 0) {
                height = Math.max(below, above);
                return new Rect(x, below >= above ? belowStart : aboveEnd - height, width, height);
            }
            return new Rect(x, viewport.y(), width, height);
        }
        @Override public Rect bounds() { return bounds; }
        public Rect menuBounds() { return menu; }
        public State state() { return state; }
        public boolean menuOpen() { return state.menuOpen(); }
        @Override public boolean pointerCaptureActive() { return state.pressed(); }
        @Override public InteractionResult<Change> cancelPointerCapture(PointerCaptureCancellation reason) {
            return change(Action.NONE, state.idle(), state.row()).withPointerCapture(PointerCaptureRequest.RELEASE);
        }
        @Override public void paint(Canvas canvas, Theme theme, WidgetState widget) {
            var style = ControlStyles.paletteHeader(theme, new WidgetState(widget.enabled()
                && (widget.focused() || state.pressed() || state.menuOpen()), widget.enabled()));
            canvas.fill(bounds, style);
            if (bounds.isEmpty()) return;
            CellText.drawClipped(canvas, bounds.x(), bounds.y(), (expanded ? "▾ " : "▸ ") + title + (maximized ? " [full]" : ""),
                style, Math.max(0, bounds.width() - 3));
            if (bounds.width() >= 3) CellText.drawClipped(canvas, bounds.rightExclusive() - 2, bounds.y(), "⋮", style, 1);
        }
        /** Paint after sibling bodies so the popup can extend beyond this palette's allocation. */
        public void paintMenu(Canvas canvas, Theme theme) {
            if (!state.menuOpen()) return;
            var normal = ControlStyles.popup(theme);
            canvas.fill(menu, normal);
            for (int line = 0; line < menu.height(); line++) {
                int index = firstMenuRow + line;
                Action action = MENU.get(index);
                var style = disabled.contains(action) ? theme.resolveStyle(WidgetThemeKeys.MENU_ITEM_DISABLED, normal)
                    : index == state.menuIndex() ? ControlStyles.popupSelected(theme) : normal;
                canvas.fill(new Rect(menu.x(), menu.y() + line, menu.width(), 1), style);
                CellText.drawClipped(canvas, menu.x(), menu.y() + line, (index == state.menuIndex() ? "> " : "  ") + label(action), style, menu.width());
            }
        }
        private String label(Action action) {
            return switch (action) {
                case TOGGLE -> expanded ? "Collapse" : "Expand";
                case MAXIMIZE -> maximized ? "Restore size" : "Maximize";
                case TOGGLE_HINTS -> hintsVisible ? "Hide hints" : "Show hints";
                case MOVE_UP -> "Move up"; case MOVE_DOWN -> "Move down";
                case TALLER -> "Taller"; case SHORTER -> "Shorter";
                case WIDER -> "Wider"; case NARROWER -> "Narrower";
                default -> "";
            };
        }
        private InteractionResult<Change> change(Action action, State next, int row) {
            return InteractionResult.output(new Change(action, next, row, state.column()));
        }
        private InteractionResult<Change> openMenu() {
            return change(Action.NONE, new State(null, true, 0), 0);
        }
        private InteractionResult<Change> activate(Action action) {
            return change(disabled.contains(action) ? Action.NONE : action, State.initial(), 0);
        }
        @Override public InteractionResult<Change> handle(TuiInputEvent event, FocusContext context) {
            return handle(event, context, System.nanoTime());
        }
        public InteractionResult<Change> handle(TuiInputEvent event, FocusContext context, long now) {
            if (!context.enabled()) return InteractionResult.ignored();
            if (event instanceof TextInput text && " ".equals(text.text())) event = KeyStroke.of(KeyStroke.Key.SPACE);
            if (event instanceof PointerEvent pointer) {
                Point point = new Point(pointer.column() - context.space().terminalOrigin().x(), pointer.row() - context.space().terminalOrigin().y());
                if (state.pressed()) {
                    if (pointer.kind() == PointerEvent.Kind.RELEASE) {
                        var completed = state.press().move(point, now);
                        // Activating a compressed palette can move its header between press and release.
                        boolean samePress = point.x() == state.column() && point.y() == state.row();
                        boolean click = !completed.gesture().cancelled() && !completed.gesture().dragging()
                            && (completed.disclosure() || !completed.gesture().armed());
                        Action action = !completed.disclosure() && completed.gesture().dragging() ? Action.DROP
                            : click && (bounds.contains(point) || samePress) ? Action.TOGGLE : Action.NONE;
                        return InteractionResult.output(new Change(action, State.initial(), point.y(), point.x()))
                            .withPointerCapture(PointerCaptureRequest.RELEASE);
                    }
                    if (pointer.kind() == PointerEvent.Kind.DRAG && pointer.button() == PointerEvent.Button.LEFT) {
                        var next = new State(state.press().move(point, now), false, 0);
                        return InteractionResult.output(new Change(next.dragging() ? Action.DRAG : Action.NONE,
                            next, point.y(), point.x()));
                    }
                    return InteractionResult.handled();
                }
                if (state.menuOpen()) {
                    if (pointer.kind() == PointerEvent.Kind.SCROLL_DOWN || pointer.kind() == PointerEvent.Kind.SCROLL_UP) {
                        int index = Math.floorMod(state.menuIndex() + (pointer.kind() == PointerEvent.Kind.SCROLL_DOWN ? 1 : -1), MENU.size());
                        return change(Action.NONE, new State(null, true, index), 0);
                    }
                    if (pointer.kind() != PointerEvent.Kind.PRESS) return InteractionResult.handled();
                    if (menu.contains(point) && pointer.button() == PointerEvent.Button.LEFT)
                        return activate(MENU.get(firstMenuRow + point.y() - menu.y()));
                    return change(Action.NONE, State.initial(), 0);
                }
                if (!bounds.contains(point)) return InteractionResult.ignored();
                if (pointer.kind() == PointerEvent.Kind.PRESS && pointer.modifiers().isEmpty()) {
                    if (pointer.button() == PointerEvent.Button.RIGHT
                        || pointer.button() == PointerEvent.Button.LEFT && point.x() >= bounds.rightExclusive() - 3) return openMenu();
                    if (pointer.button() == PointerEvent.Button.LEFT)
                        return change(Action.NONE, new State(new Press(PointerDragPolicy.Gesture.begin(point.y(), point.x(), now), point,
                            point.x() < bounds.x() + 2), false, 0), point.y())
                            .withPointerCapture(PointerCaptureRequest.ACQUIRE);
                }
                return InteractionResult.handled();
            }
            if (!context.focused() || !(event instanceof KeyStroke key)) return InteractionResult.ignored();
            if (state.menuOpen()) {
                if (key.key() == KeyStroke.Key.ESCAPE) return change(Action.NONE, State.initial(), 0);
                if (key.key() == KeyStroke.Key.ENTER || key.key() == KeyStroke.Key.SPACE) return activate(MENU.get(state.menuIndex()));
                int index = switch (key.key()) {
                    case ARROW_UP -> Math.floorMod(state.menuIndex() - 1, MENU.size());
                    case ARROW_DOWN -> (state.menuIndex() + 1) % MENU.size();
                    case HOME -> 0; case END -> MENU.size() - 1; default -> state.menuIndex();
                };
                return change(Action.NONE, new State(null, true, index), 0);
            }
            if (key.modifiers().equals(Set.of(KeyStroke.Modifier.ALT))) {
                if (key.key() == KeyStroke.Key.ARROW_UP) return activate(Action.MOVE_UP);
                if (key.key() == KeyStroke.Key.ARROW_DOWN) return activate(Action.MOVE_DOWN);
            }
            if (key.modifiers().equals(Set.of(KeyStroke.Modifier.CTRL))) {
                Action action = switch (key.key()) {
                    case ARROW_UP -> Action.SHORTER; case ARROW_DOWN -> Action.TALLER;
                    case ARROW_LEFT -> Action.NARROWER; case ARROW_RIGHT -> Action.WIDER; default -> Action.NONE;
                };
                if (action != Action.NONE) return activate(action);
            }
            if (!key.modifiers().isEmpty()) return InteractionResult.ignored();
            return switch (key.key()) {
                case SPACE -> openMenu();
                case ENTER -> activate(Action.TOGGLE);
                case ARROW_LEFT -> expanded ? activate(Action.TOGGLE) : InteractionResult.handled();
                case ARROW_RIGHT -> !expanded ? activate(Action.TOGGLE) : InteractionResult.ignored();
                default -> InteractionResult.ignored();
            };
        }
    }
}
