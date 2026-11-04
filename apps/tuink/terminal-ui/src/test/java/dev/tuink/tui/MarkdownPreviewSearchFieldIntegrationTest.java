package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownPreviewSearchFieldIntegrationTest {
    @TempDir Path directory;

    @Test void querySupportsCaretSelectionClipboardAndSemanticAliasesWithoutEditingTheSource() throws Exception {
        List<String> source = List.of("# Header", "", "needle alpha beta needle");
        try (var window = new EditorWindowFixture(directory, false, source)) {
            var sourceCaret = window.active().state().getCursor();
            long revision = window.active().tabSession().model().revision();
            var preview = open(window);
            window.event(ctrl(KeyStroke.Key.F));
            window.event(new TextInput("needle"));
            window.event(ctrl(KeyStroke.Key.A));
            assertTrue(preview.searchField.hasSelection());
            assertEquals(preview.selectionStart(), preview.selectionEnd(), "Ctrl+A belongs to the query");
            window.event(ctrl(KeyStroke.Key.C));
            assertEquals("needle", window.host.clipboard.text());
            window.event(ctrl(KeyStroke.Key.X));
            assertEquals("", preview.searchQuery);
            window.event(ctrl(KeyStroke.Key.V));
            assertEquals("needle", preview.searchQuery);

            window.command(TuinkCommand.CURSOR_HOME);
            assertEquals(0, preview.searchField.cursor());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            window.event(KeyStroke.of(KeyStroke.Key.DELETE));
            window.event(new TextInput("E"));
            assertEquals("nEedle", preview.searchQuery);
            window.event(ctrl(KeyStroke.Key.A));
            window.event(new PasteEvent("alpha\nbeta"));
            assertEquals("alpha beta", preview.searchQuery);
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            assertNotNull(MarkdownPreviewPainter.cursor(preview.placement(MarkdownPreviewState.Placement.Focus.QUERY)));
            assertEquals(preview.bounds.y(), MarkdownPreviewPainter.cursor(preview.placement(MarkdownPreviewState.Placement.Focus.QUERY)).y());

            assertEquals(sourceCaret, window.active().state().getCursor());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertEquals(source, window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test void queryAdmissionRejectsOversizedInsertionAndKeepsTheSelectedValueAndClipboard() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("alpha beta"))) {
            var preview = open(window);
            window.event(ctrl(KeyStroke.Key.F)); window.event(new TextInput("alpha"));
            window.event(ctrl(KeyStroke.Key.A));
            window.host.clipboard.setText("x".repeat(4097));
            window.event(ctrl(KeyStroke.Key.V));
            assertEquals("alpha", preview.searchQuery);
            assertEquals("alpha", preview.searchField.selectedText());
            assertEquals(4097, window.host.clipboard.text().length());
            window.event(new TextInput("x".repeat(4096)));
            assertEquals(4096, preview.searchQuery.length());
            window.event(new TextInput("y"));
            assertEquals(4096, preview.searchQuery.length());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(preview.searchVisible); assertTrue(preview.active);
        }
    }

    @Test void altPanAndWheelUseTheSameStepsAndTheFollowingArrowStaysInTheViewport() throws Exception {
        var source = new ArrayList<>(List.of("```mermaid", "flowchart LR",
            "A[Открыть Markdown] --> B{Режим}",
            "B -->|Исходник| C[Редактор]", "B -->|Просмотр| D[Рендер]",
            "C --> E[Сохранить]", "D --> E", "```", ""));
        for (int row = 0; row < 35; row++) { source.add("Paragraph " + row); source.add(""); }
        try (var window = new EditorWindowFixture(directory, false, source)) {
            var sourceCaret = window.active().state().getCursor();
            var preview = open(window);
            assertTrue(preview.result.extent() > preview.bounds.width());
            window.event(alt(KeyStroke.Key.ARROW_RIGHT));
            assertEquals(3, preview.left);
            window.pointer(PointerEvent.Kind.SCROLL_RIGHT, preview.bounds.y() + 2, preview.bounds.x() + 5);
            assertEquals(6, preview.left);
            window.event(alt(KeyStroke.Key.ARROW_DOWN));
            assertEquals(3, preview.top);
            var before = preview.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret();
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertEquals(before.row() + 1, preview.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret().row());
            assertEquals(3, preview.top);
            assertEquals(6, preview.left);
            assertTrue(window.screenRow(window.height - 1).contains("Row "));
            assertFalse(window.screenRow(preview.bounds.y()).contains("Ctrl+P"));
            assertEquals(sourceCaret, window.active().state().getCursor());
            assertFalse(window.active().state().isDirty());
        }
    }

    private static MarkdownPreviewState open(EditorWindowFixture window) throws InterruptedException {
        window.event(ctrl(KeyStroke.Key.P));
        var preview = window.preview();
        window.await(() -> preview.ready() && preview.pending == null);
        return preview;
    }
    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
    private static KeyStroke alt(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.ALT); }
}
