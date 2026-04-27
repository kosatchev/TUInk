package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HelpPagesTest {
    @Test
    void versionComesFromGeneratedResource() {
        assertEquals("0.10.5", HelpPages.appVersion());
    }
}
