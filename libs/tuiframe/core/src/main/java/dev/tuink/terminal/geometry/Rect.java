package dev.tuink.terminal.geometry;

import java.util.Objects;

/**
 * Zero-based rectangle in a cell grid. The left and top edges are inclusive;
 * {@link #rightExclusive()} and {@link #bottomExclusive()} are exclusive.
 */
public record Rect(int x, int y, int width, int height) {
    public Rect {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("width and height must not be negative");
        }
        requireRepresentableEdge(x, width, "right edge");
        requireRepresentableEdge(y, height, "bottom edge");
    }

    public static Rect fromEdges(int left, int top, int rightExclusive, int bottomExclusive) {
        if (rightExclusive < left || bottomExclusive < top) {
            throw new IllegalArgumentException("right and bottom edges must not precede left and top edges");
        }
        long width = (long) rightExclusive - left;
        long height = (long) bottomExclusive - top;
        if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("rectangle dimensions exceed the supported range");
        }
        return new Rect(left, top, (int) width, (int) height);
    }

    public int rightExclusive() {
        return x + width;
    }

    public int bottomExclusive() {
        return y + height;
    }

    public Size size() {
        return new Size(width, height);
    }

    public boolean isEmpty() {
        return width == 0 || height == 0;
    }

    public boolean contains(int candidateX, int candidateY) {
        return candidateX >= x
            && candidateX < rightExclusive()
            && candidateY >= y
            && candidateY < bottomExclusive();
    }

    public boolean contains(Point point) {
        Objects.requireNonNull(point, "point");
        return contains(point.x(), point.y());
    }

    public boolean contains(Rect other) {
        Objects.requireNonNull(other, "other");
        return other.x() >= x
            && other.y() >= y
            && other.rightExclusive() <= rightExclusive()
            && other.bottomExclusive() <= bottomExclusive();
    }

    public boolean intersects(Rect other) {
        Objects.requireNonNull(other, "other");
        return !isEmpty()
            && !other.isEmpty()
            && x < other.rightExclusive()
            && other.x() < rightExclusive()
            && y < other.bottomExclusive()
            && other.y() < bottomExclusive();
    }

    public Rect intersection(Rect other) {
        Objects.requireNonNull(other, "other");
        int left = Math.max(x, other.x());
        int top = Math.max(y, other.y());
        int right = Math.max(left, Math.min(rightExclusive(), other.rightExclusive()));
        int bottom = Math.max(top, Math.min(bottomExclusive(), other.bottomExclusive()));
        return Rect.fromEdges(left, top, right, bottom);
    }

    public Rect translate(int deltaX, int deltaY) {
        if (deltaX == 0 && deltaY == 0) {
            return this;
        }
        return new Rect(
            Math.addExact(x, deltaX),
            Math.addExact(y, deltaY),
            width,
            height
        );
    }

    private static void requireRepresentableEdge(int origin, int extent, String edgeName) {
        long edge = (long) origin + extent;
        if (edge < Integer.MIN_VALUE || edge > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(edgeName + " exceeds the supported coordinate range");
        }
    }
}
