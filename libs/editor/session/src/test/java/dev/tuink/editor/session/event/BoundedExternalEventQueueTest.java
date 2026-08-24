package dev.tuink.editor.session.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedExternalEventQueueTest {
    @Test
    void reportsBackpressurePreservesOrderAndLimitsDrainFairness() {
        AtomicInteger wakeups = new AtomicInteger();
        BoundedExternalEventQueue<String> queue =
            BoundedExternalEventQueue.forCurrentThread(3, wakeups::incrementAndGet);

        assertEquals(BoundedExternalEventQueue.OfferResult.ACCEPTED, queue.offer("a"));
        assertEquals(BoundedExternalEventQueue.OfferResult.ACCEPTED, queue.offer("b"));
        assertEquals(BoundedExternalEventQueue.OfferResult.ACCEPTED, queue.offer("c"));
        assertEquals(BoundedExternalEventQueue.OfferResult.FULL, queue.offer("d"));
        assertEquals(1, wakeups.get());

        assertEquals(List.of("a", "b"), queue.drain(2));
        assertEquals(List.of("c"), queue.drain(2));
        assertEquals(BoundedExternalEventQueue.OfferResult.ACCEPTED, queue.offer("d"));
        assertEquals(2, wakeups.get());
    }

    @Test
    void closeRejectsNewWorkAndSupportsDrainOrExplicitAbort() {
        BoundedExternalEventQueue<String> queue =
            BoundedExternalEventQueue.forCurrentThread(2, () -> { });
        queue.offer("accepted");

        queue.close();

        assertTrue(queue.isClosed());
        assertFalse(queue.isTerminated());
        assertEquals(BoundedExternalEventQueue.OfferResult.CLOSED, queue.offer("late"));
        assertEquals(List.of("accepted"), queue.drain(1));
        assertTrue(queue.isTerminated());
    }

    @Test
    void onlyTheSessionThreadMayDrain() throws InterruptedException {
        BoundedExternalEventQueue<String> queue =
            BoundedExternalEventQueue.forCurrentThread(1, () -> { });
        queue.offer("event");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try {
                queue.drain(1);
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        foreign.start();
        foreign.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(List.of("event"), queue.drain(1));
        assertThrows(IllegalArgumentException.class, () -> queue.drain(0));
    }

    @Test
    void throwingWakeupKeepsAcceptedEventsAndRequiresExplicitRetry() {
        AtomicInteger attempts = new AtomicInteger();
        BoundedExternalEventQueue<String> queue =
            BoundedExternalEventQueue.forCurrentThread(2, () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient wakeup failure");
                }
            });

        assertEquals(
            BoundedExternalEventQueue.OfferResult.ACCEPTED_WAKEUP_FAILED,
            queue.offer("first")
        );
        assertEquals(BoundedExternalEventQueue.OfferResult.ACCEPTED, queue.offer("second"));
        assertEquals(1, attempts.get());
        assertEquals(
            BoundedExternalEventQueue.WakeupResult.SIGNALED,
            queue.retryWakeup()
        );
        assertEquals(2, attempts.get());
        assertEquals(List.of("first", "second"), queue.drain(2));
    }

    @Test
    void fullQueueCanRetryFailedWakeupWithoutDuplicatingItsEvent() {
        AtomicInteger attempts = new AtomicInteger();
        BoundedExternalEventQueue<String> queue =
            BoundedExternalEventQueue.forCurrentThread(1, () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("first signal fails");
                }
            });

        assertEquals(
            BoundedExternalEventQueue.OfferResult.ACCEPTED_WAKEUP_FAILED,
            queue.offer("only")
        );
        assertEquals(
            BoundedExternalEventQueue.WakeupResult.SIGNALED,
            queue.retryWakeup()
        );
        assertEquals(List.of("only"), queue.drain(1));
    }

    @Test
    void offersWhileNonEmptyDoNotResignalAnInFlightWakeup() throws InterruptedException {
        CountDownLatch firstWakeEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWake = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        BoundedExternalEventQueue<String> queue = BoundedExternalEventQueue.forCurrentThread(2, () -> {
            int call = calls.incrementAndGet();
            int nowActive = active.incrementAndGet();
            maxActive.accumulateAndGet(nowActive, Math::max);
            try {
                if (call == 1) {
                    firstWakeEntered.countDown();
                    try {
                        releaseFirstWake.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
            } finally {
                active.decrementAndGet();
            }
        });
        AtomicReference<BoundedExternalEventQueue.OfferResult> firstResult =
            new AtomicReference<>();
        Thread first = new Thread(() -> firstResult.set(queue.offer("first")));
        first.start();
        boolean entered = firstWakeEntered.await(5, TimeUnit.SECONDS);
        if (!entered) {
            releaseFirstWake.countDown();
            first.join();
        }
        assertTrue(entered);

        assertEquals(BoundedExternalEventQueue.OfferResult.ACCEPTED, queue.offer("second"));
        releaseFirstWake.countDown();
        first.join();

        assertEquals(BoundedExternalEventQueue.OfferResult.ACCEPTED, firstResult.get());
        assertEquals(1, calls.get());
        assertEquals(1, maxActive.get());
        assertEquals(List.of("first", "second"), queue.drain(2));
    }

    @Test
    void drainThenRefillDuringWakeupProducesExactlyOneSequentialFollowUp()
        throws InterruptedException {
        CountDownLatch firstWakeEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWake = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BoundedExternalEventQueue<String> queue = BoundedExternalEventQueue.forCurrentThread(
            3,
            () -> {
                if (calls.incrementAndGet() == 1) {
                    firstWakeEntered.countDown();
                    try {
                        releaseFirstWake.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
            }
        );
        AtomicReference<BoundedExternalEventQueue.OfferResult> firstResult =
            new AtomicReference<>();
        Thread first = new Thread(() -> firstResult.set(queue.offer("first")));
        first.start();
        boolean entered = firstWakeEntered.await(5, TimeUnit.SECONDS);
        if (!entered) {
            releaseFirstWake.countDown();
            first.join();
        }
        assertTrue(entered);

        assertEquals(List.of("first"), queue.drain(1));
        assertEquals(BoundedExternalEventQueue.OfferResult.ACCEPTED, queue.offer("refill"));
        assertEquals(BoundedExternalEventQueue.OfferResult.ACCEPTED, queue.offer("still-nonempty"));
        assertEquals(1, calls.get());

        releaseFirstWake.countDown();
        first.join();

        assertEquals(BoundedExternalEventQueue.OfferResult.ACCEPTED, firstResult.get());
        assertEquals(2, calls.get());
        assertEquals(List.of("refill", "still-nonempty"), queue.drain(2));
    }
}
