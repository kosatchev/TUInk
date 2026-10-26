package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.GutterDiffFlag;
import dev.tuink.core.GutterDiffMarker;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.editor.command.DocumentCommand;
import org.junit.jupiter.api.Test;

class TuiDocumentSessionDiffTest {
    @Test
    void theTerminalViewExplicitlyProvidesDiffWhileOtherViewsShareOnlyDocumentAndHistory() {
        EditorClipboard clipboard = new EditorClipboard();
        TuiDocumentSession terminal = TuiDocumentSession.create(clipboard);
        var headless = terminal.view().engine().document().attach(LogicalEditorTextLayout::new, clipboard);
        terminal.execute(new DocumentCommand.Input("draft"));

        assertEquals(GutterDiffFlag.MODIFIED, terminal.view().engine().gutterDiffMarkerForLine(0).flag());
        assertEquals(GutterDiffMarker.NONE, headless.engine().gutterDiffMarkerForLine(0));
        assertEquals(-1L, headless.engine().nanosUntilGutterDiffRefresh(Long.MAX_VALUE));
        assertFalse(headless.engine().refreshGutterDiffIfDue());
        assertEquals("draft", terminal.document().snapshot().line(0));
        assertTrue(terminal.execute(new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO)).performed());
        assertEquals("", terminal.view().model().getLine(0));
        assertFalse(terminal.view().state().isDirty());
    }
}
