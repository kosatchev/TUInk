package dev.tuink.tui;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.PasteEvent;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownPreviewIntegrationTest {
    @TempDir Path directory;

    @Test void previewsUnsavedBufferAndReturnsWithoutChangingDocumentHistoryOrCursor() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("# Heading", "", "Hello **world**."))) {
            window.command(TuinkCommand.CURSOR_DOCUMENT_END);
            window.event(new TextInput(" unsaved"));
            var cursor = window.active().state().getCursor();
            var source = window.active().tabSession().model().snapshotLines();
            long revision = window.active().tabSession().model().revision();
            window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
            window.await(() -> window.preview().ready());
            assertTrue(window.preview().active);
            assertTrue(window.preview().result.document().projection().text().contains("unsaved"));
            window.event(new TextInput("must not edit"));
            for (var command : List.of(TuinkCommand.EDIT_UNDO, TuinkCommand.EDIT_REDO, TuinkCommand.EDIT_CUT,
                TuinkCommand.EDIT_PASTE, TuinkCommand.EDIT_DELETE_FORWARD, TuinkCommand.EDIT_INSERT_NEWLINE)) window.command(command);
            assertEquals(source, window.active().tabSession().model().snapshotLines());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertTrue(window.active().state().isDirty());
            window.command(TuinkCommand.CURSOR_UP);
            assertEquals(cursor, window.active().state().getCursor());
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            assertFalse(window.preview().active);
            assertEquals(cursor, window.active().state().getCursor());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("# Heading", "", "Hello **world**."), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test void modeAndPositionBelongToEachTab() throws Exception {
        try (var window = new EditorWindowFixture(directory, false,
            java.util.stream.IntStream.range(0, 80).mapToObj(i -> "# Heading " + i).toList())) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            window.command(TuinkCommand.CURSOR_PAGE_DOWN);
            int top = window.preview().top;
            assertTrue(top > 0);
            window.command(TuinkCommand.TAB_NEW);
            assertNull(window.preview());
            window.command(TuinkCommand.TAB_PREVIOUS);
            assertTrue(window.preview().active);
            window.await(() -> window.preview().ready());
            assertEquals(top, window.preview().top);
            window.resize(12, 35);
            window.await(() -> window.preview().ready());
            assertTrue(window.screenRow(window.height - 1).contains("MD:live"));
            assertTrue(window.screenRow(window.height - 1).contains("Ctrl+P"));
            assertFalse(window.screenRow(window.preview().bounds.y()).contains("Ctrl+P"));
        }
    }
}
