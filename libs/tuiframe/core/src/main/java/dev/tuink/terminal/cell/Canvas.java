package dev.tuink.terminal.cell;

import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.style.CellStyle;
import java.util.Arrays;
import java.util.Objects;

/**
 * Mutable, clipped drawing context over a {@link CellBuffer}.
 *
 * <p>Coordinates are local to the current origin. The canvas is intended to
 * stay on one paint thread. Clipping is strict: a width-two grapheme is placed
 * only when its complete footprint and every replaced wide-cell footprint are
 * inside the current clip.</p>
 */
public final class Canvas {
    private static final int STATE_WIDTH = 6;
    private static final int INITIAL_STATE_CAPACITY = STATE_WIDTH * 4;

    private final CellBuffer target;
    private int originX;
    private int originY;
    private int clipLeft;
    private int clipTop;
    private int clipRight;
    private int clipBottom;
    private int[] states = new int[0];
    private int stateSize;

    public Canvas(CellBuffer target) {
        this.target = Objects.requireNonNull(target, "target");
        this.clipRight = target.cols();
        this.clipBottom = target.rows();
    }

    /**
     * Stores the current origin and clip, returning a stack-depth marker for
     * {@link #restore(int)}. Markers may be reused after their state is
     * restored; callers must not retain them beyond the matching paint scope.
     */
    public int save() {
        int mark = stateSize;
        ensureStateCapacity(mark + STATE_WIDTH);
        states[mark] = originX;
        states[mark + 1] = originY;
        states[mark + 2] = clipLeft;
        states[mark + 3] = clipTop;
        states[mark + 4] = clipRight;
        states[mark + 5] = clipBottom;
        stateSize += STATE_WIDTH;
        return mark;
    }

    /** Restores a live marker and discards it together with all nested states. */
    public void restore(int mark) {
        if (mark < 0 || mark % STATE_WIDTH != 0 || mark > stateSize - STATE_WIDTH) {
            throw new IllegalArgumentException("invalid or stale canvas state marker: " + mark);
        }
        originX = states[mark];
        originY = states[mark + 1];
        clipLeft = states[mark + 2];
        clipTop = states[mark + 3];
        clipRight = states[mark + 4];
        clipBottom = states[mark + 5];
        stateSize = mark;
    }

    /** Translates subsequent local coordinates without moving the absolute clip. */
    public void translate(int deltaX, int deltaY) {
        int nextX = Math.addExact(originX, deltaX);
        int nextY = Math.addExact(originY, deltaY);
        originX = nextX;
        originY = nextY;
    }

    /** Intersects the current clip with a rectangle in current local coordinates. */
    public boolean clip(Rect rect) {
        Objects.requireNonNull(rect, "rect");
        return clip(rect.x(), rect.y(), rect.width(), rect.height());
    }

    /** Intersects the current clip with a rectangle in current local coordinates. */
    public boolean clip(int x, int y, int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("width and height must not be negative");
        }
        long requestedLeft = (long) originX + x;
        long requestedTop = (long) originY + y;
        long requestedRight = requestedLeft + width;
        long requestedBottom = requestedTop + height;
        long nextLeft = Math.max((long) clipLeft, requestedLeft);
        long nextTop = Math.max((long) clipTop, requestedTop);
        long nextRight = Math.min((long) clipRight, requestedRight);
        long nextBottom = Math.min((long) clipBottom, requestedBottom);
        if (nextRight <= nextLeft || nextBottom <= nextTop) {
            clipRight = clipLeft;
            clipBottom = clipTop;
            return false;
        }
        clipLeft = (int) nextLeft;
        clipTop = (int) nextTop;
        clipRight = (int) nextRight;
        clipBottom = (int) nextBottom;
        return true;
    }

    /**
     * Places one sanitized grapheme at a local point.
     *
     * @return {@code true} when the complete atomic write was applied
     */
    public boolean put(int x, int y, String grapheme, CellStyle style) {
        long absoluteX = (long) originX + x;
        long absoluteY = (long) originY + y;
        if (!insideClip(absoluteX, absoluteY)) {
            return false;
        }
        Cell next = new Cell(grapheme, style);
        return putInsideClip((int) absoluteX, (int) absoluteY, next);
    }

    /**
     * Places one immutable cell at a local point. Reusing a cell avoids
     * allocation in painters that repeat the same grapheme and style.
     * Continuation inputs are normalized to a blank cell by the target.
     *
     * @return {@code true} when the complete atomic write was applied
     */
    public boolean put(int x, int y, Cell cell) {
        long absoluteX = (long) originX + x;
        long absoluteY = (long) originY + y;
        if (!insideClip(absoluteX, absoluteY)) {
            return false;
        }
        return putInsideClip(
            (int) absoluteX,
            (int) absoluteY,
            Objects.requireNonNull(cell, "cell")
        );
    }

    private boolean insideClip(long absoluteX, long absoluteY) {
        return absoluteX >= clipLeft
            && absoluteX < clipRight
            && absoluteY >= clipTop
            && absoluteY < clipBottom;
    }

    private boolean putInsideClip(int absoluteX, int absoluteY, Cell next) {
        return target.trySetClipped(
            absoluteY,
            absoluteX,
            next,
            clipLeft,
            clipTop,
            clipRight,
            clipBottom
        );
    }

    private void ensureStateCapacity(int required) {
        if (states.length >= required) {
            return;
        }
        int capacity = states.length == 0 ? INITIAL_STATE_CAPACITY : states.length;
        while (capacity < required) {
            capacity = Math.multiplyExact(capacity, 2);
        }
        states = Arrays.copyOf(states, capacity);
    }
}
