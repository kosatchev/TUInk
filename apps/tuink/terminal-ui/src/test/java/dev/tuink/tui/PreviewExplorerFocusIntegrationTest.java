package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewExplorerFocusIntegrationTest {
    @TempDir Path directory;

    @Test
    void enterInExplorerWithPreviewFindVisibleTogglesFolderAndOpensItsChild() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("folder"));
        Path child = Files.writeString(folder.resolve("child.txt"), "child text");
        try (var window = previewWithFind()) {
            var preview = window.preview();
            window.controller.openFolder(directory);
            window.await(() -> !window.ui.explorer.tree.loading());
            assertEquals(directory, window.ui.explorer.selectedEntry().path());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            var selected = window.ui.explorer.placement.tree().selectedEntry().orElseThrow();
            assertEquals(folder, selected.path());
            assertFalse(selected.expanded());

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.ui.explorer.tree.loading());
            assertTrue(window.ui.explorer.placement.tree().selectedEntry().orElseThrow().expanded(),
                "Enter belongs to the focused explorer even while Preview Find is retained");
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            assertEquals(child, window.ui.explorer.placement.tree().selectedEntry().orElseThrow().path());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals(child, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertFalse(window.ui.explorer.focused);
            assertEquals("alpha", preview.searchQuery);
            assertEquals("child text", Files.readString(child));
        }
    }

    @Test
    void findFromExplorerFocusesRetainedPreviewQueryInsteadOfNavigatingItsMatches() throws Exception {
        try (var window = previewWithFind()) {
            var preview = window.preview();
            var source = window.context.tabs().getFirst();
            // The retained Find is open while Preview content owns the keyboard.
            window.selected().uiState().searchEditorFocused = true;
            window.frame();
            assertEquals(ActiveSurface.Focus.CONTENT, window.controller.activeSurface().focus());
            window.controller.openFolder(directory);
            window.await(() -> !window.ui.explorer.tree.loading());
            assertTrue(window.ui.explorer.focused);

            window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));

            assertFalse(window.ui.explorer.focused);
            assertTrue(preview.searchVisible);
            assertFalse(window.selected().uiState().searchEditorFocused);
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            window.event(new TextInput(" beta"));
            assertTrue(preview.searchQuery.contains(" beta"));
            assertEquals(List.of("alpha beta alpha"), source.tabSession().model().snapshotLines());
            assertFalse(source.state().isDirty());
        }
    }

    private EditorWindowFixture previewWithFind() throws Exception {
        var window = new EditorWindowFixture(directory, "document.md", false, List.of("alpha beta alpha"));
        try {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
            window.event(new TextInput("alpha"));
            assertTrue(window.preview().searchVisible);
            assertEquals("alpha", window.preview().searchQuery);
            return window;
        } catch (Exception | AssertionError failure) {
            window.close();
            throw failure;
        }
    }
}
