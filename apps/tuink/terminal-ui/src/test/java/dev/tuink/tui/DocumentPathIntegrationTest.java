package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.explorer.ui.PathPresentation;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentPathIntegrationTest {
    @TempDir Path directory;

    @Test void explorerOriginSurvivesHiddenSidebarAndFallsBackToFullWhenRootChangesOrCloses() throws Exception {
        Path target = Files.createDirectories(directory.resolve("nested")).resolve("note.md");
        Files.writeString(target, "# loaded");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openFromExplorer(window, target);
            assertEquals("nested/note.md", text(window));
            window.command(TuinkCommand.EXPLORER_TOGGLE);
            assertEquals("nested/note.md", text(window));
            assertTrue(window.screenRow(2).startsWith("nested/note.md"));
            window.controller.openFolder(target.getParent()); window.frame();
            assertEquals("note.md", text(window));
            window.controller.openFolder(Files.createDirectory(directory.resolve("other"))); window.frame();
            assertEquals(target.toString(), text(window));
            window.command(TuinkCommand.FOLDER_CLOSE);
            assertEquals(target.toString(), text(window));
        }
    }

    @Test void commandLineAndPickerOpenedDocumentsKeepFullPathsInsideAnOpenFolder() throws Exception {
        Path target = Files.writeString(directory.resolve("picked.txt"), "picked");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            assertEquals(window.document.toString(), text(window));
            window.controller.openFolder(directory); window.frame();
            assertEquals(window.document.toString(), text(window));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.active().uiState().openFileTree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy() && window.tabs.size() == 2);
            assertEquals(target.toString(), text(window));
        }
    }

    @Test void sourceLivePreviewFrozenPreviewAndReopenedSourceShareTheirOrigin() throws Exception {
        Path target = Files.writeString(directory.resolve("note.md"), "# loaded");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openFromExplorer(window, target);
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            assertEquals("note.md", text(window));
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.TAB_CLOSE);
            assertInstanceOf(PreviewTab.ClosedSnapshot.class, ((PreviewTab) window.selected()).source());
            assertEquals("note.md", text(window));
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.selected() instanceof TerminalEditorApp.EditorTab && !window.documentIo.busy());
            assertEquals("note.md", text(window));
        }
    }

    @Test void saveAsRecomputesTheBindingAndRetainsTheOriginAcrossAnOutsideDestination() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("root"));
        Path target = Files.writeString(folder.resolve("note.txt"), "loaded");
        Path outside = directory.resolve("saved.txt");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openFromExplorer(window, target, folder);
            assertEquals("note.txt", text(window));
            saveAs(window, outside);
            assertEquals(outside.toString(), text(window));
            Path inside = folder.resolve("renamed.txt");
            saveAs(window, inside);
            assertEquals("renamed.txt", text(window));
        }
    }

    @Test void frozenPathTracksRenameMoveAndUndoAndKeepsTheDeletedOriginalAsADraft() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path target = Files.writeString(root.resolve("note.md"), "# loaded");
        Path outside = Files.createDirectory(directory.resolve("outside"));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openFromExplorer(window, target, root);
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.TAB_CLOSE);
            assertEquals("note.md", text(window));
            selectForAction(window, target);
            window.command(TuinkCommand.EXPLORER_RENAME);
            awaitInspected(window);
            window.event(new TextInput("renamed.md"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy() && window.controller.explorerFileDialog() == null);
            Path renamed = root.resolve("renamed.md");
            assertEquals("renamed.md", text(window));
            selectForAction(window, renamed);
            window.command(TuinkCommand.EXPLORER_MOVE);
            awaitInspected(window);
            window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(outside.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.controller.explorerFileDialog().tree.loading());
            var dialog = window.controller.explorerFileDialog();
            for (int step = 0; step < 5 && dialog.focus.focused().orElse(null) != ExplorerFileDialogView.Target.APPLY; step++)
                window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(ExplorerFileDialogView.Target.APPLY, dialog.focus.focused().orElseThrow());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy() && window.controller.explorerFileDialog() == null);
            assertEquals(outside.resolve("renamed.md").toString(), text(window));
            window.command(TuinkCommand.EXPLORER_UNDO);
            window.await(() -> !window.documentIo.busy());
            assertEquals("renamed.md", text(window));
            Files.delete(renamed);
            window.command(TuinkCommand.EXPLORER_REFRESH);
            window.await(() -> !window.documentIo.busy() && text(window).contains("(Draft)"));
            assertEquals("renamed.md (Draft)", text(window));
        }
    }

    @Test void viewToggleRestoresContentGeometryAndTinyWindowsTemporarilyHideThePath() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            assertTrue(window.ui.showDocumentPathSetting);
            assertTrue(window.screenRow(1).startsWith(window.document.toString()));
            assertTrue(window.screenRow(2).contains("original"));
            window.command(TuinkCommand.MENU_VIEW);
            window.selectMenuCommand(TuinkCommand.VIEW_TOGGLE_DOCUMENT_PATH);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertFalse(window.ui.settingsSnapshot(window.host.theme).showDocumentPath());
            assertTrue(window.screenRow(1).contains("original"));
            window.command(TuinkCommand.VIEW_TOGGLE_DOCUMENT_PATH);
            window.notification.clear();
            window.resize(3, 30);
            assertTrue(window.ui.showDocumentPathSetting);
            assertTrue(window.screenRow(1).contains("original"));
            window.resize(12, 120);
            assertTrue(window.screenRow(1).startsWith(window.document.toString()));
        }
    }

    @Test void encodedPathsKeepTheirIdentityAndPercentTokensAtEveryDisplayWidth() throws Exception {
        assumeTrue(File.separatorChar == '/');
        Path path = Path.of(URI.create(directory.toUri().toASCIIString() + "raw%FF%0A-document.txt"));
        Files.writeString(path, "bytes");
        var absolute = DocumentPaths.component(path, null, false);
        assertEquals(PathPresentation.full(path), absolute.text());
        var relative = DocumentPaths.component(path, directory, true);
        assertEquals("file:raw%FF%0A-document.txt (Draft)", relative.text());
        for (var bar : List.of(absolute, relative)) for (int width = 0; width < 150; width++) {
            String fitted = bar.fit(width);
            assertTrue(CellText.width(fitted) <= width, fitted);
            assertFalse(fitted.matches(".*%(?:[0-9A-Fa-f])?(?:…|$).*"), fitted);
        }
        assertEquals("bytes", Files.readString(path));
    }

    private static String text(EditorWindowFixture window) {
        return window.host.documentPaths.component(window.host, window.context, window.ui.explorer.root()).text();
    }
    private void openFromExplorer(EditorWindowFixture window, Path target) throws Exception {
        openFromExplorer(window, target, directory);
    }
    private static void openFromExplorer(EditorWindowFixture window, Path target, Path root) throws Exception {
        window.controller.openFolder(root); window.frame();
        window.await(() -> !window.ui.explorer.tree.loading());
        assertTrue(window.ui.explorer.tree.reveal(target));
        window.await(() -> !window.ui.explorer.tree.loading() && window.ui.explorer.selectedEntry() != null
            && target.equals(window.ui.explorer.selectedEntry().path()));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        window.await(() -> !window.documentIo.busy() && window.tabs.size() == 2);
    }
    private static void saveAs(EditorWindowFixture window, Path target) throws Exception {
        window.command(TuinkCommand.DOCUMENT_SAVE_AS);
        window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
        window.event(new TextInput(target.toString()));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        window.await(() -> !window.documentIo.busy() && window.selected().uiState().modalState.isNone());
    }
    private static void selectForAction(EditorWindowFixture window, Path path) throws Exception {
        window.command(TuinkCommand.EXPLORER_FOCUS);
        window.await(() -> !window.ui.explorer.tree.loading());
        window.ui.explorer.tree.reveal(path);
        window.await(() -> !window.ui.explorer.tree.loading() && window.ui.explorer.selectedEntry() != null
            && path.equals(window.ui.explorer.selectedEntry().path()));
    }
    private static void awaitInspected(EditorWindowFixture window) throws Exception {
        window.await(() -> !window.documentIo.busy() && window.controller.explorerFileDialog() != null
            && window.controller.explorerFileDialog().inspected != null);
    }
}
