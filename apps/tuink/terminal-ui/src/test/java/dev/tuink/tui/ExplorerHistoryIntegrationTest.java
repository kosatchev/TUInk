package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.explorer.Explorer;
import dev.tuiframe.explorer.file.ExplorerFileService;
import dev.tuiframe.explorer.file.LinuxTrashProvider;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.editor.session.document.DocumentSaveSubject;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

class ExplorerHistoryIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void physicalFileUndoRedoPreserveDirtyTextHistoryAndFollowAnAcceptedAtomicSave(boolean rename) throws Exception {
        Path destination = Files.createDirectory(directory.resolve("destination"));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var subject = window.active().documentSession().document();
            var document = subject.documentId();
            long revision = window.active().tabSession().model().revision();
            open(window);
            Path relocated = rename ? directory.resolve("renamed.txt") : destination.resolve(window.document.getFileName());
            relocate(window, rename, destination, "renamed.txt");
            assertEquals(relocated, JvmDocumentStorage.pathOf(subject.binding().resource()));
            assertTrue(window.ui.explorer.canUndo());
            assertTrue(window.ui.explorer.focused);

            window.event(ctrl(KeyStroke.Key.Z));
            completed(window);
            assertTrue(Files.exists(window.document));
            assertFalse(Files.exists(relocated));
            assertEquals(window.document, JvmDocumentStorage.pathOf(subject.binding().resource()));
            assertEquals(document, subject.documentId());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.active().state().isDirty());
            assertTrue(window.ui.explorer.canRedo());

            window.event(ctrl(KeyStroke.Key.Y));
            completed(window);
            assertEquals(relocated, JvmDocumentStorage.pathOf(subject.binding().resource()));
            assertFalse(Files.exists(window.document));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.ui.explorer.focused);
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
            assertTrue(Files.exists(relocated), "Text Undo must not traverse filesystem history");
            window.event(ctrl(KeyStroke.Key.Y));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> !window.documentIo.busy() && !window.active().state().isDirty());
            assertEquals("draft original", Files.readString(relocated));

            window.command(TuinkCommand.EXPLORER_FOCUS);
            window.event(ctrl(KeyStroke.Key.Z));
            completed(window);
            assertEquals("draft original", Files.readString(window.document),
                "An accepted atomic Save refreshes the recorded identity before filesystem Undo");
            assertFalse(Files.exists(relocated));
            assertEquals(window.document, JvmDocumentStorage.pathOf(subject.binding().resource()));
            assertFalse(window.active().state().isDirty());
            window.event(ctrl(KeyStroke.Key.Y));
            completed(window);
            assertEquals("draft original", Files.readString(relocated));
            assertFalse(Files.exists(window.document));
        }
    }



    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitExplorerHistoryCommandsWorkFromSourceAndPreview(boolean preview) throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# original"))) {
            open(window);
            relocate(window, true, directory, "renamed.md");
            Path renamed = directory.resolve("renamed.md");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            if (preview) {
                window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
                window.await(() -> window.preview().ready());
                assertInstanceOf(PreviewTab.class, window.selected());
            }
            assertFalse(window.ui.explorer.focused);
            var presentation = window.selected().presentationId();
            window.command(TuinkCommand.EXPLORER_UNDO);
            completed(window);
            assertTrue(Files.exists(window.document));
            assertFalse(Files.exists(renamed));
            assertEquals(presentation, window.selected().presentationId());
            if (preview) window.await(() -> window.preview().ready());
            window.command(TuinkCommand.EXPLORER_REDO);
            completed(window);
            assertFalse(Files.exists(window.document));
            assertEquals("# original", Files.readString(renamed));
            assertEquals(presentation, window.selected().presentationId());
        }
    }

    @Test
    void searchFieldsAndReadOnlyPopupNeverUseTheSidebarFilesystemHistory() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            open(window);
            relocate(window, true, directory, "renamed.txt");
            Path renamed = directory.resolve("renamed.txt");
            long history = window.ui.explorer.tree.operations().historyVersion();
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("query"));
            window.event(ctrl(KeyStroke.Key.Z));
            assertTrue(Files.exists(renamed));
            assertFalse(Files.exists(window.document));
            assertEquals(history, window.ui.explorer.tree.operations().historyVersion());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.event(ctrl(KeyStroke.Key.L));
            window.event(new TextInput(directory.resolve("uncommitted-path").toString()));
            window.event(ctrl(KeyStroke.Key.Z));
            assertTrue(window.selected().uiState().openFileMode);
            assertEquals(Explorer.Access.READ_ONLY, window.selected().uiState().openFileTree.access());
            assertTrue(Files.exists(renamed));
            assertFalse(Files.exists(window.document));
            assertEquals(history, window.ui.explorer.tree.operations().historyVersion());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
        }
    }

    @Test
    void savedCreatedFileCannotBeUndoneAndPermanentDeletionCreatesAHistoryBoundary() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            open(window);
            select(window, directory);
            window.command(TuinkCommand.EXPLORER_NEW_FILE);
            window.event(new TextInput("created.txt"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> window.controller.explorerFileDialog() == null && !window.documentIo.busy()
                && window.tabs.size() == 2);
            Path created = directory.resolve("created.txt");
            window.event(new TextInput("saved content"));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> !window.documentIo.busy() && !window.active().state().isDirty());
            assertEquals("saved content", Files.readString(created));
            var operations = window.ui.explorer.tree.operations();
            long version = operations.historyVersion();
            window.command(TuinkCommand.EXPLORER_FOCUS);
            window.event(ctrl(KeyStroke.Key.Z));
            completed(window);
            assertEquals("saved content", Files.readString(created));
            assertEquals(version, operations.historyVersion());
            assertTrue(operations.canUndo(), "A failed inverse operation keeps its history entry");
            assertTrue(window.ui.explorer.tree.noticeIsError());
            assertEquals(created, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));

            select(window, created);
            window.command(TuinkCommand.EXPLORER_DELETE);
            inspected(window);
            apply(window);
            completed(window);
            assertFalse(Files.exists(created));
            assertFalse(operations.canUndo());
            assertFalse(operations.canRedo());
            assertNotNull(operations.historyBarrier());
            assertNull(window.active().state().getCurrentResource());
            assertEquals(List.of("saved content"), window.active().tabSession().model().snapshotLines());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("saved content"), window.active().tabSession().model().snapshotLines(),
                "Empty file history must not fall through into the document's text history");
        }
    }

    @Test
    void historyWarningsUseTheSidebarFooterAndThePreferenceOnlySuppressesThoseWarnings() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            open(window);
            relocate(window, true, directory, "renamed.txt");
            window.notification.info("DOCUMENT_NOTICE");
            window.frame();
            window.event(ctrl(KeyStroke.Key.Z));
            completed(window);
            var explorer = window.ui.explorer.tree;
            assertEquals("Undo Rename", explorer.noticeText());
            int left = window.ui.explorer.placement.documentBounds().x();
            int row = rowContaining(window, "Undo", 0, left);
            assertEquals(window.host.styles.windows().notifyWarning(), window.screenStyle(row, 0));
            int documentNotice = rowContaining(window, "DOCUMENT_NOTICE", left, window.width);
            assertEquals(left, window.screenRow(documentNotice).indexOf("DOCUMENT_NOTICE"));
            assertEquals("DOCUMENT_NOTICE", window.notification.message);
            window.command(TuinkCommand.SETTINGS_TOGGLE_FILE_HISTORY_NOTIFICATIONS);
            assertFalse(window.ui.showFileHistoryNotificationsSetting);
            String afterPreference = window.notification.message;
            window.event(ctrl(KeyStroke.Key.Y));
            completed(window);
            assertNull(explorer.noticeText());
            assertEquals(afterPreference, window.notification.message);
        }
    }

    @Test
    void trashUsesAnInjectedPrivateProviderAndKeepsTheOpenTextAsADraft() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        long uid = ((Number) Files.getAttribute(directory, "unix:uid")).longValue();
        Path data = directory.resolve("isolated-system-data");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            open(window);
            window.ui.explorer.tree.close();
            window.ui.explorer.tree = new Explorer(directory, false, false, Explorer.Access.MANAGE_FILES,
                new ExplorerFileService(new LinuxTrashProvider(data, uid)));
            window.ui.explorer.placement = null;
            window.await(() -> !window.ui.explorer.tree.loading());
            select(window, window.document);
            window.command(TuinkCommand.EXPLORER_TRASH);
            inspected(window);
            apply(window);
            completed(window);
            assertFalse(Files.exists(window.document));
            try (var entries = Files.list(data.resolve("Trash/files"))) { assertEquals(1L, entries.count()); }
            try (var info = Files.list(data.resolve("Trash/info"))) {
                Path entry = info.findFirst().orElseThrow();
                assertTrue(Files.readString(entry).contains("Path="));
            }
            assertNull(window.active().state().getCurrentResource());
            assertTrue(window.active().state().isDirty());
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            var document = window.active().documentSession().document().documentId();
            assertTrue(window.host.deletedNotices.visible(document));
            assertFalse(window.ui.explorer.canUndo());
            window.event(ctrl(KeyStroke.Key.Z));
            assertFalse(Files.exists(window.document));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
    private static void open(EditorWindowFixture window) throws Exception {
        window.controller.openFolder(window.document.getParent());
        window.await(() -> !window.ui.explorer.tree.loading());
    }
    private static void select(EditorWindowFixture window, Path path) throws Exception {
        window.await(() -> !window.ui.explorer.tree.loading());
        assertTrue(window.ui.explorer.tree.select(path));
        window.frame();
    }
    private static void relocate(EditorWindowFixture window, boolean rename, Path destination, String name) throws Exception {
        select(window, window.document);
        window.command(rename ? TuinkCommand.EXPLORER_RENAME : TuinkCommand.EXPLORER_MOVE);
        inspected(window);
        if (rename) window.event(new TextInput(name));
        else {
            window.event(ctrl(KeyStroke.Key.L));
            window.event(new PasteEvent(destination.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.controller.explorerFileDialog().tree.loading());
        }
        if (rename) window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        else apply(window);
        completed(window);
    }
    private static void transfer(EditorWindowFixture window, TuinkCommand command, Path source, Path destination) throws Exception {
        select(window, source);
        window.command(command);
        inspected(window);
        window.event(ctrl(KeyStroke.Key.L));
        window.event(new PasteEvent(destination.toString()));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        window.await(() -> !window.controller.explorerFileDialog().tree.loading());
        apply(window);
        completed(window);
    }
    private static void inspected(EditorWindowFixture window) throws Exception {
        window.await(() -> {
            var dialog = window.controller.explorerFileDialog();
            return dialog != null && dialog.inspected != null && !window.documentIo.busy()
                && (dialog.tree == null || !dialog.tree.loading());
        });
    }
    private static void apply(EditorWindowFixture window) {
        var dialog = window.controller.explorerFileDialog();
        for (int index = 0; index < 5 && dialog.focus.focused().orElse(null) != ExplorerFileDialogView.Target.APPLY; index++)
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
        assertEquals(ExplorerFileDialogView.Target.APPLY, dialog.focus.focused().orElseThrow());
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }
    private static void completed(EditorWindowFixture window) throws Exception {
        window.await(() -> !window.documentIo.busy() && window.controller.explorerFileDialog() == null);
    }
    private static int rowContaining(EditorWindowFixture window, String text, int left, int right) {
        for (int row = 0; row < window.height; row++) if (window.screenRow(row).substring(left, right).contains(text)) return row;
        throw new AssertionError("Missing " + text + " in region " + left + ".." + right);
    }
}
