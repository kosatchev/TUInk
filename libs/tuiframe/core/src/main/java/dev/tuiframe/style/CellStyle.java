package dev.tuiframe.style;

/**
 * Concrete styling of a terminal cell.
 */
public record CellStyle(CellColor fg, CellColor bg, boolean bold, boolean underline) {
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
            && underline == style.underline;
    }

    @Override
    public int hashCode() {
        int result = fg.ordinal();
        result = 31 * result + bg.ordinal();
        result = 31 * result + Boolean.hashCode(bold);
        return 31 * result + Boolean.hashCode(underline);
    }
}
