package dev.tuink.markdown;

import java.util.concurrent.CancellationException;

/** Host-owned cooperative cancellation; implementations must be safe to query on a worker. */
@FunctionalInterface
public interface ParseControl {
    ParseControl NONE = () -> false;
    boolean cancelled();
    default void check() {
        if (cancelled() || Thread.currentThread().isInterrupted()) throw new CancellationException("Markdown request cancelled");
    }
}
