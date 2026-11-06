package dev.tuink.tui;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.core.Position;
import dev.tuink.editor.command.CommandInvocation;
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

    @Test void previewsUnsavedBufferAndReturnsAtPreviewPositionWithoutChangingDocumentHistory() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Heading", "", "Hello **world**."))) {
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
            window.command(TuinkCommand.CURSOR_DOCUMENT_START);
            window.command(TuinkCommand.CURSOR_RIGHT);
            assertEquals(cursor, window.active().state().getCursor());
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            assertFalse(window.preview().active);
            assertEquals(new Position(0, 3), window.active().state().getCursor());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("# Heading", "", "Hello **world**."), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test void emptyPreviewStillReturnsToSource() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of(""))) {
            var editor = window.active();
            window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
            window.await(() -> window.preview().ready());
            assertTrue(window.preview().result.document().projection().text().isEmpty());
            window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
            assertSame(editor, window.selected());
            assertEquals(new Position(0, 0), editor.state().getCursor());
        }
    }

    @Test void returningBeforeTheFirstPreviewLayoutPreservesTheSourceCursor() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false,
                List.of("# Heading", "", "Last paragraph"))) {
            window.command(TuinkCommand.CURSOR_DOCUMENT_END);
            var editor = window.active();
            var cursor = editor.state().getCursor();
            var toggle = CommandInvocation.of(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW.id());
            window.controller.invokeCommand(toggle, UiRecipient.presentation(editor, UiRecipient.Flow.LOCAL));
            var preview = assertInstanceOf(PreviewTab.class, window.selected());
            assertFalse(preview.uiState().preview.ready());
            window.controller.invokeCommand(toggle, UiRecipient.presentation(preview, UiRecipient.Flow.LOCAL));
            window.frame();
            assertSame(editor, window.selected());
            assertEquals(cursor, editor.state().getCursor());
        }
    }

    @Test void modeAndPositionBelongToEachTab() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false,
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
