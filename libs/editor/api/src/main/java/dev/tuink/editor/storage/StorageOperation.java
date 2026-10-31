package dev.tuink.editor.storage;

import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Cooperative preparation cancellation and the fence immediately before external publication. */
public final class StorageOperation {
    public enum Phase { INSPECTING, VERIFYING, STAGING, PUBLISHING }

    /** A negative total means that the provider cannot determine it cheaply. */
    public record Progress(Phase phase, long completedBytes, long totalBytes, boolean cancellable) {}

    private static final int ACTIVE = 0;
    private static final int CANCELLED = 1;
    private static final int PUBLISHING = 2;
    private static final int PUBLISHING_CANCEL_REQUESTED = 3;
    private static final long PROGRESS_STEP = 64 * 1024;
    private final AtomicInteger gate = new AtomicInteger();
    private final Progress publishingProgress = new Progress(Phase.PUBLISHING, 0, -1, false);
    private volatile Progress progress;
    private Progress preparationProgress;

    public StorageOperation(Phase initialPhase) {
        progress = new Progress(Objects.requireNonNull(initialPhase, "initialPhase"), 0, -1, true);
        preparationProgress = progress;
    }

    /** Each synchronous caller receives its own fence; no cancellation state is shared across saves. */
    public static StorageOperation untracked() { return new StorageOperation(Phase.VERIFYING); }

    public Progress snapshot() {
        Progress value = progress;
        boolean cancellable = gate.get() == ACTIVE;
        if (value.cancellable() == cancellable) return value;
        try {
            return new Progress(value.phase(), value.completedBytes(), value.totalBytes(), cancellable);
        } catch (OutOfMemoryError unavailable) {
            // The atomic gate, not an optional presentation snapshot, controls cancellation.
            return publishing() ? publishingProgress : value;
        }
    }

    /** False means that publication has won the race and its real outcome must be collected. */
    public boolean requestCancellation() {
        while (true) {
            int current = gate.get();
            if (current == CANCELLED) return true;
            if (current == PUBLISHING_CANCEL_REQUESTED) return false;
            int next = current == PUBLISHING ? PUBLISHING_CANCEL_REQUESTED : CANCELLED;
            if (gate.compareAndSet(current, next)) return current == ACTIVE;
        }
    }

    public boolean publishing() { return gate.get() >= PUBLISHING; }
    public boolean cancelled() { return gate.get() == CANCELLED; }

    public void checkCancelled() throws InterruptedIOException {
        if (gate.get() == ACTIVE && Thread.currentThread().isInterrupted()) requestCancellation();
        if (cancelled()) throw new InterruptedIOException("Save operation was cancelled");
    }

    /** Providers report bounded chunks; repeated updates are coalesced without affecting cancellation. */
    public void progress(Phase phase, long completedBytes, long totalBytes) throws InterruptedIOException {
        Objects.requireNonNull(phase, "phase");
        if (completedBytes < 0 || totalBytes < -1) throw new IllegalArgumentException("Invalid storage progress");
        checkCancelled();
        Progress previous = progress;
        boolean cancellable = !publishing();
        if (previous.phase() != phase || previous.totalBytes() != totalBytes || completedBytes == 0
                || completedBytes == totalBytes || completedBytes < previous.completedBytes()
                || completedBytes - previous.completedBytes() >= PROGRESS_STEP
                || previous.cancellable() != cancellable) {
            try {
                progress = new Progress(phase, completedBytes, totalBytes, cancellable);
            } catch (OutOfMemoryError unavailable) {
                // Reporting progress must not interrupt an already publishing stream.
            }
        }
    }

    /** Must win against cancellation before the first call that can publish target bytes. */
    public void beginPublication() throws InterruptedIOException {
        checkCancelled();
        if (gate.compareAndSet(ACTIVE, PUBLISHING)) {
            preparationProgress = progress;
            progress = publishingProgress;
        } else if (cancelled()) {
            throw new InterruptedIOException("Save operation was cancelled");
        }
    }

    /** Only a definitely unpublished attempt may make the next preparation cancellable again. */
    public void resumePreparation() throws InterruptedIOException {
        while (true) {
            int current = gate.get();
            if (current < PUBLISHING) break;
            int next = current == PUBLISHING_CANCEL_REQUESTED ? CANCELLED : ACTIVE;
            if (gate.compareAndSet(current, next)) {
                progress = preparationProgress;
                break;
            }
        }
        checkCancelled();
    }
}
