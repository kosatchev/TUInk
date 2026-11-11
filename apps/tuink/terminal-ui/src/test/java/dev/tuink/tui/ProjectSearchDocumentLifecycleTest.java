package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSearchDocumentLifecycleTest {
    @TempDir Path root;

    @Test void autoReloadUndoSearchNavigationAndPreviewSaveUseTheSameCurrentBuffer() throws Exception {
        String original = "# Original needle";
        String external = "# External\nremote needle";
        try (var window = new EditorWindowFixture(root, "note.md", false, List.of(original))) {
            var source = window.active();
            window.controller.openFolder(root);
            search(window);
            assertFalse(window.ui.sidebar.search.stale);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());

            Files.writeString(window.document, external);
            window.await(() -> source.tabSession().model().getLine(0).equals("# External")
                && window.preview().ready() && visible(window, "remote needle"));
            assertTrue(window.ui.sidebar.search.stale);
            assertFalse(source.state().isDirty());

            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(KeyStroke.of(KeyStroke.Key.Z, KeyStroke.Modifier.CTRL));
            assertEquals(List.of(original), source.tabSession().model().snapshotLines());
            assertTrue(source.state().isDirty());
            search(window);
            var match = window.ui.sidebar.search.groups.values().iterator().next().matches.getFirst();
            assertEquals(source.documentSession().document().documentId(), match.target().documentId());
            assertEquals(0, match.line());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            var selected = assertInstanceOf(TerminalEditorApp.EditorTab.class, window.selected());
            assertSame(source.documentSession(), selected.documentSession());
            assertEquals(source.presentationId(), selected.presentationId());
            assertEquals(new Position(0, 11), source.state().getSelectionAnchor());
            assertEquals(new Position(0, 17), source.state().getCursor());
            assertEquals(external, Files.readString(window.document));

            window.command(TuinkCommand.TAB_NEXT);
            window.await(() -> window.preview().ready() && visible(window, "Original needle"));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> !window.documentIo.busy() && !source.state().isDirty());
            assertEquals(original, Files.readString(window.document));
            assertInstanceOf(PreviewTab.class, window.selected());
        }
    }

    private void search(EditorWindowFixture window) throws Exception {
        var panel = window.ui.sidebar.search;
        var previous = panel.finished;
        window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
        window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
        window.event(new TextInput("needle"));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        window.await(() -> panel.finished != null && panel.finished != previous);
        assertNull(panel.error);
        assertEquals(1, panel.matches());
    }

    private static boolean visible(EditorWindowFixture window, String text) {
        return java.util.stream.IntStream.range(0, window.height).anyMatch(row -> window.screenRow(row).contains(text));
    }
}
