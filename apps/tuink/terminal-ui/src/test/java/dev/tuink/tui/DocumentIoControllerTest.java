package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DocumentIoControllerTest {
    @Test
    void workerResultsAreAppliedOnlyWhenCollectedByTheOwner() throws Exception {
        Queue<Runnable> queued = new ArrayDeque<>();
        Thread owner = Thread.currentThread();
        AtomicReference<Thread> reader = new AtomicReference<>();
        AtomicReference<String> applied = new AtomicReference<>();
        try (DocumentIoController io = new DocumentIoController(queued::add)) {
            io.submit(() -> { reader.set(Thread.currentThread()); return "loaded"; }, result -> {
                assertSame(owner, Thread.currentThread());
                assertNull(result.failure());
                applied.set(result.value());
            });
            assertTrue(io.busy());
            assertFalse(io.collect());
            assertThrows(IllegalStateException.class, () -> io.submit(() -> "queued", ignored -> {}));
            Thread worker = new Thread(queued.remove());
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNotSame(owner, reader.get());
            assertNull(applied.get());
            assertTrue(io.collect());
            assertEquals("loaded", applied.get());
            assertFalse(io.busy());
            assertFalse(io.collect());
        }
    }

    @Test
    void failuresReleaseTheSlotAndRetainTheOriginalIoError() {
        IOException failure = new IOException("disk refused the write");
        AtomicReference<IOException> reported = new AtomicReference<>();
        try (DocumentIoController io = new DocumentIoController(Runnable::run)) {
            io.submit(() -> { throw failure; }, result -> reported.set(result.failure()));
            assertTrue(io.collect());
            assertSame(failure, reported.get());
            io.submit(() -> "next", result -> assertEquals("next", result.value()));
            assertTrue(io.collect());
        }
    }

    @Test
    void rejectedWorkerSubmissionUsesTheSameCompletionAndReleasesTheSlot() {
        AtomicReference<IOException> reported = new AtomicReference<>();
        try (DocumentIoController io = new DocumentIoController(task -> {
            throw new java.util.concurrent.RejectedExecutionException("worker unavailable");
        })) {
            io.submit(() -> "never", result -> reported.set(result.failure()));
            assertNotNull(reported.get());
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, reported.get().getCause());
            assertFalse(io.busy());
        }
    }

    @Test
    void shutdownAppliesTheCompletedWriteBeforeRejectingFurtherWork() {
        AtomicReference<String> published = new AtomicReference<>();
        DocumentIoController io = new DocumentIoController(Runnable::run);
        io.submit(() -> "saved", result -> published.set(result.value()));
        io.close();
        assertEquals("saved", published.get());
        assertThrows(IllegalStateException.class, () -> io.submit(() -> "late", ignored -> {}));
        io.close();
    }
}
