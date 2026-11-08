package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.explorer.file.ExplorerFileService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExplorerNestedLinkRelocationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void movingAFolderKeepsAnOpenedNestedRelativeLinkBoundToItsOriginalPhysicalDocument(boolean dirtyBeforeMove)
        throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path source = Files.createDirectory(directory.resolve("source"));
        Path physicalOriginal = Files.writeString(directory.resolve("target.txt"), "original");
        Path link = Files.createSymbolicLink(source.resolve("link.txt"), Path.of("../target.txt"));
        Path destination = Files.createDirectory(directory.resolve("destination"));
        Path unrelated = Files.writeString(destination.resolve("target.txt"), "original");
        // Equal fingerprints are intentional: the byte-baseline guard alone
        // cannot distinguish the newly targeted, unrelated physical document.
        try (var window = new EditorWindowFixture(directory, "source/link.txt", false, List.of("original"))) {
            if (dirtyBeforeMove) window.event(new TextInput("draft "));
            var subject = window.active().documentSession().document();
            var expected = subject.binding();
            var text = subject.snapshot();
            var pending = new ArrayDeque<Runnable>();
            try (var io = new DocumentIoController(pending::add)) {
                var accepted = new ArrayList<ExplorerFileController.Outcome>();
                var controller = new ExplorerFileController(io,
                    () -> new dev.tuiframe.explorer.file.ExplorerOperations(dev.tuiframe.explorer.Explorer.Access.MANAGE_FILES, new ExplorerFileService()),
                    () -> List.of(new ExplorerFileController.Binding(subject, expected)), accepted::add, () -> {});
                controller.open(ExplorerFileService.Action.MOVE, source, directory);
                pending.remove().run();
                assertTrue(io.collect());
                controller.dialog().navigate(destination.toString());
                controller.confirm();
                pending.remove().run();
                assertTrue(io.collect());

                assertEquals(1, accepted.size());
                var outcome = accepted.getFirst();
                assertTrue(outcome.result().succeeded());
                assertEquals(1, outcome.bindings().size());
                var update = outcome.bindings().getFirst();
                assertTrue(subject.relocateResource(update.binding().expected(), update.replacement()));
                controller.close();
            }

            Path movedLink = destination.resolve("source/link.txt");
            assertFalse(Files.exists(source));
            assertFalse(Files.exists(link));
            assertTrue(Files.isSymbolicLink(movedLink));
            assertEquals(unrelated, movedLink.toRealPath());
            assertEquals(physicalOriginal, JvmDocumentStorage.pathOf(subject.binding().resource()));
            assertEquals(physicalOriginal, JvmDocumentStorage.boundPhysicalPath(subject.binding()));
            assertSame(text, subject.snapshot());
            assertEquals(dirtyBeforeMove, subject.status().dirty());

            window.event(new TextInput("edited "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> !window.documentIo.busy());

            assertEquals(dirtyBeforeMove ? "draft edited original" : "edited original", Files.readString(physicalOriginal));
            assertEquals("original", Files.readString(unrelated), "Save must never overwrite the new link target");
            assertEquals("original", Files.readString(movedLink));
            assertFalse(subject.status().dirty());
        }
    }
}
