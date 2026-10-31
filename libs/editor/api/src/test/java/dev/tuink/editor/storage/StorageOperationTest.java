package dev.tuink.editor.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StorageOperationTest {
    @Test
    void cancellationBeforePublicationCannotBeOverridden() throws Exception {
        StorageOperation operation = new StorageOperation(StorageOperation.Phase.STAGING);
        operation.progress(StorageOperation.Phase.STAGING, 8192, 16384);
        assertTrue(operation.requestCancellation());
        assertTrue(operation.cancelled());
        assertFalse(operation.snapshot().cancellable());
        assertThrows(InterruptedIOException.class, operation::checkCancelled);
        assertThrows(InterruptedIOException.class, operation::beginPublication);
        assertFalse(operation.publishing());
    }

    @Test
    void publicationKeepsItsFenceAndDefiniteFallbackHonoursDeferredCancellation() throws Exception {
        StorageOperation operation = StorageOperation.untracked();
        operation.beginPublication();
        assertTrue(operation.publishing());
        assertFalse(operation.requestCancellation());
        assertFalse(operation.cancelled());
        assertFalse(operation.snapshot().cancellable());
        assertDoesNotThrow(operation::checkCancelled);
        assertDoesNotThrow(operation::beginPublication);
        assertThrows(InterruptedIOException.class, operation::resumePreparation);
        assertTrue(operation.cancelled());
        assertFalse(operation.publishing());
    }

    @Test
    void definiteFallbackWithoutCancellationAllowsAnotherPublicationAttempt() throws Exception {
        StorageOperation operation = StorageOperation.untracked();
        operation.progress(StorageOperation.Phase.VERIFYING, 17, 17);
        operation.beginPublication();
        operation.resumePreparation();
        assertTrue(operation.snapshot().cancellable());
        assertEquals(StorageOperation.Phase.VERIFYING, operation.snapshot().phase());
        operation.beginPublication();
        assertTrue(operation.publishing());
    }

    @Test
    void progressIsImmutableCoalescedAndIndependentBetweenCalls() throws Exception {
        StorageOperation first = StorageOperation.untracked();
        StorageOperation second = StorageOperation.untracked();
        first.progress(StorageOperation.Phase.INSPECTING, 0, 1_048_576);
        var before = first.snapshot();
        first.progress(StorageOperation.Phase.INSPECTING, 8192, 1_048_576);
        assertSame(before, first.snapshot());
        first.progress(StorageOperation.Phase.INSPECTING, 65536, 1_048_576);
        var after = first.snapshot();
        assertEquals(0, before.completedBytes());
        assertEquals(65536, after.completedBytes());
        assertTrue(first.requestCancellation());
        assertTrue(second.snapshot().cancellable());
        assertDoesNotThrow(second::beginPublication);
    }

    @Test
    void interruptedPreparationPreservesTheInterruptFlag() {
        StorageOperation operation = StorageOperation.untracked();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, operation::checkCancelled);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(operation.cancelled());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationAndPublicationHaveExactlyOneWinner() throws Exception {
        for (int attempt = 0; attempt < 64; attempt++) {
            StorageOperation operation = StorageOperation.untracked();
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean published = new AtomicBoolean();
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread publisher = new Thread(() -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    operation.beginPublication();
                    published.set(true);
                } catch (InterruptedIOException expectedCancellation) {
                    // The final XOR assertion checks that cancellation won this race.
                } catch (Throwable unexpected) {
                    failure.compareAndSet(null, unexpected);
                }
            }, "storage-publication-race");
            Thread canceller = new Thread(() -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    cancelled.set(operation.requestCancellation());
                } catch (Throwable unexpected) {
                    failure.compareAndSet(null, unexpected);
                }
            }, "storage-cancellation-race");
            publisher.start();
            canceller.start();
            start.countDown();
            publisher.join(5_000);
            canceller.join(5_000);
            assertFalse(publisher.isAlive());
            assertFalse(canceller.isAlive());
            assertNull(failure.get());
            assertNotEquals(cancelled.get(), published.get());
        }
    }
}
