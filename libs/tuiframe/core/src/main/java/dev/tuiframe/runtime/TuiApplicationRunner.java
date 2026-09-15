package dev.tuiframe.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * Runs one terminal frontend on a {@link TuiEventLoop}.
 *
 * <p>All frontend callbacks execute sequentially on the calling thread. The
 * frontend therefore remains the owner of its application state, while this
 * runner owns only the ordering of turn preparation, frame scheduling and
 * input polling. Terminal resource lifecycle remains the caller's
 * responsibility.</p>
 *
 * @param <E> physical input event type
 */
public final class TuiApplicationRunner<E> {
    private final TuiEventLoop<E> eventLoop;

    public TuiApplicationRunner(TuiEventLoop<E> eventLoop) {
        this.eventLoop = Objects.requireNonNull(eventLoop, "eventLoop");
    }

    /**
     * Runs turns until either the frontend or event loop stops.
     *
     * <p>Each turn prepares frontend state, claims and completes a pending
     * frame, polls once using the current frontend deadline, and delivers the
     * complete poll result. Idle and end-of-input results are delivered too.
     * If rendering fails, the active frame is completed as not rendered before
     * the failure propagates, and {@link Frontend#frameEnded(boolean)} is not
     * called.</p>
     *
     * @throws IOException when input polling fails
     */
    public void run(Frontend<E> frontend) throws IOException {
        Objects.requireNonNull(frontend, "frontend");
        TuiEventLoop.Deadline deadline = frontend::nanosUntilWakeup;

        while (eventLoop.running() && frontend.running()) {
            frontend.prepareTurn();

            TuiEventLoop.FrameTicket frameTicket = eventLoop.beginFrame();
            if (frameTicket != null) {
                boolean rendered = false;
                boolean renderCompleted = false;
                try {
                    rendered = frontend.renderFrame(frameTicket);
                    renderCompleted = true;
                } finally {
                    eventLoop.endFrame(frameTicket, renderCompleted && rendered);
                }
                frontend.frameEnded(rendered);
            }

            TuiEventLoop.Poll<E> poll = eventLoop.poll(deadline);
            frontend.handlePoll(poll);
        }
    }

    /**
     * Owner-thread callbacks for one terminal application frontend.
     *
     * <p>The runner never invokes callbacks concurrently. Implementations may
     * retain ordinary mutable owner-thread state, but must not close terminal
     * resources from an implicit runner lifecycle: {@link #run(Frontend)} does
     * not own those resources.</p>
     */
    public interface Frontend<E> {
        /** Returns whether the application wants another turn. */
        boolean running();

        /** Prepares application state before the current turn claims a frame. */
        void prepareTurn();

        /**
         * Renders the claimed frame and returns whether it completed
         * successfully.
         */
        boolean renderFrame(TuiEventLoop.FrameTicket frameTicket);

        /** Runs after a normally completed frame has been returned to the scheduler. */
        default void frameEnded(boolean rendered) {
        }

        /** Returns nanoseconds until the next wakeup, or a negative value for no deadline. */
        long nanosUntilWakeup(long nowNanos);

        /** Handles one complete poll result, including idle and end-of-input results. */
        void handlePoll(TuiEventLoop.Poll<E> poll);
    }
}
