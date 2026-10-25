package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveTargetSpecialFileIntegrationTest {
    @TempDir Path directory;

    @Test
    void fifoAndItsSymlinkAreRejectedBeforeTheOverwritePrompt() throws Exception {
        assumePosix();
        Path fifo = directory.resolve("pipe");
        Process process;
        try {
            process = new ProcessBuilder("mkfifo", fifo.toString()).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "mkfifo is unavailable");
            throw unavailable;
        }
        assertEquals(0, process.waitFor());
        assertRejectedBeforeOverwrite(fifo);
    }

    @Test
    void unixSocketAndItsSymlinkAreRejectedBeforeTheOverwritePrompt() throws Exception {
        assumePosix();
        Path socket = directory.resolve("socket");
        ServerSocketChannel listener;
        try {
            listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        } catch (UnsupportedOperationException unavailable) {
            assumeTrue(false, "Unix domain sockets are unavailable");
            throw unavailable;
        }
        try (listener) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            assertRejectedBeforeOverwrite(socket);
        }
    }

    private void assertRejectedBeforeOverwrite(Path special) throws Exception {
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), special.getFileName());
        for (Path target : List.of(special, alias)) {
            String error = SaveTargetPolicy.validateSaveTarget(target);
            assertNotNull(error);
            assertTrue(error.contains("not a regular file"), error);
            try (EditorWindowFixture window = new EditorWindowFixture(
                directory.resolve(target.getFileName() + "-window"), false, List.of("draft"))) {
                SaveWorkflowIntegrationTest.submitFilename(window, target);

                assertTrue(window.notification.message.contains("not a regular file"), window.notification.message);
                assertFalse(window.screenRow(window.height - 2).contains("Overwrite"));
                assertEquals(target.toString(), window.filename.text());
                assertEquals(window.document, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
                assertTrue(Files.readAttributes(special, BasicFileAttributes.class).isOther());
                assertTrue(Files.isSymbolicLink(alias));
            }
        }
    }

    private void assumePosix() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    }
}
