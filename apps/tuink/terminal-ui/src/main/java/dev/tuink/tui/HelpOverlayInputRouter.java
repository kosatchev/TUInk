package dev.tuink.tui;

import dev.tuink.terminal.geometry.Point;
import dev.tuink.terminal.input.PointerEvent;
import dev.tuink.terminal.input.TuiInputEvent;
import dev.tuink.terminal.widget.Button;
import dev.tuink.terminal.widget.FocusManager;
import dev.tuink.terminal.widget.FocusRoute;
import dev.tuink.terminal.widget.FocusScope;
import dev.tuink.terminal.widget.InteractionResult;
import dev.tuink.terminal.widget.PlacedModal;
import java.util.Objects;

/** TUInk adapter for the reusable single-control help overlay scope. */
final class HelpOverlayInputRouter {
    enum Scope {
        HELP
    }

    enum Target {
        CLOSE
    }

    record Result(boolean consumed, boolean closeRequested) {
        private static final Result IGNORED = new Result(false, false);
        private static final Result CONSUMED = new Result(true, false);
        private static final Result CLOSE = new Result(true, true);
    }

    private HelpOverlayInputRouter() {
    }

    static Result route(
        FocusManager<Target> focusManager,
        FocusScope<Target, Button.Action> scope,
        PlacedModal frame,
        TuiInputEvent event
    ) {
        Objects.requireNonNull(focusManager, "focusManager");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(event, "event");

        FocusRoute<Target, Button.Action> route = focusManager.route(scope, event);
        if (route.navigationConsumed()) {
            return Result.CONSUMED;
        }
        if (event instanceof PointerEvent pointer && closeCellHit(scope, frame, pointer)) {
            return Result.CLOSE;
        }
        if (route.target().map(target -> target.id()).orElse(null) != Target.CLOSE) {
            return Result.IGNORED;
        }

        InteractionResult<Button.Action> interaction = route.target().orElseThrow().placement()
            .handle(event, route.context().orElseThrow());
        focusManager.apply(scope, Target.CLOSE, interaction.focusRequest());
        if (interaction.output().orElse(null) == Button.Action.ACTIVATED) {
            return Result.CLOSE;
        }
        return interaction.consumed()
            ? Result.CONSUMED
            : Result.IGNORED;
    }

    private static boolean closeCellHit(
        FocusScope<Target, Button.Action> scope,
        PlacedModal frame,
        PointerEvent pointer
    ) {
        if (pointer.kind() != PointerEvent.Kind.PRESS || pointer.button() != PointerEvent.Button.LEFT) {
            return false;
        }
        var target = scope.target(Target.CLOSE).orElseThrow();
        Point local = target.space().localPoint(pointer).orElse(null);
        return local != null && frame.closeHit(local.y(), local.x());
    }
}
