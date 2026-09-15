package dev.tuiframe.widget;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Owner-thread state for a brief dialog highlight, with a 180 ms duration and
 * at least 360 ms between starts for the same owner.
 *
 * <p>The application chooses when attention is appropriate and schedules its
 * own repaint. Owners are compared with {@link Objects#equals(Object, Object)};
 * a null owner disables attention. Supply timestamps from one monotonic clock,
 * such as {@link System#nanoTime()}, to every timing method.</p>
 */
public final class DialogAttention {
    private static final long HIGHLIGHT_NANOS = TimeUnit.MILLISECONDS.toNanos(180);
    private static final long MIN_START_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(360);

    private Object owner;
    private boolean highlighted;
    private boolean hasFlashStart;
    private long flashStartedAt;

    /** Resets on owner changes, expires due highlights, and reports a visible state change. */
    public boolean synchronize(Object owner, long nowNanos) {
        boolean wasHighlighted = highlighted;
        if (!Objects.equals(this.owner, owner)) {
            this.owner = owner;
            highlighted = false;
            hasFlashStart = false;
        } else if (highlighted && nowNanos - flashStartedAt >= HIGHLIGHT_NANOS) {
            highlighted = false;
        }
        return wasHighlighted != highlighted;
    }

    /** Requests attention without extending an active highlight or bypassing its cooldown. */
    public boolean request(Object owner, long nowNanos) {
        boolean changed = synchronize(owner, nowNanos);
        if (owner == null || highlighted
            || hasFlashStart && nowNanos - flashStartedAt < MIN_START_INTERVAL_NANOS) {
            return changed;
        }
        flashStartedAt = nowNanos;
        hasFlashStart = true;
        highlighted = true;
        return true;
    }

    /** Returns the state last updated by synchronize or request. */
    public boolean highlighted() {
        return highlighted;
    }

    /**
     * Returns the remaining highlight duration, zero when synchronization is
     * due, or -1 when there is no active highlight. This method does not mutate state.
     */
    public long nanosUntilChange(long nowNanos) {
        if (!highlighted) {
            return -1L;
        }
        long elapsed = nowNanos - flashStartedAt;
        return elapsed >= HIGHLIGHT_NANOS ? 0L : HIGHLIGHT_NANOS - elapsed;
    }
}
