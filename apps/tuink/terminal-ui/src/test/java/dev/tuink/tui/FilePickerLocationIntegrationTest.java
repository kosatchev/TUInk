package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePickerLocationIntegrationTest {
    @TempDir Path directory;

    @Test
    void absolutePastedPathUsesTheExistingOpenPipelineAndKeepsTheDirtyOriginal() throws Exception {
        Path target = Files.writeString(directory.resolve("формулы and notes.md"), "selected\r\ncontents");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var original = window.active();
            open(window);
            location(window);
            assertEquals(directory.toString(), original.uiState().openFileFocusState.location().selectedText());
            window.event(new PasteEvent(target.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(2, window.tabs.size());
            assertEquals(target, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(List.of("selected", "contents"), window.active().tabSession().model().snapshotLines());
            assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
            assertTrue(original.state().isDirty());
        }
    }

    @Test
    void relativeDirectoriesNavigateAndBackspaceStillGoesToTheParent() throws Exception {
        Path child = Files.createDirectory(directory.resolve("child dir"));
        Path target = Files.writeString(child.resolve("note.txt"), "child file");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            open(window);
            location(window);
            window.event(new TextInput("child dir"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.tree.loading());
            assertEquals(child, window.tree.root());
            assertTrue(window.active().uiState().openFileMode);
            assertEquals(OpenFileInputRouter.Focus.LIST, window.active().uiState().openFileFocusState.focus());
            window.event(KeyStroke.of(KeyStroke.Key.BACKSPACE));
            window.await(() -> !window.tree.loading());
            assertEquals(directory, window.tree.root());
            location(window);
            window.event(new TextInput("child dir/note.txt"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(target, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
        }
    }

    @Test
    void failedPathKeepsTheDraftAndSupportsClipboardEditingAndRetry() throws Exception {
        Path target = Files.writeString(directory.resolve("valid.txt"), "valid");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var original = window.active();
            open(window);
            location(window);
            window.event(new TextInput("missing.txt"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertSame(original, window.active());
            assertTrue(original.uiState().openFileMode);
            assertEquals(OpenFileInputRouter.Focus.LOCATION, original.uiState().openFileFocusState.focus());
            assertEquals("missing.txt", original.uiState().openFileFocusState.location().text());
            assertTrue(original.uiState().pickerNotice.summary().contains("no longer exists"));
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(KeyStroke.of(KeyStroke.Key.C, KeyStroke.Modifier.CTRL));
            assertEquals("missing.txt", window.host.clipboard.text());
            window.event(KeyStroke.of(KeyStroke.Key.X, KeyStroke.Modifier.CTRL));
            assertEquals("", original.uiState().openFileFocusState.location().text());
            window.host.clipboard.setText(target.toString());
            window.event(KeyStroke.of(KeyStroke.Key.V, KeyStroke.Modifier.CTRL));
            assertEquals(target.toString(), original.uiState().openFileFocusState.location().text());
            assertEquals(null, original.uiState().pickerNotice);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(2, window.tabs.size());
            assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
        }
    }

    @Test
    void locationSelectionAndCaretSurviveCompactResizeAndCancelDoesNotEditTheDocument() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            open(window);
            location(window);
            window.event(new TextInput("a-long-path.txt"));
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT));
            var state = window.active().uiState().openFileFocusState;
            assertEquals("a", state.location().selectedText());
            window.resize(2, 12);
            window.event(new TextInput("must not edit a hidden field"));
            window.resize(24, 80);
            assertEquals(OpenFileInputRouter.Focus.LOCATION, state.focus());
            assertEquals("a-long-path.txt", state.location().text());
            assertEquals("a", state.location().selectedText());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.active().uiState().openFileMode);
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void hiddenToggleHasBothAnUnambiguousShortcutAndAKeyboardFocusStop() throws Exception {
        Files.writeString(directory.resolve(".hidden.txt"), "hidden");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            open(window);
            assertFalse(window.tree.showHiddenFiles());
            window.event(KeyStroke.of(KeyStroke.Key.PERIOD, KeyStroke.Modifier.ALT));
            window.await(() -> !window.tree.loading());
            assertTrue(window.tree.showHiddenFiles());
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(OpenFileInputRouter.Focus.LOCATION, window.active().uiState().openFileFocusState.focus());
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(OpenFileInputRouter.Focus.HIDDEN, window.active().uiState().openFileFocusState.focus());
            window.event(KeyStroke.of(KeyStroke.Key.SPACE));
            window.await(() -> !window.tree.loading());
            assertFalse(window.tree.showHiddenFiles());
            assertEquals(OpenFileInputRouter.Focus.HIDDEN, window.active().uiState().openFileFocusState.focus());
        }
    }

    @Test
    void okOpensTheEditedLocationInsteadOfAnUnrelatedTreeSelection() throws Exception {
        Path target = Files.writeString(directory.resolve("target.txt"), "target");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            open(window);
            location(window);
            window.event(new TextInput("target.txt"));
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(OpenFileInputRouter.Focus.OK, window.active().uiState().openFileFocusState.focus());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(target, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
        }
    }

    @Test
    void reopeningTheCurrentPathKeepsItsUnsavedTextAndUndoHistory() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var original = window.active();
            open(window);
            location(window);
            window.event(new TextInput(window.document.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(1, window.tabs.size());
            assertSame(original, window.active());
            assertTrue(original.state().isDirty());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("original"), original.tabSession().model().snapshotLines());
        }
    }

    @Test
    void directoryInspectionRunsOnTheDocumentWorker() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        Path target = directory.resolve("created-after-submission");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), tasks::addLast)) {
            open(window);
            location(window);
            window.event(new TextInput(target.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(1, tasks.size());
            assertEquals(directory, window.tree.root());
            Files.createDirectory(target);
            tasks.removeFirst().run();
            window.frame();
            window.await(() -> !window.tree.loading());
            assertEquals(target, window.tree.root());
            assertTrue(window.active().uiState().openFileMode);
            assertEquals(1, window.tabs.size());
        }
    }

    private static void open(EditorWindowFixture window) throws Exception {
        window.command(TuinkCommand.DOCUMENT_OPEN);
        window.await(() -> !window.tree.loading());
    }

    private static void location(EditorWindowFixture window) {
        window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
        assertEquals(OpenFileInputRouter.Focus.LOCATION, window.active().uiState().openFileFocusState.focus());
    }
}
