package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.explorer.Explorer;
import dev.tuiframe.explorer.file.ExplorerFileService;
import dev.tuiframe.explorer.file.PathLabels;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ExplorerDestinationViewTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(value = ExplorerFileService.Action.class, names = {"COPY", "MOVE"})
    void rootedAndFullViewsKeepTheSameDestinationAndReadOnlyPermission(ExplorerFileService.Action action) throws Exception {
        Path parent = Files.createDirectory(directory.resolve("nested"));
        Path source = Files.writeString(parent.resolve("file.txt"), "text");
        try (var state = new ExplorerFileDialogView.State(action, source, parent, directory)) {
            assertFalse(state.fullPath);
            assertEquals(directory, state.tree.root());
            assertEquals(Explorer.Navigation.ROOTED, state.tree.navigation());
            assertEquals(Explorer.Access.READ_ONLY, state.tree.access());
            assertEquals("nested", state.field.text());
            assertEquals(parent, state.destinationFolder());
            state.setFullPath(true);
            assertEquals(parent, state.destinationFolder());
            assertEquals(PathLabels.full(parent), state.field.text());
            assertEquals(Explorer.Navigation.BROWSE, state.tree.navigation());
            assertEquals(Explorer.Access.READ_ONLY, state.tree.access());
            state.setFullPath(false);
            assertFalse(state.fullPath);
            assertEquals("nested", state.field.text());
            assertEquals(parent, state.destinationFolder());
        }
    }

    @Test void anOutsideDestinationUsesFullNavigationWithoutSilentlyChangingTheTarget() throws Exception {
        Path root = Files.createDirectory(directory.resolve("workspace"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path source = Files.writeString(root.resolve("file.txt"), "text");
        try (var state = new ExplorerFileDialogView.State(ExplorerFileService.Action.COPY, source, root, root)) {
            state.navigate(outside.toString());
            assertTrue(state.fullPath);
            assertEquals(outside, state.destinationFolder());
            state.setFullPath(false);
            assertTrue(state.fullPath);
            assertEquals(outside, state.destinationFolder());
            assertNotNull(state.error);
            state.navigate(root.toString());
            state.setFullPath(false);
            assertFalse(state.fullPath);
            assertEquals(".", state.field.text());
            assertEquals(root, state.destinationFolder());
        }
    }

    @Test void switchingModeDoesNotDiscardAnUnsubmittedPath() throws Exception {
        Path source = Files.writeString(directory.resolve("file.txt"), "text");
        try (var state = new ExplorerFileDialogView.State(ExplorerFileService.Action.MOVE, source, directory, directory)) {
            state.field.set("unfinished");
            state.setFullPath(true);
            assertFalse(state.fullPath);
            assertEquals("unfinished", state.field.text());
            assertFalse(state.destinationAllowed());
            assertNotNull(state.error);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TuinkCommand.class, names = {"EXPLORER_COPY", "EXPLORER_MOVE"})
    void scrollingTheTreeKeepsAnUnsubmittedDestinationUntilAnotherFolderIsChosen(TuinkCommand command) throws Exception {
        for (int index = 0; index < 30; index++) Files.createDirectory(directory.resolve("d%02d".formatted(index)));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.controller.openFolder(directory);
            window.await(() -> !window.ui.sidebar.tree.loading());
            window.ui.sidebar.tree.reveal(window.document);
            window.await(() -> !window.ui.sidebar.tree.loading()
                && window.ui.sidebar.selectedEntry() != null && window.document.equals(window.ui.sidebar.selectedEntry().path()));
            window.command(command);
            window.await(() -> !window.documentIo.busy() && window.controller.explorerFileDialog() != null
                && window.controller.explorerFileDialog().inspected != null && !window.controller.explorerFileDialog().tree.loading());
            var dialog = window.controller.explorerFileDialog();
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            Path destination = directory.resolve("d00");
            assertEquals(destination, dialog.destinationFolder());
            window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
            window.event(new TextInput("unfinished destination"));
            var tree = ExplorerFileDialogView.place(dialog, new Rect(0, 0, window.width, window.height)).tree().contentBounds();
            window.event(new PointerEvent(tree.y() + 1, tree.x() + 4,
                PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE, Set.of()));
            assertEquals("unfinished destination", dialog.field.text());
            assertEquals(destination, dialog.destinationFolder());
            assertFalse(dialog.destinationAllowed());
            for (int index = 0; index < 3; index++) window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(ExplorerFileDialogView.Target.TREE, dialog.focus.focused().orElseThrow());
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            assertEquals(".", dialog.field.text());
            assertEquals(directory, dialog.destinationFolder());
            assertTrue(dialog.destinationAllowed());
        }
    }
}
