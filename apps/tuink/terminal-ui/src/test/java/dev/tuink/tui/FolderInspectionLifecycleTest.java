package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FolderInspectionLifecycleTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingPreviewCancelsItsInspectionAndAllowsTheNextOperation(boolean frozen) throws Exception {
        var pending = new ArrayDeque<Runnable>();
        Path nextFolder = Files.createDirectory(directory.resolve("next-folder"));
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# title"), pending::add)) {
            PreviewTab owner = openPreview(window);
            if (frozen) {
                var presentationId = owner.presentationId();
                window.command(TuinkCommand.TAB_PREVIOUS);
                window.command(TuinkCommand.TAB_CLOSE);
                owner = assertInstanceOf(PreviewTab.class, window.selected());
                assertEquals(presentationId, owner.presentationId());
                assertInstanceOf(PreviewTab.ClosedSnapshot.class, owner.source());
                window.await(() -> window.preview().ready());
            }
            startInspection(window, directory);
            assertTrue(window.documentIo.busy());
            assertEquals(1, pending.size());
            Runnable cancelledInspection = pending.remove();

            window.command(TuinkCommand.TAB_CLOSE);

            assertFalse(window.selected() instanceof PreviewTab);
            assertFalse(window.context.presentations().contains(owner));
            assertFalse(window.documentIo.busy(),
                "Closing the inspection owner must release the window's single I/O slot");

            startInspection(window, nextFolder);
            assertTrue(window.documentIo.busy(), "Another presentation must be able to start a fresh inspection");
            assertEquals(1, pending.size());
            var nextOperation = window.documentIo.pendingOperation();
            cancelledInspection.run();
            window.frame();
            assertSame(nextOperation, window.documentIo.pendingOperation(),
                "The cancelled owner's late work must not cancel the replacement operation");

            pending.remove().run();
            window.frame();

            assertFalse(window.documentIo.busy());
            assertTrue(window.selected().uiState().openFileMode);
            assertEquals(nextFolder, window.selected().uiState().openFileTree.root());
            assertFalse(window.ui.sidebar.visible, "A cancelled inspection must not open its old folder");
        }
    }

    @Test
    void closingAnotherPreviewPreservesTheSourceOwnedInspection() throws Exception {
        var pending = new ArrayDeque<Runnable>();
        Path target = Files.createDirectory(directory.resolve("source-folder"));
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# title"), pending::add)) {
            PreviewTab preview = openPreview(window);
            window.command(TuinkCommand.TAB_PREVIOUS);
            var source = window.active();
            startInspection(window, target);
            var operation = window.documentIo.pendingOperation();
            assertTrue(window.documentIo.busy());

            window.command(TuinkCommand.TAB_NEXT);
            assertSame(preview, window.selected());
            window.command(TuinkCommand.TAB_CLOSE);

            assertSame(source, window.selected());
            assertSame(operation, window.documentIo.pendingOperation(),
                "Closing another presentation must leave the actual owner's inspection running");
            pending.remove().run();
            window.frame();

            assertFalse(window.documentIo.busy());
            assertTrue(source.uiState().openFileMode);
            assertEquals(target, source.uiState().openFileTree.root());
        }
    }

    private static PreviewTab openPreview(EditorWindowFixture window) throws Exception {
        window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
        window.await(() -> window.preview().ready());
        return assertInstanceOf(PreviewTab.class, window.selected());
    }

    private static void startInspection(EditorWindowFixture window, Path target) {
        window.command(TuinkCommand.FOLDER_OPEN);
        window.event(KeyStroke.of(KeyStroke.Key.L, KeyStroke.Modifier.CTRL));
        window.event(new PasteEvent(target.toString()));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }
}
