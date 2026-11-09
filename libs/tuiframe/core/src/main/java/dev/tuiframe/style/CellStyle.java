package dev.tuiframe.style;

/**
 * Concrete styling of a terminal cell.
 */
public record CellStyle(CellColor fg, CellColor bg, boolean bold, boolean underline, boolean inverse) {
    public CellStyle(CellColor fg, CellColor bg, boolean bold, boolean underline) { this(fg, bg, bold, underline, false); }
    /** Semantic inversion also works when both colors are terminal defaults. */
    public CellStyle inverted() { return new CellStyle(fg, bg, bold, underline, !inverse); }
    public CellStyle emphasis(boolean bold, boolean underline) { return new CellStyle(fg, bg, bold, underline, inverse); }
    public static final CellStyle DEFAULT = new CellStyle(
        CellColor.DEFAULT,
        CellColor.DEFAULT,
        false,
        false
    );

    public CellStyle {
        if (fg == null || bg == null) {
            throw new IllegalArgumentException("fg/bg must not be null");
        }
    }

    public static CellStyle fg(CellColor fg) {
        return new CellStyle(fg, CellColor.DEFAULT, false, false);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof CellStyle style
            && fg == style.fg
            && bg == style.bg
            && bold == style.bold
            && underline == style.underline
            && inverse == style.inverse;
    }

    @Override
    public int hashCode() {
        int result = fg.ordinal();
        result = 31 * result + bg.ordinal();
        result = 31 * result + Boolean.hashCode(bold);
        result = 31 * result + Boolean.hashCode(underline);
        return 31 * result + Boolean.hashCode(inverse);
    }
}
