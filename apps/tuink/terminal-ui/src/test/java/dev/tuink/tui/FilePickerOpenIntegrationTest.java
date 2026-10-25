package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.tui.command.TuinkCommand;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePickerOpenIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void oversizedSelectionKeepsThePickerDirtyTabAndUndoHistoryUsable() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path target = directory.resolve("z-oversized.txt");
        try (RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
            file.setLength(2_147_483_648L);
        }
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var original = window.active();
            long revision = original.tabSession().model().revision();
            selectLastFile(window, target);

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals(1, window.tabs.size());
            assertSame(original, window.active());
            assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
            assertEquals(revision, original.tabSession().model().revision());
            assertTrue(original.state().isDirty());
            assertPickerNotice(window, "File too large to open");
            window.event(new TextInput("captured by picker"));
            assertEquals("draft original", original.tabSession().model().getLine(0));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("original"), original.tabSession().model().snapshotLines());
            assertFalse(original.state().isDirty());
            window.event(new TextInput("after "));
            assertEquals("after original", original.tabSession().model().getLine(0));
        }
    }

    @Test
    void disappearingSelectionKeepsThePickerAndDirtyDocumentOpen() throws Exception {
        Path target = directory.resolve("z-selected.txt");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Files.writeString(target, "selected");
            window.event(new TextInput("draft "));
            TerminalEditorApp.EditorTab original = window.active();
            long revision = original.tabSession().model().revision();
            selectLastFile(window, target);
            Files.delete(target);

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals(1, window.tabs.size());
            assertSame(original, window.active());
            assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
            assertEquals(window.document, JvmDocumentStorage.pathOf(original.state().getCurrentResource()));
            assertEquals(SaveDisposition.REPLACE_EXISTING, original.state().getSaveDisposition());
            assertTrue(original.state().isDirty());
            assertEquals(revision, original.tabSession().model().revision());
            assertPickerNotice(window, "file no longer exists");
            assertFalse(Files.exists(target));
            assertEquals("original", Files.readString(window.document));

            // A failed submission retains modal input ownership until the user closes the picker.
            window.event(new TextInput("must stay in picker"));
            assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("original"), original.tabSession().model().snapshotLines());
            assertFalse(original.state().isDirty());
            window.event(new TextInput("after "));
            assertEquals(List.of("after original"), original.tabSession().model().snapshotLines());
        }
    }

    @Test
    void existingSelectionOpensItsContentsWithoutChangingTheDirtyOriginalTab() throws Exception {
        Path target = directory.resolve("z-selected.txt");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Files.writeString(target, "selected\r\ncontents");
            window.event(new TextInput("draft "));
            TerminalEditorApp.EditorTab original = window.active();
            selectLastFile(window, target);

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals(2, window.tabs.size());
            assertSame(original, window.tabs.getFirst());
            assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
            assertTrue(original.state().isDirty());
            assertEquals(target, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(SaveDisposition.REPLACE_EXISTING, window.active().state().getSaveDisposition());
            assertEquals(List.of("selected", "contents"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
            assertEquals("Opened " + target, window.notification.message);
            window.event(new TextInput("after "));
            assertEquals(List.of("after selected", "contents"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void reopeningTheDirtyFirstTabSwitchesBackWithItsCursorClipboardAndUndoHistory() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_LEFT));
            var original = window.active();
            var cursor = original.state().getCursor();
            var builder = window.context.renderPlanBuilder();
            window.host.clipboard.setText("shared clipboard");
            Path other = Files.writeString(directory.resolve("a-other.txt"), "other");
            window.command(TuinkCommand.TAB_NEW);
            window.active().tabSession().engine().open(JvmDocumentStorage.resource(other));
            window.frame();
            selectLastFile(window, window.document);

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals(2, window.tabs.size());
            assertSame(original, window.active());
            assertSame(builder, window.context.renderPlanBuilder());
            assertEquals(cursor, original.state().getCursor());
            assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
            assertEquals("shared clipboard", window.host.clipboard.text());
            assertEquals("Switched to " + window.document, window.notification.message);
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("original"), original.tabSession().model().snapshotLines());
            assertFalse(original.state().isDirty());
            assertEquals("original", Files.readString(window.document));
        }
    }

    @Test
    void selectingASymlinkAliasActivatesTheExistingDocumentAndSavingDoesNotConflictWithItself() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Path alias = Files.createSymbolicLink(directory.resolve("z-alias.txt"), window.document.getFileName());
            window.event(new TextInput("draft "));
            var original = window.active();
            selectLastFile(window, alias);

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.command(TuinkCommand.DOCUMENT_SAVE);

            assertEquals(1, window.tabs.size());
            assertSame(original, window.active());
            assertEquals(window.document, JvmDocumentStorage.pathOf(original.state().getCurrentResource()));
            assertEquals("draft original", Files.readString(window.document));
            assertFalse(original.state().isDirty());
            assertTrue(Files.isSymbolicLink(alias));
            assertTrue(window.notification.message.startsWith("Saved "), window.notification.message);
        }
    }

    @Test
    void aSelectionReplacedByADirectoryCanBeCancelledAndRetried() throws Exception {
        Path target = directory.resolve("z-selected.txt");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Files.writeString(target, "selected");
            window.event(new TextInput("draft "));
            var original = window.active();
            var builder = window.context.renderPlanBuilder();
            selectLastFile(window, target);
            Files.delete(target);
            Files.createDirectory(target);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(1, window.tabs.size());
            assertSame(original, window.active());
            assertSame(builder, window.context.renderPlanBuilder());
            assertPickerNotice(window, "Not a regular file");
            window.event(new TextInput("captured"));
            assertEquals("draft original", original.tabSession().model().getLine(0));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            Files.delete(target);
            Files.writeString(target, "retried");
            selectLastFile(window, target);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(2, window.tabs.size());
            assertSame(original, window.tabs.getFirst());
            assertTrue(original.state().isDirty());
            assertEquals("retried", window.active().tabSession().model().getLine(0));
            assertSame(window.active().tabSession().textLayout(), window.context.renderPlanBuilder().textLayout());
        }
    }

    private static void selectLastFile(EditorWindowFixture window, Path target) throws Exception {
        window.command(TuinkCommand.DOCUMENT_OPEN);
        window.await(() -> !window.tree.loading());
        window.event(KeyStroke.of(KeyStroke.Key.END));
        assertEquals(target, window.tree.state().selectedId().path());
    }

    private static void assertPickerNotice(EditorWindowFixture window, String text) {
        int row = dev.tuink.tui.ui.OpenFileDialogController.layoutForTerminal(window.height, window.width).bottom() - 1;
        assertTrue(window.screenRow(row).contains(text), window.screenRow(row));
    }
}
