package dev.tuiframe.input;

import java.util.Objects;

/** A stationary half-second hold before object movement; selection and resizing remain immediate. */
public final class PointerDragPolicy {
    public static final long HOLD_NANOS = 500_000_000L;

    private PointerDragPolicy() {}

    public static boolean ready(long pressedAt, long now) {
        return now - pressedAt >= HOLD_NANOS;
    }

    public static long nanosUntilReady(long pressedAt, long now) {
        return Math.max(0L, HOLD_NANOS - (now - pressedAt));
    }

    public enum Phase { WAITING, READY, DRAGGING, CANCELLED }

    /** Early motion cancels this press permanently, including after a return to the origin. */
    public record Gesture(int startRow, int startColumn, long pressedAt, Phase phase) {
        public Gesture { Objects.requireNonNull(phase); }
        public static Gesture begin(int row, int column, long now) { return new Gesture(row, column, now, Phase.WAITING); }
        public boolean waiting() { return phase == Phase.WAITING; }
        public boolean armed() { return phase == Phase.READY || dragging(); }
        public boolean dragging() { return phase == Phase.DRAGGING; }
        public boolean cancelled() { return phase == Phase.CANCELLED; }
        public Gesture move(int row, int column, long now) {
            if (cancelled() || dragging()) return this;
            if (startRow == row && startColumn == column) return tick(now);
            return new Gesture(startRow, startColumn, pressedAt, armed() || ready(pressedAt, now) ? Phase.DRAGGING : Phase.CANCELLED);
        }
        public Gesture tick(long now) {
            return waiting() && ready(pressedAt, now) ? new Gesture(startRow, startColumn, pressedAt, Phase.READY) : this;
        }
        public long nanosUntilWakeup(long now) { return waiting() ? nanosUntilReady(pressedAt, now) : -1L; }
    }
}
