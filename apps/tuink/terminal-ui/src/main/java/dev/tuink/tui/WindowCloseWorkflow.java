package dev.tuink.tui;

import dev.tuink.tui.flow.ConfirmChoice;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Pure decisions for a user-requested window exit; owns no worker or terminal resource. */
final class WindowCloseWorkflow {
    static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);
    enum Phase { OPEN, CANCELLING, CONFIRMING, SAVING, WAITING, UNCERTAIN, CLOSED }
    enum Policy { LIVE_RECOVERY, SNAPSHOT_RETAIN }
    enum Continuation { RECHECK_DOCUMENTS, SAVE_ALL }
    enum Effect { NONE, CANCEL_PREPARATION, CONFIRM, SAVE_ALL, WAIT, RETAIN_SNAPSHOT, EXPIRE_LIVE_SAVE, EXIT }
    enum Outcome { SAVED, FAILED, CANCELLED }

    record Wait(DocumentIoController.Operation operation, Policy policy, Continuation continuation, long deadline) {
        Wait {
            Objects.requireNonNull(operation); Objects.requireNonNull(policy); Objects.requireNonNull(continuation);
            if (policy == Policy.SNAPSHOT_RETAIN && continuation != Continuation.RECHECK_DOCUMENTS)
                throw new IllegalArgumentException("Snapshots do not participate in Save-All");
        }
        boolean expired(long now) { return now - deadline >= 0; }
    }

    record State(Phase phase, UiRecipient recipient, Wait waiting) {
        State {
            Objects.requireNonNull(phase);
            if ((phase == Phase.WAITING || phase == Phase.UNCERTAIN) != (waiting != null))
                throw new IllegalArgumentException("Only a pending or uncertain publication has a wait");
            if (phase != Phase.OPEN) Objects.requireNonNull(recipient);
        }
        static State open() { return new State(Phase.OPEN, null, null); }
        boolean running() { return phase != Phase.CLOSED; }
    }

    record Decision(State state, Effect effect) {
        Decision { Objects.requireNonNull(state); Objects.requireNonNull(effect); }
    }

    private WindowCloseWorkflow() { }

    static Decision request(State state, UiRecipient recipient, DocumentIoController.Operation pending) {
        if (!state.running()) return unchanged(state);
        if (state.waiting() != null && state.waiting().operation() == pending) return unchanged(state);
        return new Decision(new State(Phase.CANCELLING, recipient, null), Effect.CANCEL_PREPARATION);
    }

    static Decision prepared(State state, DocumentIoController.Operation pending, Policy policy,
        Continuation continuation, long now, boolean confirmDirty) {
        if (state.phase() != Phase.CANCELLING) return unchanged(state);
        return pending == null ? ready(state.recipient(), confirmDirty)
            : waiting(state.recipient(), pending, policy, continuation, now);
    }

    static Decision choose(State state, ConfirmChoice choice) {
        if (state.phase() != Phase.CONFIRMING) return unchanged(state);
        return switch (choice) {
            case CANCEL -> new Decision(State.open(), Effect.NONE);
            case DISCARD -> new Decision(new State(Phase.CLOSED, state.recipient(), null), Effect.EXIT);
            case SAVE -> new Decision(new State(Phase.SAVING, state.recipient(), null), Effect.SAVE_ALL);
        };
    }

    /** A Save-All write starts its bound only when it crosses the publication fence. */
    static Decision publishing(State state, DocumentIoController.Operation operation, long now) {
        if (state.phase() != Phase.SAVING || operation == null) return unchanged(state);
        return waiting(state.recipient(), operation, Policy.LIVE_RECOVERY, Continuation.SAVE_ALL, now);
    }

    /** Called before applying a matching receipt's potentially reentrant continuation. */
    static Decision completed(State state, DocumentIoController.Operation operation, Outcome outcome, boolean confirmDirty) {
        if (state.waiting() == null || state.waiting().operation() != operation) return unchanged(state);
        if (state.phase() == Phase.UNCERTAIN) return new Decision(State.open(), Effect.NONE);
        if (outcome != Outcome.SAVED) return new Decision(State.open(), Effect.NONE);
        if (state.waiting().continuation() == Continuation.SAVE_ALL)
            return new Decision(new State(Phase.SAVING, state.recipient(), null), Effect.NONE);
        return ready(state.recipient(), confirmDirty);
    }

    static Decision tick(State state, DocumentIoController.Operation pending, long now) {
        if (state.phase() != Phase.WAITING || state.waiting().operation() != pending || !state.waiting().expired(now))
            return unchanged(state);
        return new Decision(new State(Phase.UNCERTAIN, state.recipient(), state.waiting()),
            state.waiting().policy() == Policy.LIVE_RECOVERY ? Effect.EXPIRE_LIVE_SAVE : Effect.RETAIN_SNAPSHOT);
    }

    /** Reflects the existing SaveWorkflow's filename/overwrite/encoding continuations. */
    static State saveResult(State state, UiRecipient recipient, boolean running, boolean quitQuestion, boolean continuesQuit) {
        if (!running) return new State(Phase.CLOSED, state.recipient() == null ? recipient : state.recipient(), null);
        if (quitQuestion) return new State(Phase.CONFIRMING, state.recipient() == null ? recipient : state.recipient(), null);
        if (continuesQuit) return new State(Phase.SAVING, state.recipient() == null ? recipient : state.recipient(), null);
        return State.open();
    }

    static State cancel(State state) {
        return state.phase() == Phase.CLOSED ? state : State.open();
    }

    private static Decision ready(UiRecipient recipient, boolean confirmDirty) {
        return new Decision(new State(confirmDirty ? Phase.CONFIRMING : Phase.CLOSED, recipient, null),
            confirmDirty ? Effect.CONFIRM : Effect.EXIT);
    }

    private static Decision waiting(UiRecipient recipient, DocumentIoController.Operation operation,
        Policy policy, Continuation continuation, long now) {
        return new Decision(new State(Phase.WAITING, recipient, new Wait(operation, policy, continuation, now + WAIT_NANOS)), Effect.WAIT);
    }

    private static Decision unchanged(State state) { return new Decision(state, Effect.NONE); }
}
