package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExplorerRenameIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void renamingAnOpenDirtyFileOrItsFolderPreservesHistoryAndSavesToTheNewPath(boolean folder) throws Exception {
        Path sourceFolder = Files.createDirectory(directory.resolve("source"));
        try (var window = new EditorWindowFixture(directory, "source/document.txt", false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var subject = window.active().documentSession().document();
            var id = subject.documentId();
            var cursor = window.active().state().getCursor();
            long revision = window.active().tabSession().model().revision();
            Path selected = folder ? sourceFolder : window.document;
            beginRename(window, selected);
            var dialog = window.controller.explorerFileDialog();
            assertNull(dialog.tree, "Rename edits a basename without opening a destination picker");
            String oldName = selected.getFileName().toString();
            assertEquals(oldName, dialog.field.text());
            assertEquals(0, dialog.field.selectionStart());
            assertEquals(oldName.length(), dialog.field.selectionEnd());
            assertEquals(ExplorerFileDialogView.Target.FIELD, dialog.focus.focused().orElseThrow());

            window.event(new TextInput(folder ? "renamed-source" : "renamed.txt"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> window.controller.explorerFileDialog() == null && !window.documentIo.busy());

            Path renamed = folder ? directory.resolve("renamed-source/document.txt") : sourceFolder.resolve("renamed.txt");
            assertFalse(Files.exists(selected));
            assertEquals("original", Files.readString(renamed), "Renaming must not save the draft");
            assertEquals(renamed, JvmDocumentStorage.pathOf(subject.binding().resource()));
            assertEquals(id, subject.documentId());
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
            assertEquals("draft original", Files.readString(renamed));
            assertFalse(Files.exists(window.document), "Saving must not recreate the previous path");
        }
    }

    @Test
    void aRenameCollisionKeepsTheNameFieldAndBothFiles() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Path existing = Files.writeString(directory.resolve("existing.txt"), "existing");
            beginRename(window, window.document);
            window.event(new TextInput("existing.txt"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy());

            var dialog = window.controller.explorerFileDialog();
            assertNotNull(dialog);
            assertEquals("existing.txt", dialog.field.text());
            assertNotNull(dialog.error);
            assertEquals("original", Files.readString(window.document));
            assertEquals("existing", Files.readString(existing));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertNull(window.controller.explorerFileDialog());
        }
    }

    @Test
    void theSelectedRootCannotBeRenamed() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.controller.openFolder(directory);
            window.await(() -> !window.ui.explorer.tree.loading());
            assertTrue(window.ui.explorer.selectedRoot());

            window.command(TuinkCommand.EXPLORER_RENAME);

            assertNull(window.controller.explorerFileDialog());
            assertFalse(window.documentIo.busy());
            assertEquals("original", Files.readString(window.document));
            assertTrue(Files.isDirectory(directory));
        }
    }

    @Test
    void cancellingRenameKeepsTheOriginalEntryAndOpenBinding() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var binding = window.active().documentSession().document().binding();
            beginRename(window, window.document);
            window.event(new TextInput("cancelled.txt"));

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertNull(window.controller.explorerFileDialog());
            assertFalse(Files.exists(directory.resolve("cancelled.txt")));
            assertEquals("original", Files.readString(window.document));
            assertEquals(binding, window.active().documentSession().document().binding());
        }
    }

    private void beginRename(EditorWindowFixture window, Path selected) throws Exception {
        window.controller.openFolder(directory);
        window.await(() -> !window.ui.explorer.tree.loading());
        window.ui.explorer.tree.reveal(selected);
        window.await(() -> !window.ui.explorer.tree.loading() && window.ui.explorer.tree.selectedEntry() != null
            && selected.equals(window.ui.explorer.tree.selectedEntry().path()));
        window.command(TuinkCommand.EXPLORER_RENAME);
        window.await(() -> window.controller.explorerFileDialog() != null
            && window.controller.explorerFileDialog().inspected != null && !window.documentIo.busy());
    }
}
