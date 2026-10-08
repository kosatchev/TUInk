package dev.tuiframe.scroll;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import java.util.Objects;
import java.util.Optional;

/** Exact-frame scrollbar shared by painters and pointer handlers. */
public record Scrollbar(Axis axis, Rect track, int totalItems, int offset) {
    public enum Axis { VERTICAL, HORIZONTAL }

    public Scrollbar {
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(track, "track");
        if (axis == Axis.VERTICAL ? track.width() != 1 : track.height() != 1) {
            throw new IllegalArgumentException("scrollbar must occupy a one-cell-wide track");
        }
        if (totalItems < 0) {
            throw new IllegalArgumentException("totalItems must not be negative");
        }
        int visible = axis == Axis.VERTICAL ? track.height() : track.width();
        offset = ScrollInteraction.clampOffset(offset, visible, totalItems);
    }

    public static Scrollbar vertical(Rect track, int totalItems, int offset) {
        return new Scrollbar(Axis.VERTICAL, track, totalItems, offset);
    }

    public static Scrollbar horizontal(Rect track, int totalItems, int offset) {
        return new Scrollbar(Axis.HORIZONTAL, track, totalItems, offset);
    }

    /** Captured pointer input must reach its viewport before parent hit testing. */
    public static boolean captures(PointerEvent event, Drag drag) {
        Objects.requireNonNull(event, "event");
        return drag != null && (event.kind() == PointerEvent.Kind.RELEASE
            || event.kind() == PointerEvent.Kind.DRAG && event.button() == PointerEvent.Button.LEFT);
    }

    public int visibleItems() {
        return axis == Axis.VERTICAL ? track.height() : track.width();
    }

    private int start() {
        return axis == Axis.VERTICAL ? track.y() : track.x();
    }

    private int coordinate(Point point) {
        return axis == Axis.VERTICAL ? point.y() : point.x();
    }

    public ScrollInteraction.Hit hit(Point point) {
        Objects.requireNonNull(point, "point");
        return track.contains(point)
            ? ScrollInteraction.hit(coordinate(point), start(), visibleItems(), totalItems, offset)
            : ScrollInteraction.Hit.NONE;
    }

    public Optional<Drag> grab(Point point) {
        if (visibleItems() < 2 || hit(point) != ScrollInteraction.Hit.THUMB) {
            return Optional.empty();
        }
        int thumbStart = start() + ScrollInteraction.thumbOffset(visibleItems(), totalItems, offset);
        return Optional.of(new Drag(axis, coordinate(point) - thumbStart));
    }

    /** A thumb press captures its grab point; a track press scrolls one page. */
    public Optional<Press> press(Point point) {
        ScrollInteraction.Hit hit = hit(point);
        if (hit == ScrollInteraction.Hit.NONE) {
            return Optional.empty();
        }
        if (hit == ScrollInteraction.Hit.THUMB) {
            return Optional.of(new Press(offset, grab(point).orElse(null)));
        }
        return Optional.of(new Press(
            ScrollInteraction.pageScrollFromTrackClick(offset, coordinate(point), start(), visibleItems(), totalItems),
            null
        ));
    }

    /** Captured dragging also accepts points outside the track. */
    public int drag(Point point, Drag drag) {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(drag, "drag");
        if (axis != drag.axis()) {
            throw new IllegalArgumentException("drag belongs to a different scroll axis");
        }
        if (visibleItems() < 2) {
            return offset;
        }
        long requestedStart = (long) coordinate(point) - start() - drag.grabOffset();
        int currentStart = ScrollInteraction.thumbOffset(visibleItems(), totalItems, offset);
        // Several offsets can map to one terminal cell. A stationary pointer
        // must not quantize the viewport to a different offset on first drag.
        if (requestedStart == currentStart) {
            return offset;
        }
        int boundedStart = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, requestedStart));
        return ScrollInteraction.scrollFromThumbStart(boundedStart, visibleItems(), totalItems);
    }

    public void draw(Canvas canvas, ScrollStyle style) {
        Objects.requireNonNull(style, "style");
        if (axis == Axis.VERTICAL) {
            VerticalScrollbar.draw(canvas, track, totalItems, offset, "│", style.track(), "█", style.thumb());
        } else {
            HorizontalScrollbar.draw(canvas, track, totalItems, offset, "─", style.track(), "█", style.thumb());
        }
    }

    /** Retained by the viewport owner across immutable placements. */
    public record Drag(Axis axis, int grabOffset) {
        public Drag {
            Objects.requireNonNull(axis, "axis");
            if (grabOffset < 0) {
                throw new IllegalArgumentException("grabOffset must not be negative");
            }
        }
    }

    public record Press(int offset, Drag drag) { }
}
