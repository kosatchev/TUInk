package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemanticPickerCommandIntegrationTest {
    @TempDir Path directory;

    @Test void approvedAliasesNavigateTreeToggleHiddenOpenSelectedFileAndActivateCancel() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Path target = window.document.resolveSibling("zzzz-target.txt");
            Files.writeString(target, "opened by semantic Enter");
            Files.writeString(window.document.resolveSibling(".hidden-file.txt"), "hidden");
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            window.command(TuinkCommand.CURSOR_END);
            assertEquals(target, window.tree.state().selectedId().path());
            window.command(TuinkCommand.CURSOR_UP);
            assertNotEquals(target, window.tree.state().selectedId().path());
            window.command(TuinkCommand.CURSOR_DOWN);
            assertEquals(target, window.tree.state().selectedId().path());
            boolean hidden = window.tree.showHiddenFiles();
            window.command(TuinkCommand.FILE_BROWSER_TOGGLE_HIDDEN);
            assertEquals(!hidden, window.tree.showHiddenFiles());
            window.await(() -> !window.tree.loading());
            window.command(TuinkCommand.CURSOR_END);
            window.command(TuinkCommand.EDIT_INSERT_NEWLINE);
            window.await(() -> !window.selected().uiState().openFileMode);
            assertEquals(target, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(List.of("opened by semantic Enter"), window.active().tabSession().model().snapshotLines());
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.selected().uiState().openFileFocusState.focus(OpenFileInputRouter.Focus.CANCEL);
            window.frame();
            window.command(TuinkCommand.EDIT_INSERT_NEWLINE);
            assertFalse(window.selected().uiState().openFileMode);
        }
    }
}
