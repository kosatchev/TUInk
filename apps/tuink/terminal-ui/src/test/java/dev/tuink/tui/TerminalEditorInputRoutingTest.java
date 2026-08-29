package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.flow.UiModalState;
import org.junit.jupiter.api.Test;

class TerminalEditorInputRoutingTest {
    @Test
    void singleLinePasteCollapsesLineBreaksAndDropsUnsafeControls() {
        assertEquals(
            "first second 😀",
            TerminalEditorApp.singleLinePaste("first\r\n\tsecond\u0000 😀\n")
        );
        assertEquals("", TerminalEditorApp.singleLinePaste("\r\n\t"));
        assertEquals("", TerminalEditorApp.singleLinePaste(null));
    }

    @Test
    void modalPromptExclusivelyOwnsTheInteractiveRow() {
        assertTrue(TerminalEditorApp.searchOwnsInteractiveRow(UiModalState.none()));
        assertFalse(TerminalEditorApp.searchOwnsInteractiveRow(UiModalState.saveAs()));
        assertFalse(TerminalEditorApp.searchOwnsInteractiveRow(UiModalState.closeTabConfirm(0)));
        assertFalse(TerminalEditorApp.searchOwnsInteractiveRow(UiModalState.helpHints()));
        assertFalse(TerminalEditorApp.searchOwnsInteractiveRow(null));
    }
}
