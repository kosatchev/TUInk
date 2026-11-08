package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.explorer.file.FileTreeModel;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.PointerEvent;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderSelectionIntegrationTest {
    @TempDir Path directory;

    @Test void selectingAFolderUpdatesTheFieldAndConfirmationOpensThatFolder() throws Exception {
        Path selected = Files.createDirectory(directory.resolve("alpha"));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.controller.openFolder(directory);
            window.command(TuinkCommand.FOLDER_OPEN);
            var owner = window.active().uiState();
            window.await(() -> !owner.openFileTree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertEquals(selected, owner.openFileTree.state().selectedId().path());
            assertEquals(selected.toString(), owner.openFileFocusState.location().text());
            assertFalse(owner.openFileFocusState.locationEdited());
            for (int index = 0; index < 3; index++) window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(OpenFileInputRouter.Focus.OK, owner.openFileFocusState.focus());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !owner.openFileMode);
            assertEquals(selected, window.ui.explorer.root());
        }
    }

    @Test void manuallyEnteredFolderCanBeConfirmedWithoutANavigationRoundtrip() throws Exception {
        Path selected = Files.createDirectory(directory.resolve("chosen"));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.controller.openFolder(directory);
            window.command(TuinkCommand.FOLDER_OPEN);
            var owner = window.active().uiState();
            window.await(() -> !owner.openFileTree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(selected.toString()));
            window.frame();
            assertEquals(selected.toString(), owner.openFileFocusState.location().text());
            for (int index = 0; index < 2; index++) window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(OpenFileInputRouter.Focus.OK, owner.openFileFocusState.focus());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !owner.openFileMode);
            assertEquals(selected, window.ui.explorer.root());
        }
    }

    @Test void ordinaryRefreshKeepsAnUnsubmittedLocationDraft() {
        var state = new OpenFileDialogView.FocusState();
        state.synchronizeRoot(directory);
        Path selected = directory.resolve("selected");
        state.selectDirectory(new FileTreeModel.EntryId(selected, FileTreeModel.Kind.DIRECTORY));
        state.location().set("unfinished path");
        state.synchronizeRoot(directory);
        assertEquals("unfinished path", state.location().text());
        assertTrue(state.locationEdited());
        assertEquals(selected, state.selectedDirectory());
    }

    @Test void clickingAFolderUpdatesPathWithoutFirstEnteringIt() throws Exception {
        Path selected = Files.createDirectory(directory.resolve("mouse-folder"));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.controller.openFolder(directory);
            window.command(TuinkCommand.EXPLORER_TOGGLE);
            window.command(TuinkCommand.FOLDER_OPEN);
            var owner = window.active().uiState();
            window.await(() -> !owner.openFileTree.loading());
            int row = java.util.stream.IntStream.range(1, window.height - 1)
                .filter(index -> window.screenRow(index).contains("mouse-folder")).findFirst().orElseThrow();
            window.pointer(PointerEvent.Kind.PRESS, row, window.screenRow(row).indexOf("mouse-folder") + 1);
            assertEquals(selected.toString(), owner.openFileFocusState.location().text());
            assertEquals(selected, owner.openFileFocusState.selectedDirectory());
            assertEquals(directory, owner.openFileTree.root());
        }
    }

    @Test void aCompletedInspectionCannotOverwriteALaterPathEdit() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        var tasks = new ArrayDeque<Runnable>();
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), tasks::addLast)) {
            window.controller.openFolder(directory);
            window.command(TuinkCommand.FOLDER_OPEN);
            var owner = window.active().uiState();
            window.await(() -> !owner.openFileTree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(first.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(1, tasks.size());
            window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(second.toString()));
            tasks.removeFirst().run(); window.frame();
            assertEquals(directory, owner.openFileTree.root());
            assertEquals(second.toString(), owner.openFileFocusState.location().text());
            assertTrue(owner.openFileFocusState.locationEdited());
        }
    }
}
