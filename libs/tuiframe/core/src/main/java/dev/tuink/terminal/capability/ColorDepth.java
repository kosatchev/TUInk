package dev.tuink.terminal.capability;

/**
 * Color depth available to one terminal presentation session.
 *
 * <p>The current physical cell palette is ANSI-16. Deeper modes are exposed
 * here so a frontend can negotiate presentation without coupling widgets to a
 * terminal backend; indexed and RGB theme mapping can be added independently.</p>
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
