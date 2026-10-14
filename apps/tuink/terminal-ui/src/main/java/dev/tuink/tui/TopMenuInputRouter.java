package dev.tuink.tui;

import dev.tuink.editor.command.CommandInvocation;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.MenuBar;
import java.util.Objects;

/** Raw input adapter from the generic menu widget to TUInk commands. */
final class TopMenuInputRouter {
    record Result(CommandInvocation command, boolean consumed) {
        Result {
            if (command != null && !consumed) {
                throw new IllegalArgumentException("an ignored event cannot invoke a command");
            }
        }
    }

    private TopMenuInputRouter() {
    }

    static Result route(
        TopMenuController controller,
        TopMenuView.Placement placement,
        CommandRouter commandRouter,
        TuiInputEvent event
    ) {
        return route(controller, placement, commandRouter, event, null, null);
    }

    static Result route(
        TopMenuController controller,
        TopMenuView.Placement placement,
        CommandRouter commandRouter,
        TuiInputEvent event,
        WindowPointerCapture capture,
        Object surfaceId
    ) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(commandRouter, "commandRouter");
        Objects.requireNonNull(event, "event");

        if (event instanceof KeyStroke stroke
            && commandRouter.invocationFor(stroke).filter(controller::openFor).isPresent()) {
            // A keyboard menu shortcut opens or switches the menu directly.
            // Do not adopt the placement from the frame that preceded it.
            return captured();
        }

        // Layout may normalize a stale dynamic-item selection or viewport.
        controller.adopt(placement.state());
        InteractionResult<MenuBar.Action<CommandInvocation>> interaction = placement.handle(event);
        if (capture != null) {
            capture.observe(surfaceId, placement.captureScope(), "menu", interaction);
        }
        if (interaction.output().isPresent()) {
            MenuBar.Action<CommandInvocation> action = interaction.output().orElseThrow();
            if (action instanceof MenuBar.StateChanged<CommandInvocation> changed) {
                controller.adopt(changed.state());
                return captured();
            }
            if (action instanceof MenuBar.Invoked<CommandInvocation> invoked) {
                controller.adopt(invoked.state());
                return invoked(invoked.value());
            }
            throw new IllegalStateException("Unsupported menu action: " + action);
        }
        if (interaction.consumed()) {
            return captured();
        }
        if (controller.isOpen()) {
            // A hidden/disabled open placement must remain a raw owner. This
            // prevents editor mutation while waiting for a usable resize.
            return captured();
        }
        if (event instanceof PointerEvent pointer && placement.contains(pointer)) {
            // Modified/non-left input over closed menu chrome belongs to this
            // placement and must not accidentally open a menu through another handler.
            return captured();
        }
        return ignored();
    }

    private static Result ignored() {
        return new Result(null, false);
    }

    private static Result captured() {
        return new Result(null, true);
    }

    private static Result invoked(CommandInvocation command) {
        return new Result(Objects.requireNonNull(command, "command"), true);
    }
}
