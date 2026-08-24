package dev.tuink.editor.session.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded multi-producer/single-session-consumer ingress for asynchronous work.
 *
 * <p>Accepted events retain FIFO order. Capacity exhaustion is reported to the
 * producer; the queue never drops or blocks implicitly. Draining is batch
 * limited for fairness with input and resize events. Producers request a
 * wakeup only when an offer changes the queue from empty to non-empty.</p>
 */
public final class BoundedExternalEventQueue<E> implements AutoCloseable {
    private final int capacity;
    private final Thread consumerThread;
    private final Runnable wakeup;
    private final ArrayDeque<E> events = new ArrayDeque<>();
    private boolean closed;
    private WakeState wakeState = WakeState.IDLE;
    private long nonEmptyTransitionGeneration;
    private long wakeAttemptGeneration;

    public BoundedExternalEventQueue(int capacity, Thread consumerThread, Runnable wakeup) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Queue capacity must be positive");
        }
        this.capacity = capacity;
        this.consumerThread = Objects.requireNonNull(consumerThread, "consumerThread");
        this.wakeup = Objects.requireNonNull(wakeup, "wakeup");
    }

    public static <E> BoundedExternalEventQueue<E> forCurrentThread(
        int capacity,
        Runnable wakeup
    ) {
        return new BoundedExternalEventQueue<>(capacity, Thread.currentThread(), wakeup);
    }

    public OfferResult offer(E event) {
        Objects.requireNonNull(event, "event");
        boolean shouldWake;
        synchronized (events) {
            if (closed) {
                return OfferResult.CLOSED;
            }
            if (events.size() >= capacity) {
                return OfferResult.FULL;
            }
            boolean wasEmpty = events.isEmpty();
            events.addLast(event);
            if (wasEmpty) {
                nonEmptyTransitionGeneration++;
            }
            shouldWake = wasEmpty && wakeState != WakeState.IN_FLIGHT;
            if (shouldWake) {
                wakeState = WakeState.IN_FLIGHT;
                wakeAttemptGeneration = nonEmptyTransitionGeneration;
            }
        }
        if (shouldWake) {
            if (!signalWakeup()) {
                return OfferResult.ACCEPTED_WAKEUP_FAILED;
            }
        }
        return OfferResult.ACCEPTED;
    }

    /** Retries a previously failed wakeup without enqueueing a duplicate event. */
    public WakeupResult retryWakeup() {
        synchronized (events) {
            if (events.isEmpty() || wakeState == WakeState.IDLE) {
                return WakeupResult.NOT_REQUIRED;
            }
            if (wakeState == WakeState.IN_FLIGHT) {
                return WakeupResult.ALREADY_IN_FLIGHT;
            }
            wakeState = WakeState.IN_FLIGHT;
            wakeAttemptGeneration = nonEmptyTransitionGeneration;
        }
        return signalWakeup() ? WakeupResult.SIGNALED : WakeupResult.FAILED;
    }

    /** Drains at most {@code maxBatch} events on the designated session thread. */
    public List<E> drain(int maxBatch) {
        assertConsumerThread();
        if (maxBatch <= 0) {
            throw new IllegalArgumentException("Drain batch size must be positive");
        }
        synchronized (events) {
            int count = Math.min(maxBatch, events.size());
            ArrayList<E> drained = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                drained.add(events.removeFirst());
            }
            if (events.isEmpty() && wakeState == WakeState.RETRY_REQUIRED) {
                // A manually drained event no longer needs the wakeup whose
                // failure was recorded. A later refill is a fresh transition.
                wakeState = WakeState.IDLE;
            }
            return List.copyOf(drained);
        }
    }

    public int size() {
        synchronized (events) {
            return events.size();
        }
    }

    public boolean isClosed() {
        synchronized (events) {
            return closed;
        }
    }

    public boolean isTerminated() {
        synchronized (events) {
            return closed && events.isEmpty();
        }
    }

    /** Rejects new work; already accepted events remain available for draining. */
    @Override
    public void close() {
        synchronized (events) {
            closed = true;
        }
    }

    /** Discards accepted work during an explicit abort shutdown. */
    public int discardPending() {
        assertConsumerThread();
        synchronized (events) {
            int discarded = events.size();
            events.clear();
            if (wakeState == WakeState.RETRY_REQUIRED) {
                wakeState = WakeState.IDLE;
            }
            return discarded;
        }
    }

    private void assertConsumerThread() {
        if (Thread.currentThread() != consumerThread) {
            throw new IllegalStateException("External events must be drained on the session thread");
        }
    }

    public enum OfferResult {
        ACCEPTED,
        /** The event is queued, but the producer must arrange another wakeup. */
        ACCEPTED_WAKEUP_FAILED,
        FULL,
        CLOSED
    }

    public enum WakeupResult {
        NOT_REQUIRED,
        ALREADY_IN_FLIGHT,
        SIGNALED,
        FAILED
    }

    private boolean signalWakeup() {
        while (true) {
            try {
                wakeup.run();
                synchronized (events) {
                    if (nonEmptyTransitionGeneration == wakeAttemptGeneration) {
                        wakeState = WakeState.IDLE;
                        return true;
                    }
                    // The consumer drained the queue and a producer refilled it
                    // while this signal was in flight. Serialize one follow-up
                    // for that real empty-to-nonempty transition. Offers made
                    // while the queue stayed non-empty do not change generation.
                    wakeAttemptGeneration = nonEmptyTransitionGeneration;
                }
            } catch (RuntimeException wakeupFailure) {
                synchronized (events) {
                    wakeState = events.isEmpty() ? WakeState.IDLE : WakeState.RETRY_REQUIRED;
                }
                return false;
            }
        }
    }

    private enum WakeState {
        IDLE,
        IN_FLIGHT,
        RETRY_REQUIRED
    }
}
