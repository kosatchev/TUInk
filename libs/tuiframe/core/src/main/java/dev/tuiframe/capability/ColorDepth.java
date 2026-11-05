package dev.tuiframe.capability;

/**
 * Color depth available to one terminal presentation session.
 *
 * <p>The physical cell palette uses ANSI-16 colors and a dark neutral shade
 * mapped to an indexed color on deeper terminals. Frontends negotiate
 * presentation without coupling widgets to a terminal backend.</p>
 */
public enum ColorDepth {
    MONOCHROME,
    ANSI_16,
    ANSI_256,
    TRUECOLOR;

    public boolean colorEnabled() {
        return this != MONOCHROME;
    }
}
