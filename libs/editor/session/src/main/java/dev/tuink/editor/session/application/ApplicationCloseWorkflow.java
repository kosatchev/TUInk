package dev.tuink.editor.session.application;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Portable decisions for a user-requested application close.
 *
 * <p>The host owns recipients and pending operations, executes effects, and supplies
 * monotonic time. Operations are compared by identity: equal counters from different
 * hosts or successive work items cannot finish the same close request. No worker,
 * document, frontend state, or platform resource is created by this workflow.
 */
public final class ApplicationCloseWorkflow {
    public static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);
    public enum Phase { OPEN, CANCELLING, CONFIRMING, SAVING, WAITING, UNCERTAIN, CLOSED }
    public enum Policy { LIVE_RECOVERY, SNAPSHOT_RETAIN }
    public enum Continuation { RECHECK_DOCUMENTS, SAVE_ALL }
    public enum Effect { NONE, CANCEL_PREPARATION, CONFIRM, SAVE_ALL, WAIT, RETAIN_SNAPSHOT, EXPIRE_LIVE_SAVE, EXIT }
    public enum Outcome { SAVED, FAILED, CANCELLED }
    public enum Choice { SAVE, DISCARD, CANCEL }
    public enum SaveProgress { FINISHED, CONFIRM, CONTINUE, CANCELLED }

    public record Wait<O>(O operation, Policy policy, Continuation continuation, long deadline) {
        public Wait {
            Objects.requireNonNull(operation); Objects.requireNonNull(policy); Objects.requireNonNull(continuation);
            if (policy == Policy.SNAPSHOT_RETAIN && continuation != Continuation.RECHECK_DOCUMENTS)
                throw new IllegalArgumentException("Snapshots do not participate in Save-All");
        }
        public boolean expired(long now) { return now - deadline >= 0; }
    }

    public record State<R, O>(Phase phase, R recipient, Wait<O> waiting) {
        public State {
            Objects.requireNonNull(phase);
            if ((phase == Phase.WAITING || phase == Phase.UNCERTAIN) != (waiting != null))
                throw new IllegalArgumentException("Only a pending or uncertain publication has a wait");
            if (phase != Phase.OPEN) Objects.requireNonNull(recipient);
        }
        public static <R, O> State<R, O> open() { return new State<>(Phase.OPEN, null, null); }
        public boolean running() { return phase != Phase.CLOSED; }
    }

    public record Decision<R, O>(State<R, O> state, Effect effect) {
        public Decision { Objects.requireNonNull(state); Objects.requireNonNull(effect); }
    }

    private ApplicationCloseWorkflow() { }

    public static <R, O> Decision<R, O> request(State<R, O> state, R recipient, O pending) {
        if (!state.running()) return unchanged(state);
        if (state.waiting() != null && state.waiting().operation() == pending) return unchanged(state);
        return new Decision<>(new State<>(Phase.CANCELLING, recipient, null), Effect.CANCEL_PREPARATION);
    }

    public static <R, O> Decision<R, O> prepared(State<R, O> state, O pending, Policy policy,
        Continuation continuation, long now, boolean confirmDirty) {
        if (state.phase() != Phase.CANCELLING) return unchanged(state);
        return pending == null ? ready(state.recipient(), confirmDirty)
            : waiting(state.recipient(), pending, policy, continuation, now);
    }

    public static <R, O> Decision<R, O> choose(State<R, O> state, Choice choice) {
        if (state.phase() != Phase.CONFIRMING) return unchanged(state);
        return switch (choice) {
            case CANCEL -> new Decision<>(State.open(), Effect.NONE);
            case DISCARD -> new Decision<>(new State<>(Phase.CLOSED, state.recipient(), null), Effect.EXIT);
            case SAVE -> new Decision<>(new State<>(Phase.SAVING, state.recipient(), null), Effect.SAVE_ALL);
        };
    }

    /** A Save-All write starts its bound only when it crosses the publication fence. */
    public static <R, O> Decision<R, O> publishing(State<R, O> state, O operation, long now) {
        if (state.phase() != Phase.SAVING || operation == null) return unchanged(state);
        return waiting(state.recipient(), operation, Policy.LIVE_RECOVERY, Continuation.SAVE_ALL, now);
    }

    /** Called before applying a matching receipt's potentially reentrant continuation. */
    public static <R, O> Decision<R, O> completed(State<R, O> state, O operation, Outcome outcome, boolean confirmDirty) {
        if (state.waiting() == null || state.waiting().operation() != operation) return unchanged(state);
        if (state.phase() == Phase.UNCERTAIN) return new Decision<>(State.open(), Effect.NONE);
        if (outcome != Outcome.SAVED) return new Decision<>(State.open(), Effect.NONE);
        if (state.waiting().continuation() == Continuation.SAVE_ALL)
            return new Decision<>(new State<>(Phase.SAVING, state.recipient(), null), Effect.NONE);
        return ready(state.recipient(), confirmDirty);
    }

    public static <R, O> Decision<R, O> tick(State<R, O> state, O pending, long now) {
        if (state.phase() != Phase.WAITING || state.waiting().operation() != pending || !state.waiting().expired(now))
            return unchanged(state);
        return new Decision<>(new State<>(Phase.UNCERTAIN, state.recipient(), state.waiting()),
            state.waiting().policy() == Policy.LIVE_RECOVERY ? Effect.EXPIRE_LIVE_SAVE : Effect.RETAIN_SNAPSHOT);
    }

    /** Applies a save continuation without importing a frontend's dialogs or widgets. */
    public static <R, O> State<R, O> saveProgress(State<R, O> state, R recipient, SaveProgress progress) {
        Objects.requireNonNull(progress, "progress");
        R owner = state.recipient() == null ? recipient : state.recipient();
        return switch (progress) {
            case FINISHED -> new State<>(Phase.CLOSED, owner, null);
            case CONFIRM -> new State<>(Phase.CONFIRMING, owner, null);
            case CONTINUE -> new State<>(Phase.SAVING, owner, null);
            case CANCELLED -> State.open();
        };
    }

    public static <R, O> State<R, O> cancel(State<R, O> state) {
        return state.phase() == Phase.CLOSED ? state : State.open();
    }

    private static <R, O> Decision<R, O> ready(R recipient, boolean confirmDirty) {
        return new Decision<>(new State<>(confirmDirty ? Phase.CONFIRMING : Phase.CLOSED, recipient, null),
            confirmDirty ? Effect.CONFIRM : Effect.EXIT);
    }

    private static <R, O> Decision<R, O> waiting(R recipient, O operation,
        Policy policy, Continuation continuation, long now) {
        return new Decision<>(new State<>(Phase.WAITING, recipient, new Wait<>(operation, policy, continuation, now + WAIT_NANOS)), Effect.WAIT);
    }

    private static <R, O> Decision<R, O> unchanged(State<R, O> state) { return new Decision<>(state, Effect.NONE); }
}
