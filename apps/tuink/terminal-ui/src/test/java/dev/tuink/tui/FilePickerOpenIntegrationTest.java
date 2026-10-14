package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.SaveDisposition;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePickerOpenIntegrationTest {
    @TempDir
    Path directory;

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
            assertEquals(window.document, original.state().getCurrentFile());
            assertEquals(SaveDisposition.REPLACE_EXISTING, original.state().getSaveDisposition());
            assertTrue(original.state().isDirty());
            assertEquals(revision, original.tabSession().model().revision());
            assertEquals("Open failed: file no longer exists: " + target, window.notification.message);
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
            assertEquals(target, window.active().state().getCurrentFile());
            assertEquals(SaveDisposition.REPLACE_EXISTING, window.active().state().getSaveDisposition());
            assertEquals(List.of("selected", "contents"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
            assertEquals("Opened " + target, window.notification.message);
            window.event(new TextInput("after "));
            assertEquals(List.of("after selected", "contents"), window.active().tabSession().model().snapshotLines());
        }
    }

    private static void selectLastFile(EditorWindowFixture window, Path target) throws Exception {
        window.command(TuinkCommand.DOCUMENT_OPEN);
        window.await(() -> !window.tree.loading());
        window.event(KeyStroke.of(KeyStroke.Key.END));
        assertEquals(target, window.tree.state().selectedId().path());
    }
}
