package dev.tuink.terminal.geometry;

/** Width and height in terminal cells. */
public record Size(int width, int height) {
    public static final Size ZERO = new Size(0, 0);

    public Size {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("width and height must not be negative");
        }
    }

    public boolean isEmpty() {
        return width == 0 || height == 0;
    }

    public long area() {
        return (long) width * height;
    }
}
