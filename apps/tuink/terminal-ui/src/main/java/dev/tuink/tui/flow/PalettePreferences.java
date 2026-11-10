package dev.tuink.tui.flow;

import java.util.Objects;

/** Current optional settings for the Files/Search tool-palette dock. Heights are relative body sizes. */
public record PalettePreferences(int width, int filesHeight, int searchHeight, boolean filesExpanded,
                                 boolean searchExpanded, boolean searchFirst, Maximized maximized, boolean visible,
                                 boolean filesHints, boolean searchHints) {
    public enum Maximized { NONE, FILES, SEARCH }
    public PalettePreferences {
        if (width < 16 || width > 10_000 || filesHeight < 1 || filesHeight > 10_000 || searchHeight < 1 || searchHeight > 10_000)
            throw new IllegalArgumentException("palette sizes out of range");
        Objects.requireNonNull(maximized);
    }
    public static PalettePreferences defaults() { return new PalettePreferences(30, 6, 18, true, false, false, Maximized.NONE, false, true, true); }
}
