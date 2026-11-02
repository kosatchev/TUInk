package dev.tuiframe.memory;

import java.util.Objects;

/** Admission for retained rendering allocations, shared by all consumers of a window budget. */
public interface RenderMemoryBudget {
    /** Returns null when allocation is refused. Call before constructing the retained value. */
    Reservation tryReserve(long bytes);

    /** One reference to an admitted allocation; retaining shares its charge rather than reserving again. */
    interface Reservation extends AutoCloseable {
        long bytes();
        Reservation retain();
        @Override void close();
    }

    static Bounded bounded(long limitBytes) { return new Bounded(limitBytes); }

    static Tracked tracked(RenderMemoryBudget delegate) { return new Tracked(delegate); }

    /**
     * Observes allocations made by one rendering owner, including evicted values still held by frames.
     * Admission and limits remain entirely in the delegate. Closing a cache never resets this count.
     */
    final class Tracked implements RenderMemoryBudget {
        private final RenderMemoryBudget delegate;
        private final Counter counter = new Counter();

        private Tracked(RenderMemoryBudget delegate) { this.delegate = Objects.requireNonNull(delegate, "delegate"); }

        @Override public Reservation tryReserve(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("Allocation bytes must be nonnegative");
            Reservation admitted = delegate.tryReserve(bytes);
            if (admitted == null) return null;
            try {
                Allocation allocation = new Allocation(counter, bytes);
                TrackedLease lease = new TrackedLease(allocation, admitted);
                synchronized (counter) { counter.usedBytes = Math.addExact(counter.usedBytes, bytes); }
                return lease;
            } catch (RuntimeException | Error failure) { admitted.close(); throw failure; }
        }

        public long usedBytes() { synchronized (counter) { return counter.usedBytes; } }

        private static final class Counter { long usedBytes; }
        private static final class Allocation {
            final Counter counter;
            final long bytes;
            int references = 1;
            Allocation(Counter counter, long bytes) { this.counter = counter; this.bytes = bytes; }
        }
        /** Static leases retain accounting and the delegated reservation, never a cache/producer. */
        private static final class TrackedLease implements Reservation {
            private Allocation allocation;
            private Reservation admitted;
            private final long bytes;
            TrackedLease(Allocation allocation, Reservation admitted) {
                this.allocation = allocation; this.admitted = admitted; bytes = allocation.bytes;
            }
            @Override public long bytes() { return bytes; }
            @Override public Reservation retain() {
                Allocation current = allocation;
                if (current == null) throw new IllegalStateException("Render reservation is closed");
                synchronized (current) {
                    if (allocation == null) throw new IllegalStateException("Render reservation is closed");
                    if (current.references == Integer.MAX_VALUE) throw new IllegalStateException("Too many allocation owners");
                    Reservation backing = admitted.retain();
                    try {
                        TrackedLease retained = new TrackedLease(current, backing);
                        current.references++;
                        return retained;
                    } catch (RuntimeException | Error failure) { backing.close(); throw failure; }
                }
            }
            @Override public void close() {
                Allocation current = allocation;
                if (current == null) return;
                synchronized (current) {
                    if (allocation == null) return;
                    admitted.close(); admitted = null; allocation = null;
                    if (--current.references == 0) synchronized (current.counter) {
                        current.counter.usedBytes -= current.bytes;
                    }
                }
            }
        }
    }

    /** A standalone ledger. Applications can instead adapt their existing shared admission ledger. */
    final class Bounded implements RenderMemoryBudget, AutoCloseable {
        private final long limitBytes;
        private long usedBytes;
        private boolean closed;

        private Bounded(long limitBytes) {
            if (limitBytes < 1) throw new IllegalArgumentException("Render budget must be positive");
            this.limitBytes = limitBytes;
        }

        @Override public synchronized Reservation tryReserve(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("Allocation bytes must be nonnegative");
            if (closed || bytes > limitBytes - usedBytes) return null;
            Allocation allocation = new Allocation(bytes);
            Lease lease = new Lease(allocation);
            usedBytes += bytes;
            return lease;
        }

        public long limitBytes() { return limitBytes; }
        public synchronized long usedBytes() { return usedBytes; }

        /** Stops new allocations. Existing owners keep their charges until their last reference closes. */
        @Override public synchronized void close() { closed = true; }

        private static final class Allocation {
            final long bytes;
            int references = 1;
            Allocation(long bytes) { this.bytes = bytes; }
        }

        private final class Lease implements Reservation {
            private Allocation allocation;
            private final long bytes;
            Lease(Allocation allocation) { this.allocation = allocation; bytes = allocation.bytes; }
            @Override public long bytes() { return bytes; }

            @Override public Reservation retain() {
                synchronized (Bounded.this) {
                    if (allocation == null) throw new IllegalStateException("Render reservation is closed");
                    if (allocation.references == Integer.MAX_VALUE) throw new IllegalStateException("Too many allocation owners");
                    Lease retained = new Lease(allocation);
                    allocation.references++;
                    return retained;
                }
            }

            @Override public void close() {
                synchronized (Bounded.this) {
                    if (allocation == null) return;
                    if (--allocation.references == 0) usedBytes -= allocation.bytes;
                    allocation = null;
                }
            }
        }
    }
}
