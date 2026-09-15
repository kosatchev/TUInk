package dev.tuiframe.capability;

import java.util.Objects;

/**
 * Immutable capabilities negotiated for one terminal presentation session.
 *
 * @param colorDepth available color depth
 * @param mouseTracking whether SGR mouse tracking can be enabled and decoded
 * @param horizontalScroll whether insert/delete-character scroll patches are supported
 * @param verticalScroll whether scroll-region patches are supported
 */
public record TerminalCapabilities(
    ColorDepth colorDepth,
    boolean mouseTracking,
    boolean horizontalScroll,
    boolean verticalScroll
) {
    /**
     * Compatibility profile matching the ANSI behavior used before explicit
     * capability negotiation.
     */
    public static final TerminalCapabilities DEFAULT = new TerminalCapabilities(
        ColorDepth.ANSI_16,
        true,
        true,
        true
    );

    /** Conservative profile that uses no optional terminal features. */
    public static final TerminalCapabilities MINIMAL = new TerminalCapabilities(
        ColorDepth.MONOCHROME,
        false,
        false,
        false
    );

    public TerminalCapabilities {
        Objects.requireNonNull(colorDepth, "colorDepth");
    }

    public boolean colorEnabled() {
        return colorDepth.colorEnabled();
    }

    /**
     * Returns this capability set restricted by an application color policy.
     * Enabling color never upgrades a terminal that was detected as monochrome.
     */
    public TerminalCapabilities withColorEnabled(boolean enabled) {
        if (enabled || colorDepth == ColorDepth.MONOCHROME) {
            return this;
        }
        return new TerminalCapabilities(
            ColorDepth.MONOCHROME,
            mouseTracking,
            horizontalScroll,
            verticalScroll
        );
    }
}
