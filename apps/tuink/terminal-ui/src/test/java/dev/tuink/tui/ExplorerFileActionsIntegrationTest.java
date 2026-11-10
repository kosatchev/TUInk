package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExplorerFileActionsIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void physicalAndSemanticQuitCancelFileCreationWithoutChangingTheFilesystem(boolean semantic) throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openTree(window);
            List<Path> before;
            try (var entries = Files.list(directory)) { before = entries.sorted().toList(); }
            window.command(TuinkCommand.EXPLORER_NEW_FILE);
            window.event(new TextInput("should-not-exist.txt"));
            assertNotNull(window.controller.explorerFileDialog());

            if (semantic) {
                window.controller.invokeCommand(CommandInvocation.of(TuinkCommand.APP_QUIT.id()),
                    UiRecipient.presentation(window.selected(), UiRecipient.Flow.LOCAL));
                window.frame();
            } else window.event(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL));

            assertNull(window.controller.explorerFileDialog());
            assertEquals(ActiveSurface.ModalScope.NONE, window.controller.activeSurface().modalScope());
            assertFalse(window.documentIo.busy());
            assertFalse(Files.exists(directory.resolve("should-not-exist.txt")));
            assertEquals("original", Files.readString(window.document));
            try (var entries = Files.list(directory)) { assertEquals(before, entries.sorted().toList()); }
        }
    }

    @Test
    void newFileNameUsesTheApplicationClipboardAndCancelKeepsTheFilesystem() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openTree(window);
            window.command(TuinkCommand.EXPLORER_NEW_FILE);
            window.host.clipboard.setText("clipboard.txt");
            window.command(TuinkCommand.EDIT_PASTE);
            var dialog = window.controller.explorerFileDialog();
            assertEquals("clipboard.txt", dialog.field.text());
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.command(TuinkCommand.EDIT_CUT);
            assertEquals("clipboard.txt", window.host.clipboard.text());
            assertEquals("", dialog.field.text());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertNull(window.controller.explorerFileDialog());
            assertFalse(Files.exists(directory.resolve("clipboard.txt")));
            assertEquals("original", Files.readString(window.document));
        }
    }

    @Test
    void selectedRootCreatesItsFolderAndFileAndOpensTheNewFile() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openTree(window);
            select(window, directory);
            window.command(TuinkCommand.EXPLORER_NEW_DIRECTORY);
            assertNotNull(window.controller.explorerFileDialog());
            window.event(new TextInput("created-folder"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> window.controller.explorerFileDialog() == null && !window.documentIo.busy());
            assertTrue(Files.isDirectory(directory.resolve("created-folder")));

            window.await(() -> !window.ui.sidebar.tree.loading());
            select(window, directory);
            window.command(TuinkCommand.EXPLORER_NEW_FILE);
            window.event(new TextInput("created.txt"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            Path created = directory.resolve("created.txt");
            window.await(() -> window.controller.explorerFileDialog() == null && !window.documentIo.busy()
                && window.tabs.size() == 2);

            assertEquals("", Files.readString(created));
            assertEquals(created, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(List.of(""), window.active().tabSession().model().snapshotLines());
            assertEquals("original", Files.readString(window.document));
            assertEquals(directory, window.ui.sidebar.root());
        }
    }

    @Test
    void rootMoveAndDeleteAreRejectedEvenThroughDirectSemanticCommands() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openTree(window);
            select(window, directory);
            for (var command : List.of(TuinkCommand.EXPLORER_MOVE, TuinkCommand.EXPLORER_DELETE)) {
                window.command(command);
                assertNull(window.controller.explorerFileDialog());
                assertFalse(window.documentIo.busy());
                assertTrue(Files.isDirectory(directory));
                assertEquals("original", Files.readString(window.document));
            }
        }
    }

    @Test
    void moveChooserNavigatesDirectoriesWithKeyboardWithoutChangingTheSidebarRoot() throws Exception {
        Path destination = Files.createDirectory(directory.resolve("destination"));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openTree(window);
            select(window, window.document);
            window.command(TuinkCommand.EXPLORER_MOVE);
            awaitDialog(window);
            var dialog = window.controller.explorerFileDialog();
            assertEquals(directory, dialog.destinationFolder(), "The initial '..' row must not select the parent as destination");
            assertEquals(ExplorerFileDialogView.Target.TREE, dialog.focus.focused().orElseThrow());

            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertEquals(destination, dialog.tree.state().selectedId().path());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !dialog.tree.loading());
            assertEquals(directory, dialog.tree.root());
            assertEquals(destination, dialog.destinationFolder());
            assertEquals("destination", dialog.field.text());
            assertEquals(directory, window.ui.sidebar.root());
            chooseApply(window);
            window.await(() -> window.controller.explorerFileDialog() == null && !window.documentIo.busy());

            Path moved = destination.resolve(window.document.getFileName());
            assertFalse(Files.exists(window.document));
            assertEquals("original", Files.readString(moved));
            assertEquals(moved, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(directory, window.ui.sidebar.root());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void movingAnOpenDirtyFileOrItsFolderPreservesHistoryAndSavesToTheNewPath(boolean moveFolder) throws Exception {
        Path sourceFolder = Files.createDirectory(directory.resolve("source"));
        Path destination = Files.createDirectory(directory.resolve("destination"));
        try (var window = new EditorWindowFixture(directory, "source/document.txt", false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var document = window.active().documentSession().document();
            var id = document.documentId();
            var cursor = window.active().state().getCursor();
            long revision = window.active().tabSession().model().revision();
            window.controller.openFolder(directory);
            window.await(() -> !window.ui.sidebar.tree.loading());
            if (!moveFolder) {
                window.ui.sidebar.tree.reveal(window.document);
                window.await(() -> !window.ui.sidebar.tree.loading()
                    && window.ui.sidebar.tree.selectedEntry() != null
                    && window.document.equals(window.ui.sidebar.tree.selectedEntry().path()));
            } else select(window, sourceFolder);
            window.command(TuinkCommand.EXPLORER_MOVE);
            awaitDialog(window);
            chooseDestination(window, destination);
            chooseApply(window);
            window.await(() -> window.controller.explorerFileDialog() == null && !window.documentIo.busy());

            Path moved = moveFolder ? destination.resolve("source").resolve(window.document.getFileName())
                : destination.resolve(window.document.getFileName());
            assertFalse(Files.exists(window.document));
            assertEquals("original", Files.readString(moved), "Moving does not save the draft");
            assertEquals(moved, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(id, document.documentId());
            assertEquals(cursor, window.active().state().getCursor());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertTrue(window.active().state().isDirty());
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
            window.command(TuinkCommand.EDIT_REDO);
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> !window.documentIo.busy());
            assertEquals("draft original", Files.readString(moved));
            assertFalse(Files.exists(window.document), "Save must not recreate the old path");
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void deletingAnOpenDirtyFileKeepsItsBufferAndRequiresSaveAs() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var original = window.active();
            openTree(window);
            select(window, window.document);
            window.command(TuinkCommand.EXPLORER_DELETE);
            awaitDialog(window);
            assertEquals(ExplorerFileDialogView.Target.CANCEL,
                window.controller.explorerFileDialog().focus.focused().orElseThrow());
            chooseApply(window);
            window.await(() -> window.controller.explorerFileDialog() == null && !window.documentIo.busy());

            assertFalse(Files.exists(window.document));
            assertSame(original.documentSession(), window.active().documentSession());
            assertEquals(original.presentationId(), window.selected().presentationId());
            assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
            assertTrue(original.state().isDirty());
            assertNull(original.state().getCurrentResource());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertTrue(original.uiState().modalState.isSaveAs());
            Path copy = directory.resolve("saved-copy.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(copy.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy() && original.uiState().modalState.isNone());
            assertEquals("draft original", Files.readString(copy));
            assertFalse(Files.exists(window.document));
        }
    }

    @Test
    void moveDialogCanBeCancelledAfterASmallResizeWithoutChangingTheSource() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openTree(window);
            select(window, window.document);
            window.command(TuinkCommand.EXPLORER_MOVE);
            awaitDialog(window);

            window.resize(6, 20);
            assertNotNull(window.controller.explorerFileDialog());
            assertEquals(ActiveSurface.ModalScope.WINDOW, window.controller.activeSurface().modalScope());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertNull(window.controller.explorerFileDialog());
            assertFalse(window.documentIo.busy());
            assertEquals("original", Files.readString(window.document));
            window.resize(24, 80);
            assertEquals(directory, window.ui.sidebar.root());
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void frozenPreviewRetainsItsTextAndTracksMoveOrDeletion(boolean delete) throws Exception {
        Path destination = Files.createDirectory(directory.resolve("destination"));
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# original"))) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.TAB_CLOSE);
            var preview = assertInstanceOf(PreviewTab.class, window.selected());
            var frozen = assertInstanceOf(PreviewTab.ClosedSnapshot.class, preview.source());
            var snapshot = frozen.source().text();
            var presentation = preview.presentationId();
            openTree(window);
            select(window, window.document);
            window.command(delete ? TuinkCommand.EXPLORER_DELETE : TuinkCommand.EXPLORER_MOVE);
            awaitDialog(window);
            if (!delete) chooseDestination(window, destination);
            chooseApply(window);
            window.await(() -> window.controller.explorerFileDialog() == null && !window.documentIo.busy());

            var retained = assertInstanceOf(PreviewTab.class, window.selected());
            var retainedFrozen = assertInstanceOf(PreviewTab.ClosedSnapshot.class, retained.source());
            assertEquals(presentation, retained.presentationId());
            assertSame(frozen.subject(), retainedFrozen.subject());
            assertSame(snapshot, retainedFrozen.source().text());
            assertEquals("# original", retainedFrozen.source().text().lineText(0).toString());
            assertFalse(Files.exists(window.document));
            if (delete) assertNull(retainedFrozen.source().binding().resource());
            else {
                Path moved = destination.resolve("document.md");
                assertEquals(moved, JvmDocumentStorage.pathOf(retainedFrozen.source().binding().resource()));
                assertEquals("# original", Files.readString(moved));
            }
        }
    }

    private static void openTree(EditorWindowFixture window) throws Exception {
        window.controller.openFolder(window.document.getParent());
        window.await(() -> !window.ui.sidebar.tree.loading());
    }

    private static void select(EditorWindowFixture window, Path path) {
        assertTrue(window.ui.sidebar.tree.select(path));
        window.frame();
    }

    private static void awaitDialog(EditorWindowFixture window) throws Exception {
        window.await(() -> {
            var dialog = window.controller.explorerFileDialog();
            return dialog != null && dialog.inspected != null && !window.documentIo.busy()
                && (dialog.tree == null || !dialog.tree.loading());
        });
    }

    private static void chooseDestination(EditorWindowFixture window, Path destination) throws Exception {
        window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
        window.event(new PasteEvent(destination.toString()));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        window.await(() -> !window.controller.explorerFileDialog().tree.loading());
        assertEquals(destination, window.controller.explorerFileDialog().destinationFolder());
    }

    private static void chooseApply(EditorWindowFixture window) {
        var dialog = window.controller.explorerFileDialog();
        for (int index = 0; index < 5 && dialog.focus.focused().orElse(null) != ExplorerFileDialogView.Target.APPLY; index++)
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
        assertEquals(ExplorerFileDialogView.Target.APPLY, dialog.focus.focused().orElseThrow());
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }
}
