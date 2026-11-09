package dev.tuink.render;

/**
 * Render-independent styling metadata.
 */
public record Style(Color fg, Color bg, boolean bold, boolean underline, boolean inverse) {
    public Style(Color fg, Color bg, boolean bold, boolean underline) { this(fg, bg, bold, underline, false); }
    public static final Style DEFAULT = new Style(Color.DEFAULT, Color.DEFAULT, false, false);

    public Style {
        if (fg == null || bg == null) {
            throw new IllegalArgumentException("fg/bg must not be null");
        }
    }

    public static Style fg(Color fg) {
        return new Style(fg, Color.DEFAULT, false, false);
    }
}
