package dev.tuink.tui;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusRoute;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.ConfirmationShortcuts;
import dev.tuink.tui.ui.ConfirmBarView;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** TUInk policy adapter for one placed multi-target confirmation prompt. */
final class ConfirmBarInputRouter {
    record Policy(ConfirmationShortcuts shortcuts, boolean pointerMissBubbles) {
        private static final Policy RECOVERY = new Policy(ConfirmationShortcuts.STANDARD, false);
        private static final Policy OVERWRITE = new Policy(ConfirmationShortcuts.SAVE, false);
        private static final Policy QUIT = new Policy(ConfirmationShortcuts.QUIT, false);
        private static final Policy CLOSE_TAB = new Policy(ConfirmationShortcuts.SAVE_CHANGES, true);

        Policy { Objects.requireNonNull(shortcuts, "shortcuts"); }

        static Policy recovery() { return RECOVERY; }
        static Policy overwrite() { return OVERWRITE; }
        static Policy quit() { return QUIT; }
        static Policy closeTab() { return CLOSE_TAB; }
        static Policy open() { return RECOVERY; }
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
            ConfirmChoice mnemonic = mnemonicDecision(text.text(), policy.shortcuts());
            if (mnemonic != null) {
                return decide(focusManager, placement, mnemonic);
            }
        }

        if (event instanceof KeyStroke stroke) {
            Result keyResult = handleKey(focusManager, placement, policy, stroke);
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
                return decide(focusManager, placement, target.id());
            }
            if (interaction.consumed()) {
                return Result.CAPTURED;
            }
        }

        // Only close-tab confirmation lets pointer misses continue to the
        // application, where its tab-strip close/scroll policy still applies.
        // Other confirmation modes capture misses before any editor chrome.
        return event instanceof PointerEvent && policy.pointerMissBubbles() && placement.decisionsAvailable()
            ? Result.IGNORED
            : Result.CAPTURED;
    }

    private static Result handleKey(
        FocusManager<ConfirmChoice> focusManager,
        ConfirmBarView.Placement placement,
        Policy policy,
        KeyStroke stroke
    ) {
        FocusScope<ConfirmChoice, Button.Action> scope = placement.focusScope();
        if (stroke.key() == KeyStroke.Key.Q
            && stroke.modifiers().equals(Set.of(KeyStroke.Modifier.CTRL))) {
            return decide(focusManager, placement, policy.shortcuts().ctrlQDecision());
        }
        if (policy.shortcuts().saveShortcut() && stroke.key() == KeyStroke.Key.S
            && stroke.modifiers().equals(Set.of(KeyStroke.Modifier.CTRL))) {
            return decide(focusManager, placement, ConfirmChoice.SAVE);
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
            case ESCAPE -> decide(focusManager, placement, ConfirmChoice.CANCEL);
            default -> null;
        };
    }

    private static ConfirmChoice mnemonicDecision(String text, ConfirmationShortcuts shortcuts) {
        if (text == null || text.codePointCount(0, text.length()) != 1) {
            return null;
        }
        int codePoint = Character.toLowerCase(text.codePointAt(0));
        if (codePoint == 'y') {
            return ConfirmChoice.SAVE;
        }
        if (codePoint == 'n') {
            return shortcuts.noDecision();
        }
        return null;
    }

    private static Result decide(
        FocusManager<ConfirmChoice> focusManager,
        ConfirmBarView.Placement placement,
        ConfirmChoice decision
    ) {
        FocusScope<ConfirmChoice, Button.Action> scope = placement.focusScope();
        if (decision != ConfirmChoice.CANCEL
            && (!placement.decisionsAvailable() || !eligible(scope.target(decision).orElse(null)))) {
            return Result.CAPTURED;
        }
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
        return target != null && target.focusEligible();
    }
}
