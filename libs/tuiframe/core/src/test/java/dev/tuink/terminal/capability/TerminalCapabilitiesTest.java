package dev.tuink.terminal.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalCapabilitiesTest {
    @Test
    void colorDepthReportsOnlyMonochromeAsColorless() {
        assertFalse(ColorDepth.MONOCHROME.colorEnabled());
        assertTrue(ColorDepth.ANSI_16.colorEnabled());
        assertTrue(ColorDepth.ANSI_256.colorEnabled());
        assertTrue(ColorDepth.TRUECOLOR.colorEnabled());
    }

    @Test
    void predefinedProfilesSeparateCompatibilityFromConservativeFallback() {
        assertEquals(ColorDepth.ANSI_16, TerminalCapabilities.DEFAULT.colorDepth());
        assertTrue(TerminalCapabilities.DEFAULT.mouseTracking());
        assertTrue(TerminalCapabilities.DEFAULT.horizontalScroll());
        assertTrue(TerminalCapabilities.DEFAULT.verticalScroll());

        assertEquals(ColorDepth.MONOCHROME, TerminalCapabilities.MINIMAL.colorDepth());
        assertFalse(TerminalCapabilities.MINIMAL.mouseTracking());
        assertFalse(TerminalCapabilities.MINIMAL.horizontalScroll());
        assertFalse(TerminalCapabilities.MINIMAL.verticalScroll());
    }

    @Test
    void applicationPolicyCanOnlyRestrictDetectedColorSupport() {
        TerminalCapabilities detected = new TerminalCapabilities(
            ColorDepth.TRUECOLOR,
            true,
            false,
            true
        );

        assertSame(detected, detected.withColorEnabled(true));
        assertEquals(
            new TerminalCapabilities(ColorDepth.MONOCHROME, true, false, true),
            detected.withColorEnabled(false)
        );
        assertSame(
            TerminalCapabilities.MINIMAL,
            TerminalCapabilities.MINIMAL.withColorEnabled(true)
        );
    }

    @Test
    void colorDepthIsRequired() {
        assertThrows(
            NullPointerException.class,
            () -> new TerminalCapabilities(null, false, false, false)
        );
    }
}
