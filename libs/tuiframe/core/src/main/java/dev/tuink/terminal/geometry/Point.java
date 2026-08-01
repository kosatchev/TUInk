package dev.tuink.terminal.geometry;

/**
 * Zero-based point in a cell grid. The x coordinate is the column and the y
 * coordinate is the row.
 */
public record Point(int x, int y) {
    public static final Point ORIGIN = new Point(0, 0);

    public Point translate(int deltaX, int deltaY) {
        if (deltaX == 0 && deltaY == 0) {
            return this;
        }
        return new Point(Math.addExact(x, deltaX), Math.addExact(y, deltaY));
    }
}
