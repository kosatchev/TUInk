package dev.tuink.tui;

import dev.tuiframe.explorer.Explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderExplorerIntegrationTest {
    @TempDir Path directory;

    @Test
    void explorerWithRetainedFindBlocksReplaceAndEscapeReallyFocusesTheDocument() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("needle middle needle"))) {
            open(window, directory);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("needle"));
            window.focusSearch(SearchFocus.REPLACE);
            window.event(new TextInput("replacement"));
            long revision = window.active().tabSession().model().revision();

            window.event(KeyStroke.of(KeyStroke.Key.E, KeyStroke.Modifier.CTRL));
            assertTrue(window.ui.explorer.focused);
            assertTrue(window.active().uiState().searchMode);
            window.event(KeyStroke.of(KeyStroke.Key.R, KeyStroke.Modifier.CTRL));
            window.command(TuinkCommand.SEARCH_REPLACE_ALL);
            assertEquals(List.of("needle middle needle"), window.active().tabSession().model().snapshotLines());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.ui.explorer.focused);
            assertTrue(window.active().uiState().searchMode);
            assertTrue(window.active().uiState().searchEditorFocused);
            window.event(new TextInput("inserted"));
            assertTrue(window.active().tabSession().model().getLine(0).contains("inserted"));
            assertTrue(window.active().state().isDirty());
            assertEquals("needle", window.query.text());
            assertEquals("replacement", window.replacement.text());
        }
    }

    @Test
    void rightClickOnAnEmptySidebarDoesNotOpenTheFolderPicker() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(KeyStroke.of(KeyStroke.Key.E, KeyStroke.Modifier.CTRL));
            var explorer = window.ui.explorer;
            assertTrue(explorer.visible);
            assertNull(explorer.tree);
            var bounds = explorer.placement.bounds();
            window.event(new PointerEvent(bounds.y() + 1, bounds.x() + 1,
                PointerEvent.Kind.PRESS, PointerEvent.Button.RIGHT, Set.of()));
            assertFalse(window.active().uiState().openFileMode);
            assertTrue(window.active().uiState().modalState.isNone());
            assertNull(explorer.tree);
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void saveAsRefreshesAnExpandedFolderWithoutResettingTheExplorer() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("folder"));
        Path target = folder.resolve("new-document.txt");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            open(window, directory);
            var tree = window.ui.explorer.tree;
            select(window, folder);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !tree.loading());
            var selected = tree.state().selectedId();
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy() && !tree.loading());
            assertEquals("draft original", Files.readString(target));
            assertSame(tree, window.ui.explorer.tree);
            assertEquals(selected, tree.state().selectedId());
            window.event(KeyStroke.of(KeyStroke.Key.E, KeyStroke.Modifier.CTRL));
            select(window, target);
            assertEquals(target, tree.state().selectedId().path());
        }
    }

    @Test
    void folderInspectionUpdatesItsOwningPickerAfterSwitchingTabs() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        Path target = directory.resolve("created-after-submission");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), tasks::addLast)) {
            var owner = window.active();
            window.command(TuinkCommand.FOLDER_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(1, tasks.size());
            assertEquals(directory, owner.uiState().openFileTree.root());
            window.command(TuinkCommand.TAB_NEW);
            var active = window.active();
            assertEquals(2, window.tabs.size());

            Files.createDirectory(target);
            tasks.removeFirst().run();
            window.frame();
            window.await(() -> !owner.uiState().openFileTree.loading());
            assertEquals(target, owner.uiState().openFileTree.root());
            assertTrue(owner.uiState().openFileMode);
            assertEquals(OpenFileInputRouter.Focus.LIST, owner.uiState().openFileFocusState.focus());
            assertSame(active, window.active());
            assertFalse(active.uiState().openFileMode);
            assertNull(window.ui.explorer.root());
        }
    }

    @Test
    void cancellingAnotherTabsFolderPickerDoesNotCancelTheOwnersInspection() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        Path target = Files.createDirectory(directory.resolve("target"));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), tasks::addLast)) {
            open(window, directory);
            var owner = window.active();
            window.command(TuinkCommand.FOLDER_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(1, tasks.size());
            window.command(TuinkCommand.TAB_NEW);
            var active = window.active();
            window.command(TuinkCommand.FOLDER_OPEN);
            assertTrue(active.uiState().openFileMode);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(active.uiState().openFileMode);
            assertTrue(window.documentIo.busy());

            tasks.removeFirst().run();
            window.frame();
            window.await(() -> !owner.uiState().openFileTree.loading());
            assertEquals(target, owner.uiState().openFileTree.root());
            assertTrue(owner.uiState().openFileMode);
            assertSame(active, window.active());
            assertFalse(active.uiState().openFileMode);
        }
    }

    @Test
    void closingTheFolderRevokesAPendingChooseBeforeItsLateCompletion() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        Path target = Files.createDirectory(directory.resolve("target"));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), tasks::addLast)) {
            open(window, directory);
            window.command(TuinkCommand.FOLDER_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.tree.loading());
            assertEquals(target, window.tree.root());
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(OpenFileInputRouter.Focus.OK, window.active().uiState().openFileFocusState.focus());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(1, tasks.size());
            assertTrue(window.documentIo.busy());
            window.command(TuinkCommand.TAB_NEW);
            var active = window.active();
            window.command(TuinkCommand.FOLDER_CLOSE);
            assertNull(window.ui.explorer.root());
            assertFalse(window.documentIo.busy());

            tasks.removeFirst().run();
            window.frame();
            assertNull(window.ui.explorer.root());
            assertFalse(window.ui.explorer.visible);
            assertSame(active, window.active());
            assertEquals(2, window.tabs.size());
        }
    }

    @Test
    void expandedTreeSelectionAndScrollSurviveTabSwitchesAndVisibilityChanges() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("folder"));
        for (int index = 0; index < 35; index++) {
            Files.writeString(folder.resolve("child-%02d-with-a-long-name.txt".formatted(index)), "child");
        }
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var original = window.active();
            open(window, directory);
            var explorer = window.ui.explorer;
            var tree = explorer.tree;
            select(window, folder);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !tree.loading());
            select(window, folder.resolve("child-34-with-a-long-name.txt"));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT));
            var selected = tree.state().selectedId();
            int firstVisible = tree.state().widgetState().firstVisibleIndex();
            int horizontal = tree.state().widgetState().horizontalOffset();
            assertTrue(firstVisible > 0);
            assertTrue(horizontal > 0);

            window.command(TuinkCommand.TAB_NEW);
            assertEquals(2, window.tabs.size());
            window.command(TuinkCommand.TAB_PREVIOUS);
            assertSame(original, window.active());
            window.command(TuinkCommand.EXPLORER_TOGGLE);
            assertFalse(explorer.visible);
            assertNull(explorer.placement);
            window.command(TuinkCommand.EXPLORER_TOGGLE);
            assertTrue(explorer.visible);
            assertSame(tree, explorer.tree);
            assertEquals(directory, explorer.root());
            assertEquals(selected, tree.state().selectedId());
            assertEquals(firstVisible, tree.state().widgetState().firstVisibleIndex());
            assertEquals(horizontal, tree.state().widgetState().horizontalOffset());
            assertEquals(List.of("original"), original.tabSession().model().snapshotLines());
        }
    }

    @Test
    void explorerFocusCapturesEditingInputAndEscapeReturnsToTheDocument() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var original = window.active();
            var cursor = original.state().getCursor();
            long revision = original.tabSession().model().revision();
            open(window, directory);
            window.host.clipboard.setText("clipboard input");
            window.event(new TextInput("tree input"));
            window.event(new PasteEvent("pasted input"));
            window.event(KeyStroke.of(KeyStroke.Key.DELETE));
            window.event(KeyStroke.of(KeyStroke.Key.BACKSPACE));
            window.event(KeyStroke.of(KeyStroke.Key.V, KeyStroke.Modifier.CTRL));
            window.event(KeyStroke.of(KeyStroke.Key.X, KeyStroke.Modifier.CTRL));
            window.event(KeyStroke.of(KeyStroke.Key.Z, KeyStroke.Modifier.CTRL));
            assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
            assertEquals(revision, original.tabSession().model().revision());
            assertEquals(cursor, original.state().getCursor());
            assertTrue(original.state().isDirty());
            assertTrue(window.ui.explorer.focused);

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.ui.explorer.focused);
            assertTrue(window.ui.explorer.visible);
            window.event(new TextInput("after "));
            assertEquals(List.of("draft after original"), original.tabSession().model().snapshotLines());
            window.event(KeyStroke.of(KeyStroke.Key.E, KeyStroke.Modifier.CTRL));
            assertTrue(window.ui.explorer.focused);
            window.event(KeyStroke.of(KeyStroke.Key.E, KeyStroke.Modifier.CTRL));
            assertTrue(window.ui.explorer.focused);
        }
    }

    @Test
    void openingFilesReusesExistingDocumentsAndClosingTheFolderKeepsDirtyTabs() throws Exception {
        Path other = Files.writeString(directory.resolve("z-other.txt"), "other");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var original = window.active();
            open(window, directory);
            select(window, other);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(2, window.tabs.size());
            assertEquals(other, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertFalse(window.ui.explorer.focused);
            assertTrue(window.ui.explorer.visible);
            assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
            var otherTab = window.active();
            window.event(new TextInput("modified "));

            window.event(KeyStroke.of(KeyStroke.Key.E, KeyStroke.Modifier.CTRL));
            select(window, window.document);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertSame(original, window.active());
            assertEquals(2, window.tabs.size());
            assertTrue(original.state().isDirty());

            window.event(KeyStroke.of(KeyStroke.Key.E, KeyStroke.Modifier.CTRL));
            select(window, other);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertSame(otherTab, window.active());
            assertEquals(2, window.tabs.size());
            assertEquals(List.of("modified other"), otherTab.tabSession().model().snapshotLines());
            window.command(TuinkCommand.FOLDER_CLOSE);
            assertNull(window.ui.explorer.root());
            assertFalse(window.ui.explorer.visible);
            assertEquals(2, window.tabs.size());
            assertSame(otherTab, window.active());
            assertTrue(otherTab.state().isDirty());
            assertEquals("other", Files.readString(other));
        }
    }

    @Test
    void folderPickerCanChooseAnEmptyDirectoryAndCancelLeavesTheExistingFolder() throws Exception {
        Path empty = Files.createDirectory(directory.resolve("empty"));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var original = window.active();
            window.command(TuinkCommand.FOLDER_OPEN);
            window.await(() -> !window.tree.loading());
            assertTrue(original.uiState().choosingFolder);
            window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
            window.event(new PasteEvent(empty.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.tree.loading());
            assertEquals(empty, window.tree.root());
            assertTrue(original.uiState().openFileMode);
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(OpenFileInputRouter.Focus.OK, original.uiState().openFileFocusState.focus());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> window.ui.explorer.tree != null && !window.ui.explorer.tree.loading());
            assertFalse(original.uiState().openFileMode);
            assertEquals(empty, window.ui.explorer.root());
            assertTrue(window.ui.explorer.visible);
            assertEquals(1, window.tabs.size());
            assertSame(original, window.active());

            var tree = window.ui.explorer.tree;
            window.command(TuinkCommand.FOLDER_OPEN);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(original.uiState().openFileMode);
            assertSame(tree, window.ui.explorer.tree);
            assertEquals(empty, window.ui.explorer.root());
            assertEquals(List.of("original"), original.tabSession().model().snapshotLines());
        }
    }

    @Test
    void aDisappearingFileDoesNotOpenTheModalPickerOrChangeTheDirtyDocument() throws Exception {
        Path target = Files.writeString(directory.resolve("z-disappearing.txt"), "selected");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var original = window.active();
            open(window, directory);
            select(window, target);
            Files.delete(target);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertFalse(original.uiState().openFileMode);
            assertSame(original, window.active());
            assertEquals(1, window.tabs.size());
            assertTrue(window.ui.explorer.visible);
            assertEquals(directory, window.ui.explorer.root());
            assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
            assertTrue(original.state().isDirty());
        }
    }

    @Test
    void typedFilePathInFolderPickerIsRejectedWithoutOpeningTheFile() throws Exception {
        Path target = Files.writeString(directory.resolve("z-not-a-folder.txt"), "selected");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var original = window.active();
            window.command(TuinkCommand.FOLDER_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
            window.event(new PasteEvent(target.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(original.uiState().openFileMode);
            assertTrue(original.uiState().choosingFolder);
            assertTrue(original.uiState().pickerNotice != null);
            assertSame(original, window.active());
            assertEquals(1, window.tabs.size());
            assertNull(window.ui.explorer.root());
            assertEquals(directory, window.tree.root());
            assertEquals(List.of("original"), original.tabSession().model().snapshotLines());
        }
    }

    private static void open(EditorWindowFixture window, Path root) throws Exception {
        window.controller.openFolder(root);
        window.await(() -> window.ui.explorer.tree != null && !window.ui.explorer.tree.loading());
        assertTrue(window.ui.explorer.visible);
        assertTrue(window.ui.explorer.focused);
    }

    private static void select(EditorWindowFixture window, Path path) {
        window.event(KeyStroke.of(KeyStroke.Key.HOME));
        for (int attempt = 0; attempt < 100; attempt++) {
            var selected = window.ui.explorer.placement.tree().selectedEntry();
            if (selected.isPresent() && selected.orElseThrow().path().equals(path)) return;
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
        }
        throw new AssertionError("Explorer entry not found: " + path);
    }
}
