package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CursorPolicyTest {
    @Test
    void everyPlacedConfirmPromptHidesTheEditorCursor() {
        assertFalse(CursorPolicy.shouldShowCursor(
            false,
            false,
            false,
            true,
            false,
            false,
            false
        ));
    }

    @Test
    void textEntryModesRetainTheirCursorPolicy() {
        assertTrue(CursorPolicy.shouldShowCursor(
            true,
            false,
            false,
            false,
            false,
            false,
            false
        ));
        assertTrue(CursorPolicy.shouldShowCursor(
            false,
            true,
            true,
            false,
            false,
            false,
            false
        ));
        assertFalse(CursorPolicy.shouldShowCursor(
            false,
            true,
            false,
            false,
            false,
            false,
            false
        ));
    }

    @Test
    void foregroundOverlaySuppressesTheRetainedSearchCursor() {
        assertFalse(CursorPolicy.shouldShowCursor(
            false,
            true,
            true,
            false,
            true,
            false,
            false
        ));
        assertFalse(CursorPolicy.shouldShowCursor(
            false,
            true,
            true,
            false,
            false,
            true,
            false
        ));
    }
}
