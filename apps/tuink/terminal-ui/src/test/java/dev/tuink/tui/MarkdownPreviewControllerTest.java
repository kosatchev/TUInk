package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real key/pointer routing, including the content-focus path beneath a blocking dialog. */
class MarkdownPreviewControllerTest {
    @TempDir Path directory;

    @Test void headingAndInternalLinkNavigationUseSemanticAnchorsWithoutMovingSource() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false,
            List.of("# Start", "", "[jump](#second)", "", "## Second", "", "Text"))) {
            var sourceCursor = window.active().state().getCursor();
            var preview = openPreview(window);
            window.command(TuinkCommand.VIEW_PREVIEW_NEXT_HEADING);
            assertEquals(preview.result.document().projection().text().indexOf("Second"), preview.cursorOffset());
            window.command(TuinkCommand.VIEW_PREVIEW_PREVIOUS_HEADING);
            assertEquals(0, preview.cursorOffset());
            int link = preview.result.document().projection().text().indexOf("jump");
            preview.selection.selectRange(preview.result.document().projection().text(), link, link);
            window.command(TuinkCommand.VIEW_PREVIEW_FOLLOW_LINK);
            assertEquals(preview.result.document().projection().text().indexOf("Second"), preview.cursorOffset());
            assertEquals(sourceCursor, window.active().state().getCursor());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test void keyboardSelectionCopiesSemanticContentAndSurvivesReflowWithoutMovingSourceCursor() throws Exception {
        List<String> source = List.of("alpha **beta** gamma");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, "document.md", false, source)) {
            var caret = window.active().state().getCursor();
            long revision = window.active().tabSession().model().revision();
            MarkdownPreviewState preview = openPreview(window);
            for (int i = 0; i < 3; i++) window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT));
            assertEquals("alp", preview.selectedText());
            window.event(ctrl(KeyStroke.Key.C));
            assertEquals("alp", window.host.clipboard.text());
            assertTrue(window.screenRow(window.height - 2).contains("Copied Markdown preview text"));

            window.resize(12, 12);
            window.await(() -> preview.ready() && preview.pending == null);
            assertEquals("alp", preview.selectedText());
            window.event(ctrl(KeyStroke.Key.A));
            window.event(ctrl(KeyStroke.Key.C));
            assertEquals("alpha beta gamma", window.host.clipboard.text());
            assertEquals(caret, window.active().state().getCursor());
            assertEquals(source, window.active().tabSession().model().snapshotLines());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test void previewSearchEditsOnlyItsQueryAndEscapeKeepsThePreviewTabOpen() throws Exception {
        List<String> source = List.of("# Title", "", "one **needle** two needle");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, "document.md", false, source)) {
            long revision = window.active().tabSession().model().revision();
            MarkdownPreviewState preview = openPreview(window);
            window.event(ctrl(KeyStroke.Key.F));
            assertTrue(preview.searchVisible);
            window.event(new TextInput("needle"));
            assertEquals("needle", preview.searchQuery);
            assertEquals(2, preview.searchMatches.size());
            assertFalse(window.active().uiState().searchMode, "Preview search must not activate the source replacement controls");
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals("needle", preview.selectedText());
            int first = preview.selectionStart();
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(preview.selectionStart() > first);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(first, preview.selectionStart());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(preview.searchVisible);
            assertTrue(preview.active);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(preview.active);
            assertInstanceOf(PreviewTab.class, window.selected());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_LEFT));
            window.event(ctrl(KeyStroke.Key.P));
            assertFalse(preview.active);
            assertInstanceOf(TerminalEditorApp.EditorTab.class, window.selected());
            assertEquals(new Position(2, 6), window.active().state().getCursor());
            assertEquals(source, window.active().tabSession().model().snapshotLines());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test void copyCommandsAndMdSourceMenuUseCurrentSemanticPosition() throws Exception {
        List<String> source = List.of("Intro $x_i^2$.", "", "| A | B |", "|---|---|", "| left | right |", "", "Last paragraph.");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, "document.md", false, source)) {
            long revision = window.active().tabSession().model().revision();
            MarkdownPreviewState preview = openPreview(window);
            String semantic = preview.result.document().projection().text();
            int formula = semantic.indexOf("x_i^2");
            preview.selection.selectRange(semantic, formula, formula);
            window.command(TuinkCommand.VIEW_PREVIEW_COPY_SOURCE);
            assertEquals("x_i^2", window.host.clipboard.text());
            assertTrue(preview.active);

            int table = semantic.indexOf("left");
            preview.selection.selectRange(semantic, table, table);
            window.command(TuinkCommand.VIEW_PREVIEW_COPY_TABLE_TSV);
            assertEquals("A\tB\nleft\tright", window.host.clipboard.text());

            int paragraph = semantic.indexOf("Last paragraph.");
            preview.selection.selectRange(semantic, paragraph + 3, paragraph + 3);
            window.event(KeyStroke.of(KeyStroke.Key.F3));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertFalse(preview.active);
            assertEquals(new Position(6, 3), window.active().state().getCursor());
            assertEquals(source, window.active().tabSession().model().snapshotLines());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test void dialogContentFocusRoutesPointerNavigationAndCopyToPreview() throws Exception {
        List<String> source = List.of("alpha **beta** gamma", "", "second paragraph");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, "document.md", false, source)) {
            var caret = window.active().state().getCursor();
            long revision = window.active().tabSession().model().revision();
            MarkdownPreviewState preview = openPreview(window);
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            String filename = window.filename.text();
            int row = preview.contentBounds().y();
            int column = preview.contentBounds().x() + 1;
            window.pointer(PointerEvent.Kind.PRESS, row, column);
            window.pointer(PointerEvent.Kind.DRAG, row, column + 3);
            window.pointer(PointerEvent.Kind.RELEASE, row, column + 3);
            assertTrue(window.selected().uiState().blockingContentFocused);
            assertEquals("lph", preview.selectedText());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT));
            assertEquals("lpha", preview.selectedText());
            window.event(ctrl(KeyStroke.Key.C));
            assertEquals("lpha", window.host.clipboard.text());
            window.event(new TextInput("ignored"));

            assertTrue(preview.active);
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            assertEquals(filename, window.filename.text());
            assertEquals(caret, window.active().state().getCursor());
            assertEquals(source, window.active().tabSession().model().snapshotLines());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
            assertFalse(window.editorView.isMouseSelecting());
        }
    }

    private static MarkdownPreviewState openPreview(EditorWindowFixture window) throws InterruptedException {
        window.event(ctrl(KeyStroke.Key.P));
        MarkdownPreviewState preview = window.preview();
        window.await(() -> preview.ready() && preview.pending == null);
        assertTrue(preview.active);
        return preview;
    }
    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
}
