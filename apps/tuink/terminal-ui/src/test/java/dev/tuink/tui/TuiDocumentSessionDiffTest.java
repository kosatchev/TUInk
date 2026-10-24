package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.GutterDiffFlag;
import dev.tuink.core.GutterDiffMarker;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.editor.session.document.CoreSessionBridge;
import org.junit.jupiter.api.Test;

class TuiDocumentSessionDiffTest {
    @Test
    void theTerminalViewExplicitlyProvidesDiffWhileOtherViewsShareOnlyDocumentAndHistory() {
        EditorClipboard clipboard = new EditorClipboard();
        TuiDocumentSession terminal = TuiDocumentSession.create(clipboard);
        var headless = CoreSessionBridge.attach(terminal.document(), LogicalEditorTextLayout::new, clipboard);
        terminal.view().engine().insertInput("draft");

        assertEquals(GutterDiffFlag.MODIFIED, terminal.view().engine().gutterDiffMarkerForLine(0).flag());
        assertEquals(GutterDiffMarker.NONE, headless.engine().gutterDiffMarkerForLine(0));
        assertEquals(-1L, headless.engine().nanosUntilGutterDiffRefresh(Long.MAX_VALUE));
        assertFalse(headless.engine().refreshGutterDiffIfDue());
        assertEquals("draft", terminal.document().snapshot().line(0));
        assertTrue(terminal.document().undo());
        assertEquals("", terminal.view().model().getLine(0));
        assertFalse(terminal.view().state().isDirty());
    }
}
