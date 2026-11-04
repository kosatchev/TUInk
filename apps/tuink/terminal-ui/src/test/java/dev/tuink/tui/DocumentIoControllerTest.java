package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import dev.tuink.editor.session.application.ApplicationCloseWorkflow;
import dev.tuink.editor.storage.StorageOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DocumentIoControllerTest {
    @Test
    void cancelledCompletedReceiptIsDisposedOnTheWorkerExactlyOnce() throws Exception {
        Queue<Runnable> queued = new ArrayDeque<>();
        AtomicInteger disposed = new AtomicInteger();
        AtomicReference<Thread> cleanupThread = new AtomicReference<>();
        Thread owner = Thread.currentThread();
        try (var io = new DocumentIoController(queued::add)) {
            io.submit(() -> "claimed draft", ignored -> fail("cancelled receipt must not publish"), value -> {
                assertEquals("claimed draft", value);
                cleanupThread.set(Thread.currentThread());
                disposed.incrementAndGet();
            });
            queued.remove().run();
            assertTrue(io.cancelRead());
            assertFalse(io.cancelRead());
            assertEquals(0, disposed.get(), "cancellation must not run filesystem cleanup on the UI owner");
            Thread cleanup = new Thread(queued.remove());
            cleanup.start();
            cleanup.join(5000);
            assertFalse(cleanup.isAlive());
            assertEquals(1, disposed.get());
            assertNotSame(owner, cleanupThread.get());
            assertFalse(io.collect());
        }
    }

    @Test
    @Timeout(5)
    void cancelledProviderThatFinishesLateDisposesItsReceiptBeforeReturning() throws Exception {
        Queue<Runnable> queued = new ArrayDeque<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger disposed = new AtomicInteger();
        try (var io = new DocumentIoController(queued::add)) {
            io.submit(() -> {
                entered.countDown();
                for (;;) {
                    try { finish.await(); break; }
                    catch (InterruptedException ignored) { /* This provider finishes its ownership transaction. */ }
                }
                return "late claim";
            }, ignored -> fail("late receipt must not publish"), value -> disposed.incrementAndGet());
            Thread worker = new Thread(queued.remove());
            worker.start();
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(io.cancelRead());
                assertFalse(io.busy());
            } finally {
                finish.countDown();
                worker.join(2000);
            }
            assertFalse(worker.isAlive());
            assertEquals(1, disposed.get());
            assertFalse(io.collect());
        }
    }

    @Test
    void shutdownDisposesAReadyUnpublishedReceiptAndNormalPublicationTransfersOwnership() {
        Queue<Runnable> queued = new ArrayDeque<>();
        AtomicInteger disposed = new AtomicInteger();
        AtomicInteger published = new AtomicInteger();
        var io = new DocumentIoController(queued::add);
        io.submit(() -> "accepted", result -> published.incrementAndGet(), value -> disposed.incrementAndGet());
        queued.remove().run();
        assertTrue(io.collect());
        assertEquals(1, published.get());
        assertEquals(0, disposed.get());
        io.submit(() -> "unpublished", result -> fail("shutdown must not publish the prompt"),
            value -> disposed.incrementAndGet());
        queued.remove().run();
        io.close(0);
        assertEquals(0, disposed.get());
        queued.remove().run();
        assertEquals(1, disposed.get());
        assertEquals(1, published.get());
    }

    @Test
    void receiptReadyAtTheQuitDeadlineCanStartTheNextSave() {
        Queue<Runnable> queued = new ArrayDeque<>();
        AtomicReference<String> published = new AtomicReference<>();
        try (DocumentIoController io = new DocumentIoController(queued::add)) {
            io.submitSave(() -> "first", result -> {
                published.set(result.value());
                io.submitSave(() -> "second", next -> published.set(next.value()));
            });
            var expiring = io.pendingOperation();
            queued.remove().run();

            assertDoesNotThrow(() -> io.expireSave(expiring));

            assertEquals("first", published.get());
            assertTrue(io.busy(), "the ready receipt may continue a multi-document quit");
            queued.remove().run();
            assertTrue(io.collect());
            assertEquals("second", published.get());
            assertFalse(io.busy());
        }
    }

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
        AtomicReference<DocumentIoController.Operation> rejected = new AtomicReference<>();
        try (DocumentIoController io = new DocumentIoController(task -> {
            throw new java.util.concurrent.RejectedExecutionException("worker unavailable");
        })) {
            io.submit(() -> "never", result -> { rejected.set(result.operation()); reported.set(result.failure()); });
            assertNotNull(reported.get());
            assertNotNull(rejected.get());
            assertEquals(DocumentIoController.Kind.READ, rejected.get().kind());
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, reported.get().getCause());
            assertFalse(io.busy());
        }
    }

    @Test
    void shutdownAppliesTheCompletedWriteBeforeRejectingFurtherWork() {
        AtomicReference<String> published = new AtomicReference<>();
        DocumentIoController io = new DocumentIoController(Runnable::run);
        io.submitSave(() -> "saved", result -> published.set(result.value()));
        io.close(0);
        assertEquals("saved", published.get());
        assertThrows(IllegalStateException.class, () -> io.submit(() -> "late", ignored -> {}));
        io.close();
    }

    @Test
    void cancellingAQueuedReadCannotPublishIntoTheNextSave() {
        Queue<Runnable> queued = new ArrayDeque<>();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger replies = new AtomicInteger();
        AtomicReference<String> saved = new AtomicReference<>();
        try (DocumentIoController io = new DocumentIoController(queued::add)) {
            io.submit(() -> { reads.incrementAndGet(); return "old document"; },
                ignored -> replies.incrementAndGet());
            assertTrue(io.cancelRead());
            assertFalse(io.busy());
            io.submitSave(() -> "new save", result -> saved.set(result.value()));

            queued.remove().run();
            assertFalse(io.collect(), "an abandoned read cannot complete a later save");
            assertTrue(io.busy());
            assertNull(saved.get());
            queued.remove().run();
            assertTrue(io.collect());

            assertEquals(0, reads.get());
            assertEquals(0, replies.get());
            assertEquals("new save", saved.get());
            assertFalse(io.busy());
        }
    }

    @Test
    void readCancellationCannotAbandonSaveWork() {
        Queue<Runnable> queued = new ArrayDeque<>();
        AtomicReference<String> saved = new AtomicReference<>();
        try (DocumentIoController io = new DocumentIoController(queued::add)) {
            io.submitSave(() -> "saved", result -> saved.set(result.value()));
            assertFalse(io.cancelRead());
            assertTrue(io.busy());
            queued.remove().run();
            assertTrue(io.collect());
            assertEquals("saved", saved.get());
        }
    }

    @Test
    @Timeout(15)
    void shutdownDoesNotWaitForAReadProviderThatIgnoresInterruption() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger replies = new AtomicInteger();
        DocumentIoController io = new DocumentIoController();
        try {
            io.submit(() -> {
                started.countDown();
                try {
                    while (release.getCount() != 0) {
                        try { release.await(); }
                        catch (InterruptedException cancelled) { interrupted.countDown(); }
                    }
                    return "late document";
                } finally {
                    finished.countDown();
                }
            }, ignored -> replies.incrementAndGet());
            assertTrue(started.await(5, TimeUnit.SECONDS));

            io.close();

            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertEquals(1L, finished.getCount(), "shutdown returned before the blocked read finished");
            assertFalse(io.busy());
            assertFalse(io.collect());
            assertEquals(0, replies.get());
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertFalse(io.collect(), "a result arriving after shutdown has no publication rights");
            assertEquals(0, replies.get());
        } finally {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            io.close();
        }
    }

    @Test
    @Timeout(15)
    void repeatedQuitRequestsKeepTheDeadlineWithoutInterruptingPublication() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<InterruptedException> interrupted = new AtomicReference<>();
        AtomicInteger replies = new AtomicInteger();
        StorageOperation operation = new StorageOperation(StorageOperation.Phase.STAGING);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "document-io-timeout-test");
                thread.setDaemon(true);
                return thread;
            });
        DocumentIoController io = new DocumentIoController(executor, true);
        try {
            io.submitSave(() -> {
                operation.beginPublication();
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException stopped) { interrupted.set(stopped); }
                }
                return "externally saved";
            }, ignored -> replies.incrementAndGet());
            assertTrue(started.await(5, TimeUnit.SECONDS));

            var recipient = UiRecipient.window(new TabUiState(), null);
            var requested = ApplicationCloseWorkflow.request(ApplicationCloseWorkflow.State.<UiRecipient, DocumentIoController.Operation>open(), recipient, io.pendingOperation());
            var waiting = ApplicationCloseWorkflow.prepared(requested.state(), io.pendingOperation(),
                ApplicationCloseWorkflow.Policy.LIVE_RECOVERY, ApplicationCloseWorkflow.Continuation.RECHECK_DOCUMENTS, 100L, true);
            var repeated = ApplicationCloseWorkflow.request(waiting.state(), recipient, io.pendingOperation());
            assertSame(waiting.state(), repeated.state());
            assertEquals(ApplicationCloseWorkflow.Effect.NONE,
                ApplicationCloseWorkflow.tick(repeated.state(), io.pendingOperation(), 5_000_000_099L).effect());
            assertTrue(io.busy(), "publication keeps its chance to finish before the original deadline");
            assertTrue(operation.publishing());
            assertNull(interrupted.get());
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> io.expireSave(ApplicationCloseWorkflow.tick(repeated.state(), io.pendingOperation(),
                    5_000_000_100L).state().waiting().operation()));

            assertTrue(failure.getMessage().contains("unknown"));
            assertTrue(failure.getMessage().contains("recovery"));
            assertTrue(failure.getMessage().contains("verify"));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, failure.getCause());
            assertTrue(operation.publishing());
            assertFalse(operation.cancelled(), "an unconfirmed write is not a cancelled write");
            assertFalse(io.busy());
            assertFalse(io.collect());
            assertEquals(0, replies.get());
            assertTrue(executor.isShutdown());
            assertFalse(executor.isTerminated(), "the provider is still waiting for its external result");
            release.countDown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(interrupted.get(), "the timeout must not interrupt an external publication");
            assertFalse(io.collect());
            assertEquals(0, replies.get(), "a late success cannot advance the owner's savepoint");
        } finally {
            release.countDown();
            io.close();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void collectingAReceiptClearsTheQuitDeadlineBeforeTheNextOperation() {
        Queue<Runnable> queued = new ArrayDeque<>();
        AtomicReference<String> saved = new AtomicReference<>();
        try (DocumentIoController io = new DocumentIoController(queued::add)) {
            io.submitSave(() -> "first save", result -> saved.set(result.value()));
            var first = io.pendingOperation();
            queued.remove().run();
            assertTrue(io.collect());
            assertEquals("first save", saved.get());
            assertDoesNotThrow(() -> io.expireSave(first));

            io.submitSave(() -> "next save", result -> saved.set(result.value()));
            var next = io.pendingOperation();
            assertNotSame(first, next);
            assertDoesNotThrow(() -> io.expireSave(first),
                "a later operation must not inherit an earlier quit deadline");
            assertSame(next, io.pendingOperation());
            assertTrue(io.busy());
            assertEquals("first save", saved.get());
            queued.remove().run();
            assertTrue(io.collect());
            assertEquals("next save", saved.get());
        }
    }

    @Test
    void aForeignOrOldTokenCannotCollectOrExpireAnotherOperation() {
        Queue<Runnable> queued = new ArrayDeque<>();
        AtomicReference<DocumentIoController.Operation> completed = new AtomicReference<>();
        try (DocumentIoController io = new DocumentIoController(queued::add)) {
            io.submitSave(() -> "saved", result -> completed.set(result.operation()));
            var pending = io.pendingOperation();
            var foreign = new DocumentIoController.Operation(pending.sequence(), pending.kind());
            assertFalse(io.collect(foreign));
            assertDoesNotThrow(() -> io.expireSave(foreign));
            assertSame(pending, io.pendingOperation());
            queued.remove().run();
            assertFalse(io.collect(foreign));
            assertTrue(io.collect(pending));
            assertSame(pending, completed.get());
            assertNull(io.pendingOperation());
            assertDoesNotThrow(() -> io.expireSave(pending));
        }
    }

    @Test
    void cancellingAQueuedPreparationDiscardsItsReplyWithoutAffectingTheNextWrite() {
        Queue<Runnable> queued = new ArrayDeque<>();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger replies = new AtomicInteger();
        try (DocumentIoController io = new DocumentIoController(queued::add)) {
            io.submitSave(() -> reads.incrementAndGet(), ignored -> replies.incrementAndGet());
            assertTrue(io.cancelSave());
            assertFalse(io.busy());
            io.submit(() -> "saved", result -> assertEquals("saved", result.value()));
            queued.remove().run();
            assertFalse(io.collect(), "the abandoned reply cannot complete the newer write");
            assertTrue(io.busy());
            queued.remove().run();
            assertTrue(io.collect());
            assertEquals(0, reads.get());
            assertEquals(0, replies.get());
            assertFalse(io.busy());
        }
    }

    @Test
    void cancelledPreparationDoesNotHoldShutdownEvenWhenItsProviderHasNotReturned() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger replies = new AtomicInteger();
        DocumentIoController io = new DocumentIoController();
        try {
            io.submitSave(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException cancelled) {
                    interrupted.countDown();
                    release.await();
                } finally {
                    finished.countDown();
                }
                return "late inspection";
            }, ignored -> replies.incrementAndGet());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(io.cancelSave());
            io.close();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(io.busy());
            assertFalse(io.collect());
            assertEquals(1L, finished.getCount(), "shutdown must not wait for a read-only provider");
            assertEquals(0, replies.get());
        } finally {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            io.close();
        }
    }

    @Test
    void cancelledPreparationsDoNotAccumulateBehindAnAbandonedOperation() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "document-io-cancel-test");
                thread.setDaemon(true);
                return thread;
            });
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger replies = new AtomicInteger();
        DocumentIoController io = new DocumentIoController(executor, true);
        try (io) {
            io.submitSave(() -> {
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException cancelled) { /* Simulate an uninterruptible provider. */ }
                }
                return "abandoned";
            }, ignored -> replies.incrementAndGet());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(io.cancelSave());
            for (int attempt = 0; attempt < 32; attempt++) {
                io.submitSave(() -> "queued preparation", ignored -> replies.incrementAndGet());
                assertTrue(io.cancelSave());
                assertTrue(executor.getQueue().isEmpty(), "cancelled work must release queued captures");
            }
            io.submitSave(() -> "unstarted write", ignored -> replies.incrementAndGet());
            assertTrue(io.cancelSave());
            assertTrue(executor.getQueue().isEmpty());
            assertFalse(io.busy());
            assertEquals(0, replies.get());
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void publicationRefusesCancellationAndShutdownCollectsItsResult() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> applied = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StorageOperation operation = new StorageOperation(StorageOperation.Phase.STAGING);
        DocumentIoController io = new DocumentIoController();
        try {
            io.submitSave(() -> {
                operation.beginPublication();
                started.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { failure.set(interrupted); }
                return "saved";
            }, result -> applied.set(result.value()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(operation.requestCancellation(), "publication retains its real outcome");
            assertTrue(io.busy(), "the host must keep the task when the session refuses cancellation");
            release.countDown();
            io.close();
            assertNull(failure.get());
            assertEquals("saved", applied.get());
            assertFalse(io.busy());
        } finally {
            release.countDown();
            io.close();
        }
    }
}
