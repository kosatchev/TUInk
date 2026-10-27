package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DocumentIoSavePublicationTest {
    @Test
    void anExecutorWhichWritesThenThrowsCannotReplaceTheActualReceiptWithRejection() throws Exception {
        MemoryDocumentStorage storage = new MemoryDocumentStorage();
        EditorDocumentSession session = dirtySession(storage);
        var work = session.io().beginSave();
        AtomicReference<DocumentIoCoordinator.Transition> result = new AtomicReference<>();
        try (DocumentIoController io = new DocumentIoController(task -> {
            runOnWorker(task);
            throw new RejectedExecutionException("executor reported failure after starting the write");
        })) {
            io.submit(work::perform, completion -> result.set(completion.succeeded()
                ? session.io().accept(work, completion.value()) : session.io().reject(work, completion.failure())));

            assertArrayEquals("draft original".getBytes(StandardCharsets.UTF_8), storage.bytes("source"));
            assertTrue(session.status().dirty(), "only the owner may publish the external receipt");
            assertTrue(io.busy(), "a started operation retains its collection slot");
            assertNull(result.get());
            assertTrue(io.collect());
            assertInstanceOf(DocumentIoCoordinator.Saved.class, result.get());
            assertFalse(session.status().dirty());
            assertFalse(session.io().busy());
        }
    }

    @Test
    void anExecutorWhichQueuesThenThrowsCannotRunTheRejectedWriteLater() throws Exception {
        MemoryDocumentStorage storage = new MemoryDocumentStorage();
        EditorDocumentSession session = dirtySession(storage);
        var work = session.io().beginSave();
        int calls = storage.calls();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicReference<DocumentIoCoordinator.Transition> result = new AtomicReference<>();
        try (DocumentIoController io = new DocumentIoController(task -> {
            queue.add(task);
            throw new RejectedExecutionException("queued before reporting rejection");
        })) {
            io.submit(work::perform, completion -> result.set(completion.succeeded()
                ? session.io().accept(work, completion.value()) : session.io().reject(work, completion.failure())));

            var failed = assertInstanceOf(DocumentIoCoordinator.Failed.class, result.get());
            assertEquals(DocumentIoCoordinator.FailureKind.FAILED_BEFORE_COMMIT, failed.kind());
            assertFalse(io.busy());
            assertFalse(session.io().busy());
            runOnWorker(queue.remove());
            assertEquals(calls, storage.calls(), "the retained executor runnable cannot call the provider");
            assertArrayEquals("original".getBytes(StandardCharsets.UTF_8), storage.bytes("source"));
            assertTrue(session.status().dirty());
            assertFalse(io.collect());
            assertInstanceOf(DocumentCommandResult.Applied.class,
                session.execute(session.defaultViewId(), new DocumentCommand.Input("after ")));
            assertEquals(java.util.List.of("draft after original"), session.snapshot().lines());
        }
    }

    private static EditorDocumentSession dirtySession(MemoryDocumentStorage storage) throws Exception {
        storage.put("source", "original".getBytes(StandardCharsets.UTF_8));
        var session = EditorDocumentSession.create(new DocumentId("publication"), java.util.List.of(""));
        var resource = storage.resource("source");
        session.openPrepared(storage.load(resource, DocumentOpenMode.DETECT, false));
        session.execute(session.defaultViewId(), new DocumentCommand.Input("draft "));
        return session;
    }

    private static void runOnWorker(Runnable task) {
        Thread worker = new Thread(task, "save-publication-test");
        worker.start();
        try {
            worker.join(5_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        assertFalse(worker.isAlive(), "detached save did not finish");
    }
}
