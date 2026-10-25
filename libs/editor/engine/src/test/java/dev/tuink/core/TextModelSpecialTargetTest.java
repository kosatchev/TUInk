package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.FileBaseline;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class TextModelSpecialTargetTest {
    @TempDir Path directory;

    @Test
    void saveRejectsFifosAndTheirSymlinksWithoutOpeningOrReplacingThem() throws Exception {
        Path fifo = createFifo(directory.resolve("pipe"));
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), fifo.getFileName());
        TextModel model = draft();
        DocumentFileMetadata metadata = model.fileMetadata();
        Object identity = Files.readAttributes(fifo, BasicFileAttributes.class).fileKey();

        for (Path target : List.of(fifo, alias)) {
            for (SaveDisposition disposition : SaveDisposition.values()) {
                assertSpecialFileRejected(target, () -> model.save(JvmDocumentStorage.resource(target), metadata, disposition));
                assertEquals(identity, Files.readAttributes(fifo, BasicFileAttributes.class).fileKey());
                assertTrue(Files.readAttributes(fifo, BasicFileAttributes.class).isOther());
                assertTrue(Files.isSymbolicLink(alias));
                assertEquals(metadata, model.fileMetadata());
                assertNull(JvmDocumentStorage.baselineOf(model.fileBaseline()));
            }
        }
    }

    @Test
    void saveRejectsUnixSocketsAndTheirSymlinks() throws Exception {
        assumePosix();
        Path socket = directory.resolve("socket");
        try (ServerSocketChannel listener = openUnixSocket()) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            Path alias = Files.createSymbolicLink(directory.resolve("alias"), socket.getFileName());
            TextModel model = draft();
            for (Path target : List.of(socket, alias)) {
                assertSpecialFileRejected(target,
                    () -> model.save(JvmDocumentStorage.resource(target), model.fileMetadata(), SaveDisposition.REPLACE_EXISTING));
                assertTrue(Files.readAttributes(socket, BasicFileAttributes.class).isOther());
                assertTrue(Files.isSymbolicLink(alias));
            }
        }
    }

    @Test
    void saveRejectsAReplacedFileOrRetargetedSymlinkWithoutLosingTheDirtyDocument() throws Exception {
        assumePosix();
        for (boolean symlink : List.of(false, true)) {
            Path scenario = Files.createDirectory(directory.resolve(Boolean.toString(symlink)));
            Path original = Files.writeString(scenario.resolve("document.txt"), "original");
            Path target = symlink
                ? Files.createSymbolicLink(scenario.resolve("alias"), original.getFileName()) : original;
            TextModel model = new TextModel(new SimpleLineBuffer());
            EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
            engine.open(JvmDocumentStorage.resource(target));
            engine.insertInput("draft ");
            FileBaseline baseline = JvmDocumentStorage.baselineOf(model.fileBaseline());
            Files.delete(target);
            if (symlink) {
                Path fifo = createFifo(scenario.resolve("pipe"));
                Files.createSymbolicLink(target, fifo.getFileName());
            } else {
                createFifo(target);
            }

            assertSpecialFileRejected(target, "File changed outside TUInk", engine::save);

            assertTrue(Files.readAttributes(target, BasicFileAttributes.class).isOther());
            if (symlink) {
                assertTrue(Files.isSymbolicLink(target));
                assertEquals("original", Files.readString(original));
            }
            assertEquals("draft original", model.getLine(0));
            assertEquals(baseline, JvmDocumentStorage.baselineOf(model.fileBaseline()));
            assertTrue(engine.state().isDirty());
            engine.undo();
            assertFalse(engine.state().isDirty());
        }
    }

    private TextModel draft() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "draft");
        return model;
    }

    private Path createFifo(Path target) throws Exception {
        assumePosix();
        Process process;
        try {
            process = new ProcessBuilder("mkfifo", target.toString()).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "mkfifo is unavailable");
            throw unavailable;
        }
        assertEquals(0, process.waitFor());
        return target;
    }

    private void assumePosix() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    }

    private static ServerSocketChannel openUnixSocket() throws IOException {
        try {
            return ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        } catch (UnsupportedOperationException unavailable) {
            assumeTrue(false, "Unix domain sockets are unavailable");
            throw unavailable;
        }
    }

    private static void assertSpecialFileRejected(Path target, Executable save) throws Exception {
        assertSpecialFileRejected(target, "not a regular file", save);
    }

    private static void assertSpecialFileRejected(Path target, String expectedMessage, Executable save) throws Exception {
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon().start(() -> {
            try {
                save.execute();
                result.complete(null);
            } catch (Throwable failure) {
                result.complete(failure);
            }
        });
        Throwable failure;
        try {
            failure = result.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException blocked) {
            // Opening a FIFO for attributes can block in native code, where an
            // interrupt cannot release it. Supply a peer so a regression also
            // terminates cleanly instead of stranding the test worker.
            try (FileChannel peer = FileChannel.open(target, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                result.get(3, TimeUnit.SECONDS);
            }
            fail("Saving a special file blocked instead of rejecting it", blocked);
            return;
        }
        IOException rejected = assertInstanceOf(IOException.class, failure);
        assertTrue(rejected.getMessage().contains(expectedMessage), rejected.getMessage());
    }
}
