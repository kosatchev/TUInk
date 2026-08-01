package dev.tuink.terminal.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * Coordinates input polling, render invalidation, resize and stop requests.
 *
 * <p>The application remains responsible for updating its model and painting a
 * frame. This class owns the timing and state transitions around those actions,
 * so another terminal application can reuse the same deterministic loop policy.</p>
 */
public final class TuiEventLoop<E> {
    private static final long IDLE_INPUT_POLL_TIMEOUT_MS = 16L;
    private static final long MAX_DEADLINE_INPUT_POLL_TIMEOUT_MS = 50L;

    private final TimedInput<E> input;
    private final NanoClock clock;
    private boolean stopRequested;
    private boolean resizePending;
    private E resizeEventPending;
    private boolean renderPending = true;
    private boolean fullRedrawPending = true;
    private long nextFrameSequence;
    private long activeFrameSequence;

    public TuiEventLoop(TimedInput<E> input) {
        this(input, System::nanoTime);
    }

    TuiEventLoop(TimedInput<E> input, NanoClock clock) {
        this.input = Objects.requireNonNull(input, "input");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void requestResize() {
        resizePending = true;
        renderPending = true;
        fullRedrawPending = true;
    }

    /** Coalesces resize signals while retaining the latest typed resize event. */
    public synchronized void requestResize(E resizeEvent) {
        resizePending = true;
        resizeEventPending = Objects.requireNonNull(resizeEvent, "resizeEvent");
        renderPending = true;
        fullRedrawPending = true;
    }

    public synchronized void requestStop() {
        stopRequested = true;
    }

    public synchronized boolean running() {
        return !stopRequested;
    }

    public synchronized void invalidate() {
        renderPending = true;
    }

    /**
     * Claims the currently pending frame, if any.
     *
     * <p>Pending state is cleared before application rendering starts. An
     * invalidation arriving during rendering therefore schedules another frame
     * instead of being lost.</p>
     */
    public synchronized FrameTicket beginFrame() {
        if (activeFrameSequence != 0L) {
            throw new IllegalStateException("A frame is already active");
        }
        if (!renderPending) {
            return null;
        }

        renderPending = false;
        boolean fullRedraw = fullRedrawPending;
        fullRedrawPending = false;
        activeFrameSequence = ++nextFrameSequence;
        return new FrameTicket(activeFrameSequence, fullRedraw);
    }

    public synchronized void endFrame(FrameTicket ticket, boolean rendered) {
        Objects.requireNonNull(ticket, "ticket");
        if (ticket.sequence() != activeFrameSequence) {
            throw new IllegalArgumentException("Frame ticket is not active");
        }
        activeFrameSequence = 0L;
        if (!rendered) {
            renderPending = true;
            fullRedrawPending |= ticket.fullRedraw();
        }
    }

    /**
     * Polls one input result using the current application deadline.
     *
     * <p>A stop request does not discard a result already being read. The caller
     * may process that result; {@link #running()} becomes false before the next
     * application iteration.</p>
     */
    public Poll<E> poll(Deadline deadline) throws IOException {
        Objects.requireNonNull(deadline, "deadline");
        synchronized (this) {
            if (resizeEventPending != null) {
                E event = resizeEventPending;
                resizeEventPending = null;
                resizePending = false;
                return new Poll<>(event, true, false);
            }
        }

        long timeoutMillis = timeoutMillis(deadline.nanosUntil(clock.nanoTime()));
        Read<E> read = Objects.requireNonNull(input.poll(timeoutMillis), "input result");

        boolean resized;
        E deliveredResize = null;
        synchronized (this) {
            if (resizeEventPending != null && read.kind() == ReadKind.IDLE) {
                deliveredResize = resizeEventPending;
                resizeEventPending = null;
                resizePending = false;
                resized = true;
            } else if (resizeEventPending != null) {
                // Preserve a typed resize until the current input event is delivered.
                resized = false;
            } else {
                resized = resizePending;
                resizePending = false;
            }
            if (read.kind() == ReadKind.EVENT) {
                renderPending = true;
            } else if (read.kind() == ReadKind.END) {
                stopRequested = true;
            }
        }

        if (deliveredResize != null) {
            return new Poll<>(deliveredResize, true, false);
        }
        return new Poll<>(read.event(), resized, read.kind() == ReadKind.END);
    }

    private static long timeoutMillis(long nanosUntilDeadline) {
        if (nanosUntilDeadline < 0L) {
            return IDLE_INPUT_POLL_TIMEOUT_MS;
        }
        long millisUntilDeadline = nanosUntilDeadline / 1_000_000L;
        if (nanosUntilDeadline % 1_000_000L != 0L) {
            millisUntilDeadline++;
        }
        return Math.max(0L, Math.min(MAX_DEADLINE_INPUT_POLL_TIMEOUT_MS, millisUntilDeadline));
    }

    @FunctionalInterface
    public interface TimedInput<E> {
        Read<E> poll(long timeoutMillis) throws IOException;
    }

    @FunctionalInterface
    public interface Deadline {
        /** Returns nanoseconds until the deadline, or a negative value when none exists. */
        long nanosUntil(long nowNanos);
    }

    @FunctionalInterface
    interface NanoClock {
        long nanoTime();
    }

    public enum ReadKind {
        EVENT,
        IDLE,
        END
    }

    public record Read<E>(ReadKind kind, E event) {
        public Read {
            Objects.requireNonNull(kind, "kind");
            if ((kind == ReadKind.EVENT) != (event != null)) {
                throw new IllegalArgumentException("Only EVENT results may contain an event");
            }
        }

        public static <E> Read<E> event(E event) {
            return new Read<>(ReadKind.EVENT, Objects.requireNonNull(event, "event"));
        }

        public static <E> Read<E> idle() {
            return new Read<>(ReadKind.IDLE, null);
        }

        public static <E> Read<E> end() {
            return new Read<>(ReadKind.END, null);
        }
    }

    public record Poll<E>(E event, boolean resized, boolean endOfInput) {
        public Poll {
            if (endOfInput && event != null) {
                throw new IllegalArgumentException("End-of-input cannot contain an event");
            }
        }
    }

    public record FrameTicket(long sequence, boolean fullRedraw) {
        public FrameTicket {
            if (sequence <= 0L) {
                throw new IllegalArgumentException("sequence must be positive");
            }
        }
    }
}
