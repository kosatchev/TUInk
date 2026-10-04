package dev.tuink.tui;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusRoute;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuink.tui.ui.SaveAsBarView;
import java.util.Objects;
import java.util.Set;

/** Raw-input adapter for the application-owned single-field Save As prompt. */
final class SaveAsInputRouter {
    record Result(boolean consumed, TextField.Action action) {
        private static final Result IGNORED = new Result(false, null);
        private static final Result CAPTURED = new Result(true, null);

        Result {
            if (!consumed && action != null) {
                throw new IllegalArgumentException("an ignored event cannot produce a field action");
            }
        }

        static Result action(TextField.Action action) {
            return new Result(true, Objects.requireNonNull(action, "action"));
        }
    }

    private SaveAsInputRouter() {
    }

    static Result route(
        FocusManager<SaveAsBarView.Target> focusManager,
        SaveAsBarView.Placement placement,
        TuiInputEvent event
    ) {
        Objects.requireNonNull(focusManager, "focusManager");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(event, "event");
        placement.activate(focusManager);

        FocusScope<SaveAsBarView.Target, TextField.Action> scope = placement.focusScope();
        RoutedTarget<SaveAsBarView.Target, TextField.Action> fieldTarget = scope
            .target(SaveAsBarView.Target.FIELD)
            .orElseThrow();
        boolean interactive = fieldTarget.enabled() && fieldTarget.focusable();

        // The application owns cancellation even when the field is hidden.
        if (event instanceof KeyStroke stroke && isApplicationOwnedCancel(stroke)) {
            return Result.IGNORED;
        }

        FocusRoute<SaveAsBarView.Target, TextField.Action> route = focusManager.route(scope, event);
        if (route.navigationConsumed()) {
            return Result.CAPTURED;
        }

        if (route.target().isPresent()) {
            RoutedTarget<SaveAsBarView.Target, TextField.Action> target = route.target().orElseThrow();
            InteractionResult<TextField.Action> interaction = target.placement()
                .handle(event, route.context().orElseThrow());
            focusManager.apply(scope, target.id(), interaction.focusRequest());
            if (interaction.output().isPresent()) {
                return Result.action(interaction.output().orElseThrow());
            }
            if (interaction.consumed()) {
                return Result.CAPTURED;
            }
        }

        if (event instanceof PasteEvent paste && paste.truncated()) {
            return Result.action(TextField.Action.PASTE_REJECTED);
        }
        if (!interactive) {
            return Result.CAPTURED;
        }
        if (event instanceof PointerEvent || isFieldOwned(event)) {
            // Pointer misses and caret-boundary no-ops stay inside the prompt.
            return Result.CAPTURED;
        }
        return Result.IGNORED;
    }

    private static boolean isApplicationOwnedCancel(KeyStroke stroke) {
        return stroke.key() == KeyStroke.Key.ESCAPE && stroke.modifiers().isEmpty()
            || stroke.key() == KeyStroke.Key.Q && stroke.modifiers().equals(Set.of(KeyStroke.Modifier.CTRL));
    }

    private static boolean isFieldOwned(TuiInputEvent event) {
        if (event instanceof TextInput || event instanceof PasteEvent) {
            return true;
        }
        if (!(event instanceof KeyStroke stroke) || !stroke.modifiers().isEmpty()) {
            return false;
        }
        return switch (stroke.key()) {
            case TAB, ENTER, BACKSPACE, DELETE, ARROW_LEFT, ARROW_RIGHT, HOME, END -> true;
            default -> false;
        };
    }
}
