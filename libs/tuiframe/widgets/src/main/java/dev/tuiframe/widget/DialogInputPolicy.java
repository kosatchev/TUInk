package dev.tuiframe.widget;

import java.util.Objects;

/**
 * Dialog access policy. Applications classify their own commands before consulting it.
 * Local controls own their history capabilities; a dialog mode only restricts the background content.
 */
public final class DialogInputPolicy {
    public enum Target {
        CONTENT,
        TEXT_INPUT,
        BUTTON,
        OTHER_CONTROL
    }

    public enum Action {
        OTHER,
        READ,
        MODIFY,
        COPY,
        CUT,
        PASTE,
        UNDO,
        REDO
    }

    public enum Decision {
        ALLOW,
        IGNORE,
        BLOCK;

        public boolean consumed() {
            return this != ALLOW;
        }
    }

    private DialogInputPolicy() {
    }

    public static Decision decide(DialogMode mode, Target target, Action action) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(action, "action");
        if (mode == DialogMode.EXCLUSIVE && target == Target.CONTENT) {
            return Decision.BLOCK;
        }
        if (target == Target.BUTTON && (action == Action.COPY || action == Action.CUT || action == Action.PASTE)) {
            return Decision.IGNORE;
        }
        if (mode == DialogMode.BLOCKING && target == Target.CONTENT) {
            return switch (action) {
                case MODIFY, CUT, PASTE, UNDO, REDO -> Decision.BLOCK;
                case OTHER, READ, COPY -> Decision.ALLOW;
            };
        }
        return Decision.ALLOW;
    }

    /** Applies rejection effects once. The caller must discard consumed input rather than replaying it. */
    public static void apply(Decision decision, Runnable focusFirst, Runnable signalBlocked) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(focusFirst, "focusFirst");
        Objects.requireNonNull(signalBlocked, "signalBlocked");
        if (decision == Decision.BLOCK) {
            focusFirst.run();
            signalBlocked.run();
        }
    }
}
