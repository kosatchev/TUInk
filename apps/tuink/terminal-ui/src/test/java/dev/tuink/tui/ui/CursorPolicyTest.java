package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.flow.SearchFocus;
import org.junit.jupiter.api.Test;

class CursorPolicyTest {
    @Test
    void everyPlacedConfirmPromptHidesTheEditorCursor() {
        assertFalse(CursorPolicy.shouldShowCursor(
            false,
            false,
            SearchFocus.FIND,
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
            SearchFocus.FIND,
            false,
            false,
            false,
            false
        ));
        assertTrue(CursorPolicy.shouldShowCursor(
            false,
            true,
            SearchFocus.REPLACE,
            false,
            false,
            false,
            false
        ));
        assertFalse(CursorPolicy.shouldShowCursor(
            false,
            true,
            SearchFocus.NEXT_BUTTON,
            false,
            false,
            false,
            false
        ));
    }
}
