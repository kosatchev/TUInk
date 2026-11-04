package dev.tuink.render.surface;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.scroll.ScrollLayout;
import dev.tuiframe.scroll.Scrollbar;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * One pointer gesture state machine for terminal text surfaces. Placements and
 * geometry are borrowed for one call and never retained across frames or retirement.
 * Results are intents: the presentation commits selection through its own semantic owner.
 */
public final class TextSurfacePointer<P> {
    public enum Action { NONE, BEGIN, EXTEND, WORD, FINISH, SCROLL }
    public record Policy(boolean wordSelection, boolean edgeScroll, boolean clipDragToViewport,
                         boolean horizontalRepeat, int maxEdgeStep, long repeatNanos, long doubleClickNanos) {
        public static Policy source(boolean wrap) { return new Policy(true, true, true, !wrap, 6, 50_000_000L, 450_000_000L); }
        public static Policy preview() { return new Policy(false, false, false, false, 6, 50_000_000L, 450_000_000L); }
    }
    public record Placement<P>(Rect bounds, ScrollLayout layout, Scrollbar vertical, Scrollbar horizontal,
                               TextSurfaceGeometry<P> geometry, TextSurfaceInteraction.Viewport viewport,
                               int totalRows, int totalColumns, Policy policy) {
        public Placement {
            Objects.requireNonNull(bounds); Objects.requireNonNull(layout); Objects.requireNonNull(geometry);
            Objects.requireNonNull(viewport); Objects.requireNonNull(policy);
        }
    }
    public record Result<P>(boolean consumed, Action action, P target, TextSurfaceInteraction.Visual visual,
                            TextSurfaceInteraction.Viewport viewport, Scrollbar.Drag drag) {}

    private final BiPredicate<P, P> adjacentClick;
    private boolean selecting;
    private P lastClick;
    private long lastClickNanos;
    private Scrollbar.Drag scrollDrag;
    private PointerEvent repeatPointer;
    private long repeatAtNanos;

    public TextSurfacePointer(BiPredicate<P, P> adjacentClick) { this.adjacentClick = Objects.requireNonNull(adjacentClick); }
    public boolean selecting() { return selecting; }
    public boolean capturing() { return selecting || scrollDrag != null; }
    public void cancel() { selecting = false; scrollDrag = null; repeatPointer = null; lastClick = null; lastClickNanos = 0; }
    public boolean finishSelection() {
        boolean wasSelecting = selecting;
        selecting = false; scrollDrag = null; repeatPointer = null;
        return wasSelecting;
    }
    public long nanosUntilRepeat(long now) { return repeatPointer == null ? -1 : Math.max(0L, repeatAtNanos - now); }
    public PointerEvent repeatIfDue(long now) { return repeatPointer == null || now - repeatAtNanos < 0 ? null : repeatPointer; }

    public Result<P> handle(Placement<P> placement, PointerEvent event, boolean extend, long now) {
        boolean captured = capturing();
        boolean inside = placement.bounds().contains(event.column(), event.row());
        if (event.kind() == PointerEvent.Kind.RELEASE) {
            boolean finished = finishSelection();
            return result(placement, captured || inside, finished ? Action.FINISH : Action.NONE, null, null, placement.viewport());
        }
        boolean dragging = event.button() == PointerEvent.Button.LEFT
            && (event.kind() == PointerEvent.Kind.DRAG || event.kind() == PointerEvent.Kind.MOVE);
        if (!inside && !(dragging && captured)) {
            if (event.kind() == PointerEvent.Kind.PRESS) cancel();
            return result(placement, false, Action.NONE, null, null, placement.viewport());
        }
        switch (event.kind()) {
            case SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT -> {
                int rows = event.kind() == PointerEvent.Kind.SCROLL_UP ? -3 : event.kind() == PointerEvent.Kind.SCROLL_DOWN ? 3 : 0;
                int columns = event.kind() == PointerEvent.Kind.SCROLL_LEFT ? -3 : event.kind() == PointerEvent.Kind.SCROLL_RIGHT ? 3 : 0;
                var next = TextSurfaceInteraction.scrollBy(placement.viewport(), placement.totalRows(), placement.totalColumns(), rows, columns);
                return result(placement, true, Action.SCROLL, null, null, next);
            }
            case PRESS -> {
                selecting = false; scrollDrag = null; repeatPointer = null;
                if (event.button() != PointerEvent.Button.LEFT) return result(placement, true, Action.NONE, null, null, placement.viewport());
                var scrollbar = scrollbar(placement, new Point(event.column(), event.row()), null);
                if (scrollbar.consumed()) { lastClick = null; return scrollbar; }
                var hit = hit(placement, event, placement.viewport(), false);
                if (hit == null) { lastClick = null; return result(placement, true, Action.NONE, null, null, placement.viewport()); }
                P target = placement.geometry().positionAt(hit);
                selecting = true;
                Action action = Action.BEGIN;
                if (extend) { action = Action.EXTEND; lastClick = null; lastClickNanos = 0; }
                else {
                    if (placement.policy().wordSelection() && lastClick != null && adjacentClick.test(lastClick, target)
                        && now - lastClickNanos <= placement.policy().doubleClickNanos()) action = Action.WORD;
                    lastClick = target; lastClickNanos = now;
                }
                return result(placement, true, action, target, hit, placement.viewport());
            }
            case DRAG, MOVE -> {
                if (dragging && scrollDrag != null) return scrollbar(placement, new Point(event.column(), event.row()), scrollDrag);
                if (dragging && selecting) {
                    var next = placement.viewport();
                    Rect content = placement.layout().contentBounds();
                    if (placement.policy().edgeScroll() && !content.isEmpty()) {
                        int delta = edgeDelta(event.row(), content.y(), content.bottomExclusive() - 1, placement.policy().maxEdgeStep());
                        next = TextSurfaceInteraction.scrollBy(next, placement.totalRows(), placement.totalColumns(), delta, 0);
                    }
                    var hit = hit(placement, event, next, true);
                    if (hit != null) return result(placement, true, Action.EXTEND, placement.geometry().positionAt(hit), hit, next);
                }
            }
            default -> { }
        }
        return result(placement, true, Action.NONE, null, null, placement.viewport());
    }

    /** Shared scrollbar classifier; external capture adapters may restore the grab offset explicitly. */
    public Result<P> scrollbar(Placement<P> placement, Point point, Scrollbar.Drag drag) {
        for (Scrollbar bar : new Scrollbar[] { placement.vertical(), placement.horizontal() }) {
            if (bar == null || drag != null && bar.axis() != drag.axis()) continue;
            Scrollbar.Press press = drag == null ? bar.press(point).orElse(null)
                : new Scrollbar.Press(bar.drag(point, drag), drag);
            if (press == null) continue;
            scrollDrag = press.drag(); selecting = false; repeatPointer = null;
            var current = placement.viewport();
            var target = TextSurfaceInteraction.clamp(new TextSurfaceInteraction.Viewport(
                bar.axis() == Scrollbar.Axis.VERTICAL ? press.offset() : current.top(),
                bar.axis() == Scrollbar.Axis.HORIZONTAL ? press.offset() : current.left(), current.rows(), current.columns()),
                placement.totalRows(), placement.totalColumns());
            return result(placement, true, Action.SCROLL, null, null, target);
        }
        scrollDrag = null;
        return result(placement, false, Action.NONE, null, null, placement.viewport());
    }

    /** Repetition is admitted after the adapter observes the real semantic/viewport commit result. */
    public void afterDrag(Placement<P> placement, PointerEvent event, boolean changed, long now) {
        Rect content = placement.layout().contentBounds();
        boolean verticalEdge = event.row() <= content.y() || event.row() >= content.bottomExclusive() - 1;
        boolean horizontalEdge = placement.policy().horizontalRepeat()
            && (event.column() <= content.x() || event.column() >= content.rightExclusive() - 1);
        repeatPointer = selecting && changed && placement.policy().edgeScroll() && !content.isEmpty()
            && (verticalEdge || horizontalEdge) ? event : null;
        repeatAtNanos = now + placement.policy().repeatNanos();
    }

    private TextSurfaceInteraction.Visual hit(Placement<P> placement, PointerEvent event,
                                               TextSurfaceInteraction.Viewport viewport, boolean dragging) {
        Rect content = placement.layout().contentBounds();
        if (content.isEmpty()) return null;
        long row = (long) event.row() - content.y();
        if (dragging && placement.policy().clipDragToViewport()) row = Math.clamp(row, 0L, content.height() - 1L);
        if (!dragging && (row < 0 || row >= content.height())) return null;
        if (dragging && !placement.policy().clipDragToViewport()) {
            int absolute = (int) Math.clamp((long) viewport.top() + row, 0L, Math.max(0L, (long) placement.totalRows() - 1));
            int column = (int) Math.clamp((long) event.column() - content.x() + viewport.left(), 0L, Integer.MAX_VALUE);
            return new TextSurfaceInteraction.Visual(absolute, column);
        }
        int column = (int) Math.clamp((long) event.column() - content.x(), 0L, Integer.MAX_VALUE);
        return TextSurfaceInteraction.hit(viewport, (int) row, column);
    }
    private static int edgeDelta(int at, int top, int bottom, int maxStep) {
        if (at < top) return -(int) Math.min(maxStep, Math.max(1L, (long) top - at));
        if (at > bottom) return (int) Math.min(maxStep, Math.max(1L, (long) at - bottom));
        return at == top ? -1 : at == bottom ? 1 : 0;
    }
    private Result<P> result(Placement<P> placement, boolean consumed, Action action, P target,
                             TextSurfaceInteraction.Visual visual, TextSurfaceInteraction.Viewport viewport) {
        return new Result<>(consumed, action, target, visual, viewport, scrollDrag);
    }
}
