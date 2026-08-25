package dev.tuink.tui;

import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.terminal.input.PasteEvent;
import dev.tuink.terminal.input.PointerEvent;
import dev.tuink.terminal.input.TextInput;
import dev.tuink.terminal.input.TuiInputEvent;
import dev.tuink.terminal.widget.Button;
import dev.tuink.terminal.widget.FocusManager;
import dev.tuink.terminal.widget.FocusRoute;
import dev.tuink.terminal.widget.FocusScope;
import dev.tuink.terminal.widget.InteractionResult;
import dev.tuink.terminal.widget.RoutedTarget;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.ui.ConfirmBarView;
import java.util.List;
import java.util.Objects;

/** TUInk policy adapter for one placed multi-target confirmation prompt. */
final class ConfirmBarInputRouter {
    record Policy(
        ConfirmChoice yesDecision,
        ConfirmChoice noDecision,
        ConfirmChoice escapeDecision,
        ConfirmChoice ctrlQDecision,
        boolean pointerMissBubbles
    ) {
        private static final Policy RECOVERY = new Policy(
            ConfirmChoice.SAVE,
            ConfirmChoice.DISCARD,
            ConfirmChoice.DISCARD,
            ConfirmChoice.DISCARD,
            false
        );
        private static final Policy OVERWRITE = standard(ConfirmChoice.SAVE, false);
        private static final Policy QUIT = new Policy(
            ConfirmChoice.DISCARD,
            ConfirmChoice.CANCEL,
            ConfirmChoice.CANCEL,
            ConfirmChoice.DISCARD,
            false
        );
        private static final Policy CLOSE_TAB = standard(ConfirmChoice.DISCARD, true);

        Policy {
            Objects.requireNonNull(yesDecision, "yesDecision");
            Objects.requireNonNull(noDecision, "noDecision");
            Objects.requireNonNull(escapeDecision, "escapeDecision");
            Objects.requireNonNull(ctrlQDecision, "ctrlQDecision");
        }

        static Policy recovery() {
            return RECOVERY;
        }

        static Policy overwrite() {
            return OVERWRITE;
        }

        static Policy quit() {
            return QUIT;
        }

        static Policy closeTab() {
            return CLOSE_TAB;
        }

        private static Policy standard(ConfirmChoice yesDecision, boolean pointerMissBubbles) {
            return new Policy(
                yesDecision,
                ConfirmChoice.CANCEL,
                ConfirmChoice.CANCEL,
                ConfirmChoice.CANCEL,
                pointerMissBubbles
            );
        }
    }

    record Result(boolean consumed, ConfirmChoice decision) {
        private static final Result IGNORED = new Result(false, null);
        private static final Result CAPTURED = new Result(true, null);

        Result {
            if (!consumed && decision != null) {
                throw new IllegalArgumentException("an ignored event cannot decide a confirmation");
            }
        }

        static Result decided(ConfirmChoice choice) {
            return new Result(true, Objects.requireNonNull(choice, "choice"));
        }
    }

    private ConfirmBarInputRouter() {
    }

    static Result route(
        FocusManager<ConfirmChoice> focusManager,
        ConfirmBarView.Placement placement,
        Policy policy,
        TuiInputEvent event
    ) {
        Objects.requireNonNull(focusManager, "focusManager");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(event, "event");
        placement.activate(focusManager);

        // Preserve the application's truncated-paste notification path. The
        // modal branch still captures all complete paste content afterward.
        if (event instanceof PasteEvent) {
            return Result.IGNORED;
        }

        FocusScope<ConfirmChoice, Button.Action> scope = placement.focusScope();
        FocusRoute<ConfirmChoice, Button.Action> route = focusManager.route(scope, event);
        if (route.navigationConsumed()) {
            return Result.CAPTURED;
        }

        if (event instanceof TextInput text) {
            ConfirmChoice mnemonic = mnemonicDecision(text.text(), policy);
            if (mnemonic != null) {
                return decide(focusManager, scope, mnemonic);
            }
        }

        if (event instanceof KeyStroke stroke) {
            Result keyResult = handleKey(focusManager, scope, policy, stroke);
            if (keyResult != null) {
                return keyResult;
            }
        }

        if (route.target().isPresent()) {
            RoutedTarget<ConfirmChoice, Button.Action> target = route.target().orElseThrow();
            InteractionResult<Button.Action> interaction = target.placement()
                .handle(event, route.context().orElseThrow());
            focusManager.apply(scope, target.id(), interaction.focusRequest());
            if (interaction.output().orElse(null) == Button.Action.ACTIVATED) {
                return decide(focusManager, scope, target.id());
            }
            if (interaction.consumed()) {
                return Result.CAPTURED;
            }
        }

        // Only close-tab confirmation lets pointer misses continue to the
        // application, where its tab-strip close/scroll policy still applies.
        // Other confirmation modes capture misses before any editor chrome.
        return event instanceof PointerEvent && policy.pointerMissBubbles()
            ? Result.IGNORED
            : Result.CAPTURED;
    }

    private static Result handleKey(
        FocusManager<ConfirmChoice> focusManager,
        FocusScope<ConfirmChoice, Button.Action> scope,
        Policy policy,
        KeyStroke stroke
    ) {
        if (stroke.key() == KeyStroke.Key.Q
            && stroke.modifiers().contains(KeyStroke.Modifier.CTRL)
            && !stroke.modifiers().contains(KeyStroke.Modifier.ALT)
            && !stroke.modifiers().contains(KeyStroke.Modifier.SUPER)) {
            return decide(focusManager, scope, policy.ctrlQDecision());
        }
        if (!stroke.modifiers().isEmpty()) {
            return null;
        }
        return switch (stroke.key()) {
            case ARROW_RIGHT -> {
                focusManager.next(scope);
                yield Result.CAPTURED;
            }
            case ARROW_LEFT -> {
                focusManager.previous(scope);
                yield Result.CAPTURED;
            }
            case HOME -> {
                focusFirst(focusManager, scope);
                yield Result.CAPTURED;
            }
            case END -> {
                focusLast(focusManager, scope);
                yield Result.CAPTURED;
            }
            case ESCAPE -> decide(focusManager, scope, policy.escapeDecision());
            default -> null;
        };
    }

    private static ConfirmChoice mnemonicDecision(String text, Policy policy) {
        if (text == null || text.codePointCount(0, text.length()) != 1) {
            return null;
        }
        int codePoint = Character.toLowerCase(text.codePointAt(0));
        if (codePoint == 'y') {
            return policy.yesDecision();
        }
        if (codePoint == 'n') {
            return policy.noDecision();
        }
        return null;
    }

    private static Result decide(
        FocusManager<ConfirmChoice> focusManager,
        FocusScope<ConfirmChoice, Button.Action> scope,
        ConfirmChoice decision
    ) {
        if (scope.target(decision).isPresent()) {
            focusManager.focus(scope, decision);
        }
        return Result.decided(decision);
    }

    private static void focusFirst(
        FocusManager<ConfirmChoice> focusManager,
        FocusScope<ConfirmChoice, Button.Action> scope
    ) {
        focusEligible(focusManager, scope, scope.tabOrder());
    }

    private static void focusLast(
        FocusManager<ConfirmChoice> focusManager,
        FocusScope<ConfirmChoice, Button.Action> scope
    ) {
        List<ConfirmChoice> order = scope.tabOrder();
        for (int index = order.size() - 1; index >= 0; index--) {
            ConfirmChoice id = order.get(index);
            if (eligible(scope.target(id).orElse(null))) {
                focusManager.focus(scope, id);
                return;
            }
        }
    }

    private static void focusEligible(
        FocusManager<ConfirmChoice> focusManager,
        FocusScope<ConfirmChoice, Button.Action> scope,
        List<ConfirmChoice> order
    ) {
        for (ConfirmChoice id : order) {
            if (eligible(scope.target(id).orElse(null))) {
                focusManager.focus(scope, id);
                return;
            }
        }
    }

    private static boolean eligible(RoutedTarget<?, ?> target) {
        return target != null && target.enabled() && target.focusable();
    }
}
