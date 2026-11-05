package dev.tuiframe.style;

/**
 * Terminal cell colors: ANSI-16 plus neutral shades for deeper terminals.
 *
 * <p>This physical color model is intentionally separate from editor semantic
 * styling. Terminal capability fallback belongs to the screen backend.</p>
 */
public enum CellColor {
    DEFAULT,
    BLACK,
    RED,
    GREEN,
    YELLOW,
    BLUE,
    MAGENTA,
    CYAN,
    WHITE,
    BRIGHT_BLACK,
    BRIGHT_RED,
    BRIGHT_GREEN,
    BRIGHT_YELLOW,
    BRIGHT_BLUE,
    BRIGHT_MAGENTA,
    BRIGHT_CYAN,
    BRIGHT_WHITE,
    /** Dark neutral shade; indexed 236 where supported, black on ANSI-16. */
    DARK_GRAY,
    GRAY_128,
    GRAY_158,
    GRAY_188,
    GRAY_218,
    GRAY_238,
    GRAY_028
}
