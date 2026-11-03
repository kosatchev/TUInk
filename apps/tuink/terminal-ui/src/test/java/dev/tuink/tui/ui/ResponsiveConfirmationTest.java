package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.FocusManager;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.ConfirmationShortcuts;
import org.junit.jupiter.api.Test;

class ResponsiveConfirmationTest {
    @Test void narrowPromptWrapsCompleteFileIdentityAndKeepsAllChoicesAvailable() {
        String identity = "report-界界界界-long-ending.md";
        var view = ConfirmBarView.responsive("Overwrite: " + identity + " ?", 18, 22,
            "Replace", "Choose another name", true, "Cancel", ConfirmationShortcuts.SAVE);
        String unwrapped = view.row().lines().map(String::stripTrailing).collect(java.util.stream.Collectors.joining());
        assertTrue(unwrapped.contains(identity));
        assertTrue(unwrapped.contains("Choose another name"), "a compact button retains its full meaning in visible context");
        assertTrue(view.row().contains("Ctrl+S"));
        var placed = view.place(new Object(), 1, new Rect(0, 0, 18, 24));
        assertTrue(placed.decisionsAvailable());
        var focus = new FocusManager<ConfirmChoice>();
        placed.activate(focus);
        assertEquals(ConfirmChoice.CANCEL, focus.focused().orElseThrow());
        for (var id : placed.focusScope().tabOrder()) assertTrue(placed.focusScope().target(id).orElseThrow().focusEligible());
    }

    @Test void quitHintUsesItsActualDiscardShortcutAndTheFullActionLabel() {
        var single = ConfirmBarView.responsive("Unsaved (1)", 120, 24,
            "Save", "Discard", true, "Cancel", ConfirmationShortcuts.QUIT);
        assertEquals("Y/Ctrl+S=Save  N/Ctrl+Q=Discard  Esc=Cancel",
            single.row().lines().findFirst().orElseThrow().stripTrailing());
        var quit = ConfirmBarView.responsive("Unsaved (2)", 120, 24,
            "Save All", "Discard All", true, "Cancel", ConfirmationShortcuts.QUIT);
        assertTrue(quit.row().contains("Ctrl+Q=Discard All"));
        assertTrue(quit.row().contains("Y/Ctrl+S=Save All"));
        assertTrue(quit.row().contains("N/Ctrl+Q=Discard All  Esc=Cancel"));
        assertFalse(quit.row().contains("Tab/"));
        var close = ConfirmBarView.responsive("Close document?", 120, 24,
            "Save", "Discard", true, "Cancel", ConfirmationShortcuts.SAVE_CHANGES);
        assertTrue(close.row().contains("N=Discard  Esc/Ctrl+Q=Cancel"));
        assertFalse(close.row().contains("Ctrl+Q=Discard"));
    }

    @Test void clippedContextCannotAuthorizeAVisibleButtonAndInsufficientHeightGivesSafeHint() {
        var view = ConfirmBarView.responsive("Overwrite: report.md ?", 18, 22,
            "Replace", "Choose another name", true, "Cancel", ConfirmationShortcuts.SAVE);
        var clipped = view.place(new Object(), 0, new Rect(0, 1, 18, 23));
        assertFalse(clipped.decisionsAvailable());
        assertFalse(clipped.focusScope().target(ConfirmChoice.SAVE).orElseThrow().focusEligible());
        var tooShort = ConfirmBarView.responsive("Overwrite: report.md ?", 18, 2,
            "Replace", "Choose another name", true, "Cancel", ConfirmationShortcuts.SAVE);
        assertTrue(tooShort.row().contains("Enlarge"));
        assertFalse(tooShort.place(new Object(), 0, new Rect(0, 0, 18, 2)).decisionsAvailable());
    }
}
