package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
