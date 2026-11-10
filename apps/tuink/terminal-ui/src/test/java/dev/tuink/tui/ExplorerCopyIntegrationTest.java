package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.explorer.Explorer;
import dev.tuiframe.explorer.file.ExplorerFileService;
import dev.tuiframe.explorer.ui.PathPresentation;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SavePathInput;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;

class ExplorerCopyIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copyingAnOpenDirtyFileOrItsFolderKeepsItsBindingAndSeparateTextHistory(boolean copyFolder) throws Exception {
        Path sourceFolder = Files.createDirectory(directory.resolve("source"));
        Path destination = Files.createDirectory(directory.resolve("destination"));
        Path empty = Files.createDirectory(sourceFolder.resolve("empty"));
        Path nested = Files.createDirectories(sourceFolder.resolve("nested"));
        Files.write(nested.resolve("data.bin"), new byte[] {0, 1, (byte) 255});
        try (var window = new EditorWindowFixture(directory, "source/document.txt", false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var subject = window.active().documentSession().document();
            var binding = subject.binding();
            var document = subject.documentId();
            var presentation = window.selected().presentationId();
            var cursor = window.active().state().getCursor();
            long revision = window.active().tabSession().model().revision();
            openTree(window, directory);
            select(window, copyFolder ? sourceFolder : window.document);
            window.command(TuinkCommand.MENU_EXPLORER);
            window.selectMenuCommand(TuinkCommand.EXPLORER_COPY);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            inspected(window);
            chooseDestination(window, destination);
            chooseApply(window);
            completed(window);

            Path copied = destination.resolve(copyFolder ? "source/document.txt" : "document.txt");
            assertEquals("original", Files.readString(window.document));
            assertEquals("original", Files.readString(copied), "Copy reads disk bytes without saving the dirty document");
            if (copyFolder) {
                assertTrue(Files.isDirectory(destination.resolve("source/empty")));
                assertArrayEquals(new byte[] {0, 1, (byte) 255}, Files.readAllBytes(destination.resolve("source/nested/data.bin")));
            }
            assertTrue(Files.isDirectory(empty));
            assertEquals(binding, subject.binding());
            assertEquals(document, subject.documentId());
            assertEquals(presentation, window.selected().presentationId());
            assertEquals(cursor, window.active().state().getCursor());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertEquals(1, window.tabs.size(), "Copy must not open another document");
            assertTrue(window.active().state().isDirty());
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.host.deletedNotices.visible(document));

            window.command(TuinkCommand.EXPLORER_UNDO);
            completed(window);
            assertFalse(Files.exists(copied));
            assertEquals("original", Files.readString(window.document));
            assertEquals(binding, subject.binding());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertTrue(window.active().state().isDirty());
            window.command(TuinkCommand.EXPLORER_REDO);
            completed(window);
            assertEquals("original", Files.readString(copied));
            assertEquals(binding, subject.binding());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
            assertTrue(Files.exists(copied), "Document Undo must leave the copied entry intact");
            window.command(TuinkCommand.EDIT_REDO);
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> !window.documentIo.busy() && !window.active().state().isDirty());
            assertEquals("draft original", Files.readString(window.document));
            assertEquals("original", Files.readString(copied), "Saving the original must not save into its copy");
        }
    }

    @Test
    void rootCopyUsesAnOutsideDestinationWithoutChangingTheOpenRootOrItsDocument() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path destination = Files.createDirectory(directory.resolve("outside"));
        try (var window = new EditorWindowFixture(directory, "root/document.txt", false, List.of("original"))) {
            var subject = window.active().documentSession().document();
            var binding = subject.binding();
            openTree(window, root);
            select(window, root);
            assertTrue(window.ui.sidebar.selectedRoot());
            window.command(TuinkCommand.EXPLORER_COPY);
            inspected(window);
            assertEquals(root, window.controller.explorerFileDialog().tree.root());
            assertFalse(window.controller.explorerFileDialog().fullPath);
            chooseDestination(window, destination);
            chooseApply(window);
            completed(window);
            assertEquals(root, window.ui.sidebar.root());
            assertEquals("original", Files.readString(root.resolve("document.txt")));
            assertEquals("original", Files.readString(destination.resolve("root/document.txt")));
            assertEquals(binding, subject.binding());
            assertEquals(window.document, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void copyDestinationUsesAReadOnlyTreeAndDoesNotSubmitAnUnnavigatedPath() throws Exception {
        Path destination = Files.createDirectory(directory.resolve("destination"));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            openTree(window, directory);
            select(window, window.document);
            window.command(TuinkCommand.EXPLORER_COPY);
            inspected(window);
            var dialog = window.controller.explorerFileDialog();
            assertEquals(Explorer.Access.READ_ONLY, dialog.tree.access());
            assertEquals(directory, dialog.destinationFolder(), "The initial '..' row does not choose the parent");
            assertTrue(dialog.destinationAllowed());
            var denied = dialog.tree.operations().perform(ExplorerFileService.Action.CREATE_FILE, null, destination, "forbidden.txt");
            assertFalse(denied.succeeded());
            assertFalse(Files.exists(destination.resolve("forbidden.txt")));

            window.event(ctrl(KeyStroke.Key.L));
            window.event(new PasteEvent(destination.toString()));
            assertFalse(dialog.destinationAllowed(), "Typing a path is not an accepted destination until Enter navigates there");
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(ExplorerFileDialogView.Target.FULL_PATH, dialog.focus.focused().orElseThrow());
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(ExplorerFileDialogView.Target.CANCEL, dialog.focus.focused().orElseThrow(),
                "The disabled Copy action is excluded from focus traversal");
            assertFalse(Files.exists(destination.resolve("document.txt")));
            window.event(ctrl(KeyStroke.Key.L));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !dialog.tree.loading());
            assertEquals(directory, dialog.tree.root());
            assertEquals(destination, dialog.destinationFolder());
            assertTrue(dialog.destinationAllowed());
            assertEquals(directory, window.ui.sidebar.root());
            chooseApply(window);
            completed(window);
            assertEquals("original", Files.readString(window.document));
            assertEquals("original", Files.readString(destination.resolve("document.txt")));
        }
    }

    @Test
    void occupiedCopyDestinationPreservesBothFilesAndTheDialogCanChooseAnotherFolder() throws Exception {
        Path occupied = Files.createDirectory(directory.resolve("occupied"));
        Path destination = Files.createDirectory(directory.resolve("destination"));
        Path existing = Files.writeString(occupied.resolve("document.txt"), "other content");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var subject = window.active().documentSession().document();
            var binding = subject.binding();
            openTree(window, directory);
            select(window, window.document);
            long history = window.ui.sidebar.tree.operations().historyVersion();
            window.command(TuinkCommand.EXPLORER_COPY);
            inspected(window);
            var dialog = window.controller.explorerFileDialog();
            chooseDestination(window, occupied);
            chooseApply(window);
            window.await(() -> !window.documentIo.busy() && dialog.error != null);
            assertSame(dialog, window.controller.explorerFileDialog());
            assertEquals("occupied", dialog.field.text());
            assertEquals("original", Files.readString(window.document));
            assertEquals("other content", Files.readString(existing));
            assertEquals(binding, subject.binding());
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.active().state().isDirty());
            assertEquals(history, window.ui.sidebar.tree.operations().historyVersion());
            assertFalse(window.ui.sidebar.canUndo());
            chooseDestination(window, destination);
            chooseApply(window);
            completed(window);
            assertEquals("original", Files.readString(destination.resolve("document.txt")));
            assertEquals("other content", Files.readString(existing));
            assertEquals(binding, subject.binding());
        }
    }

    @Test
    void copyingFromFrozenPreviewKeepsItsSnapshotAndOriginalResource() throws Exception {
        Path destination = Files.createDirectory(directory.resolve("destination"));
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# original"))) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.TAB_CLOSE);
            var preview = assertInstanceOf(PreviewTab.class, window.selected());
            var frozen = assertInstanceOf(PreviewTab.ClosedSnapshot.class, preview.source());
            var text = frozen.source().text();
            var binding = frozen.source().binding();
            openTree(window, directory);
            select(window, window.document);
            window.command(TuinkCommand.EXPLORER_COPY);
            inspected(window);
            chooseDestination(window, destination);
            chooseApply(window);
            completed(window);
            var retained = assertInstanceOf(PreviewTab.class, window.selected());
            var retainedFrozen = assertInstanceOf(PreviewTab.ClosedSnapshot.class, retained.source());
            assertEquals(preview.presentationId(), retained.presentationId());
            assertSame(frozen.subject(), retainedFrozen.subject());
            assertSame(text, retainedFrozen.source().text());
            assertEquals(binding, retainedFrozen.source().binding());
            assertEquals("# original", Files.readString(window.document));
            assertEquals("# original", Files.readString(destination.resolve("document.md")));
            assertFalse(window.host.deletedNotices.visible(TerminalEditorApp.documentId(retained)));
        }
    }

    @ParameterizedTest
    @EnumSource(value = TuinkCommand.class, names = {"EXPLORER_COPY", "EXPLORER_MOVE"})
    void destinationInputsPreserveNativeBytesAndControlsThroughFormattingNavigationAndSubmission(TuinkCommand command) throws Exception {
        assumeTrue(File.separatorChar == '/');
        Path sourceParent = Files.createDirectory(Path.of(URI.create(directory.toUri().toASCIIString() + "source%FF")));
        Path destination = Files.createDirectory(Path.of(URI.create(directory.toUri().toASCIIString() + "destination%FE%0A")));
        Path lookalike = Files.createDirectory(directory.resolve("destination\uFFFD\n"));
        Path source = Files.writeString(sourceParent.resolve("control\nfile.txt"), "exact bytes");
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(source));
            window.frame();
            openTree(window, sourceParent);
            select(window, source);
            window.command(command);
            inspected(window);
            var dialog = window.controller.explorerFileDialog();
            assertEquals(".", dialog.field.text());
            assertEquals(sourceParent, dialog.destinationFolder());
            assertTrue(dialog.destinationAllowed());

            dialog.setFullPath(true);
            assertEquals(PathPresentation.full(sourceParent), dialog.field.text());
            assertEquals(sourceParent, SavePathInput.parse(dialog.field.text()));
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !dialog.tree.loading());
            assertEquals(directory, dialog.tree.root());
            assertEquals(PathPresentation.full(directory), dialog.field.text());
            window.event(ctrl(KeyStroke.Key.L));
            window.event(new PasteEvent(PathPresentation.full(destination)));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !dialog.tree.loading());
            assertEquals(destination, dialog.tree.root());
            assertEquals(PathPresentation.full(destination), dialog.field.text());
            assertEquals(destination, SavePathInput.parse(dialog.field.text()));
            assertTrue(dialog.destinationAllowed());
            chooseApply(window);
            completed(window);

            Path transferred = destination.resolve(source.getFileName());
            assertEquals("exact bytes", Files.readString(transferred));
            assertFalse(Files.exists(lookalike.resolve(source.getFileName())), "The decoded lookalike is a different directory");
            assertEquals(command == TuinkCommand.EXPLORER_COPY, Files.exists(source));
            assertEquals(command == TuinkCommand.EXPLORER_COPY ? source : transferred,
                JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }

    private static void openTree(EditorWindowFixture window, Path root) throws Exception {
        window.controller.openFolder(root);
        window.await(() -> !window.ui.sidebar.tree.loading());
    }

    private static void select(EditorWindowFixture window, Path source) throws Exception {
        assertTrue(window.ui.sidebar.tree.reveal(source));
        window.await(() -> !window.ui.sidebar.tree.loading() && window.ui.sidebar.selectedEntry() != null
            && source.equals(window.ui.sidebar.selectedEntry().path()));
    }

    private static void inspected(EditorWindowFixture window) throws Exception {
        window.await(() -> {
            var dialog = window.controller.explorerFileDialog();
            return dialog != null && dialog.inspected != null && !window.documentIo.busy()
                && dialog.tree != null && !dialog.tree.loading();
        });
    }

    private static void chooseDestination(EditorWindowFixture window, Path destination) throws Exception {
        window.event(ctrl(KeyStroke.Key.L));
        window.event(new PasteEvent(destination.toString()));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        window.await(() -> !window.controller.explorerFileDialog().tree.loading());
        assertEquals(destination, window.controller.explorerFileDialog().destinationFolder());
    }

    private static void chooseApply(EditorWindowFixture window) {
        var dialog = window.controller.explorerFileDialog();
        for (int step = 0; step < 5 && dialog.focus.focused().orElse(null) != ExplorerFileDialogView.Target.APPLY; step++)
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
        assertEquals(ExplorerFileDialogView.Target.APPLY, dialog.focus.focused().orElseThrow());
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }

    private static void completed(EditorWindowFixture window) throws Exception {
        window.await(() -> !window.documentIo.busy() && window.controller.explorerFileDialog() == null);
    }
}
