package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuiframe.explorer.file.ExplorerFileService;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerMutationLifetimeTest {
    @TempDir Path directory;

    @Test
    void typingWhileANewlyCreatedFileOpensKeepsTheTextAndTheNewerEditingIntent() throws Exception {
        var pending = new ArrayDeque<Runnable>();
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), pending::add)) {
            var original = window.active().documentSession();
            window.controller.openFolder(directory);
            window.await(() -> !window.ui.sidebar.tree.loading());
            window.command(TuinkCommand.EXPLORER_NEW_FILE);
            window.event(new TextInput("new.txt"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            pending.remove().run(); window.frame();
            assertTrue(Files.exists(directory.resolve("new.txt")));
            assertTrue(window.documentIo.busy(), "The new file still has a detached read pending");
            assertFalse(window.ui.sidebar.focused, "The tree must not swallow typing while the read finishes");
            window.event(new TextInput("draft "));
            pending.remove().run(); window.frame();
            assertEquals(2, window.tabs.size());
            assertSame(original, window.active().documentSession());
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.active().state().isDirty());
            assertEquals("", Files.readString(directory.resolve("new.txt")));
        }
    }

    @Test
    void mutationCannotBeCancelledAsAReadOrSaveAndShutdownAcceptsItsOutcome() throws Exception {
        var pending = new ArrayDeque<Runnable>();
        var receipts = new ArrayList<Path>();
        var io = new DocumentIoController(pending::add);
        var release = new CountDownLatch(1);
        Path target = directory.resolve("created.txt");
        io.submitMutation(() -> {
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Files.createFile(target);
        }, result -> {
            assertNull(result.failure());
            receipts.add(result.value());
        });
        assertEquals(DocumentIoController.Kind.MUTATION, io.pendingOperation().kind());
        assertFalse(io.cancelRead());
        assertFalse(io.cancelSave());
        Thread worker = new Thread(pending.remove(), "test-explorer-mutation");
        worker.start();
        release.countDown();
        io.close();
        worker.join(5_000);
        assertFalse(worker.isAlive());
        assertEquals(List.of(target), receipts);
        assertTrue(Files.exists(target));
        assertFalse(io.busy());
    }

    @Test
    void cancellingTheDestinationDialogOnlyCancelsItsUnpublishedInspection() throws Exception {
        var pending = new ArrayDeque<Runnable>();
        try (var io = new DocumentIoController(pending::add)) {
            Path source = Files.writeString(directory.resolve("source.txt"), "keep");
            var accepted = new ArrayList<ExplorerFileController.Outcome>();
            var controller = new ExplorerFileController(io,
                    () -> new dev.tuiframe.explorer.file.ExplorerOperations(dev.tuiframe.explorer.Explorer.Access.MANAGE_FILES, new ExplorerFileService()), List::of, accepted::add, () -> {});
            controller.open(ExplorerFileService.Action.MOVE, source, directory);
            assertTrue(io.busy());
            controller.close();
            assertFalse(io.busy());
            pending.remove().run();
            assertFalse(io.collect());
            assertTrue(accepted.isEmpty());
            assertEquals("keep", Files.readString(source));
        }
    }

    @Test
    void movingAFolderRebindsAnOpenAliasOutsideItToTheMovedPhysicalFile() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("source"));
        Path file = Files.writeString(folder.resolve("note.txt"), "original");
        Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), file);
        Path destination = Files.createDirectory(directory.resolve("destination"));
        try (var window = new EditorWindowFixture(directory, "alias.txt", true, List.of("original"))) {
            var subject = window.active().documentSession().document();
            var original = subject.binding();
            var pending = new ArrayDeque<Runnable>();
            try (var io = new DocumentIoController(pending::add)) {
                var accepted = new ArrayList<ExplorerFileController.Outcome>();
                var controller = new ExplorerFileController(io,
                    () -> new dev.tuiframe.explorer.file.ExplorerOperations(dev.tuiframe.explorer.Explorer.Access.MANAGE_FILES, new ExplorerFileService()),
                    () -> List.of(new ExplorerFileController.Binding(subject, original)), accepted::add, () -> {});
                controller.open(ExplorerFileService.Action.MOVE, folder, directory);
                pending.remove().run(); io.collect();
                controller.dialog().navigate(destination.toString());
                controller.confirm();
                assertTrue(controller.mutating());
                controller.close();
                assertNotNull(controller.dialog(), "A publishing mutation retains its dialog and receipt");
                pending.remove().run(); io.collect();
                assertEquals(1, accepted.size());
                assertTrue(accepted.getFirst().result().succeeded());
                assertEquals(1, accepted.getFirst().bindings().size());
                var update = accepted.getFirst().bindings().getFirst();
                assertTrue(subject.relocateResource(update.binding().expected(), update.replacement()));
                assertEquals(destination.resolve("source/note.txt"), JvmDocumentStorage.pathOf(subject.binding().resource()));
                assertEquals("original", Files.readString(destination.resolve("source/note.txt")));
                assertTrue(Files.isSymbolicLink(alias));
                controller.close();
            }
        }
    }

    @Test
    void movingARelativeLinkKeepsItsOpenedPhysicalDocumentWhenTheNewLinkTargetsOtherBytes() throws Exception {
        Path original = Files.writeString(directory.resolve("original.txt"), "original");
        Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), Path.of("original.txt"));
        Path destination = Files.createDirectory(directory.resolve("destination"));
        Path other = Files.writeString(destination.resolve("original.txt"), "original");
        try (var window = new EditorWindowFixture(directory, "alias.txt", true, List.of("original"))) {
            var subject = window.active().documentSession().document();
            var expected = subject.binding();
            var pending = new ArrayDeque<Runnable>();
            try (var io = new DocumentIoController(pending::add)) {
                var accepted = new ArrayList<ExplorerFileController.Outcome>();
                var controller = new ExplorerFileController(io,
                    () -> new dev.tuiframe.explorer.file.ExplorerOperations(dev.tuiframe.explorer.Explorer.Access.MANAGE_FILES, new ExplorerFileService()),
                    () -> List.of(new ExplorerFileController.Binding(subject, expected)), accepted::add, () -> {});
                controller.open(ExplorerFileService.Action.MOVE, alias, directory);
                pending.remove().run(); io.collect();
                controller.dialog().navigate(destination.toString());
                controller.confirm(); pending.remove().run(); io.collect();
                var outcome = accepted.getFirst();
                assertTrue(outcome.result().succeeded());
                var update = outcome.bindings().getFirst();
                assertTrue(subject.relocateResource(update.binding().expected(), update.replacement()));
                assertEquals(original, JvmDocumentStorage.pathOf(subject.binding().resource()));
                assertTrue(Files.isSymbolicLink(destination.resolve("alias.txt")));
                assertEquals(other, destination.resolve("alias.txt").toRealPath());
                controller.close();
            }
        }
    }
}
