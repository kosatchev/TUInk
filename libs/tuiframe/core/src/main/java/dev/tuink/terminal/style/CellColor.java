package dev.tuink.terminal.style;

/**
 * Terminal cell color from the portable ANSI 16-color palette.
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
    BRIGHT_WHITE
}
