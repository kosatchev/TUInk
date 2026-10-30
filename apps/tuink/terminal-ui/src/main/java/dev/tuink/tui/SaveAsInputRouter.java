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
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.core.EditorClipboard;
import java.util.Objects;
import java.util.Set;

/** Raw-input adapter for the application-owned Save As field and action buttons. */
final class SaveAsInputRouter {
    record Result(boolean consumed, SaveAsBarView.Action action) {
        private static final Result IGNORED = new Result(false, null);
        private static final Result CAPTURED = new Result(true, null);

        Result {
            if (!consumed && action != null) {
                throw new IllegalArgumentException("an ignored event cannot produce a Save As action");
            }
        }

        static Result action(SaveAsBarView.Action action) {
            return new Result(true, Objects.requireNonNull(action, "action"));
        }
    }

    private SaveAsInputRouter() {
    }

    static Result routeCommand(FocusManager<SaveAsBarView.Target> focusManager, SaveAsBarView.Placement placement,
        TuinkCommand command, EditorClipboard clipboard) {
        Objects.requireNonNull(focusManager, "focusManager");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(command, "command");
        placement.activate(focusManager);
        ControlCommandRouter.ClipboardResult copied = ControlCommandRouter.clipboard(command,
            placement.field(focusManager.focused().orElse(null)).orElse(null), clipboard);
        if (copied.handled()) {
            if (copied.inputRejected()) return Result.action(SaveAsBarView.Action.INPUT_REJECTED);
            return copied.valueChanged() ? Result.action(SaveAsBarView.Action.VALUE_CHANGED) : Result.CAPTURED;
        }
        if (command == TuinkCommand.UI_ESCAPE || command == TuinkCommand.SEARCH_CLOSE) {
            return Result.action(SaveAsBarView.Action.CANCELLED);
        }
        KeyStroke event = ControlCommandRouter.eventFor(command).orElse(null);
        if (event != null) {
            Result routed = route(focusManager, placement, event);
            return routed.consumed() ? routed : Result.CAPTURED;
        }
        return ControlCommandRouter.unsupportedLocally(command) ? Result.CAPTURED : Result.IGNORED;
    }

    static Result route(
        FocusManager<SaveAsBarView.Target> focusManager,
        SaveAsBarView.Placement placement,
        TuiInputEvent event
    ) {
        return route(focusManager, placement, event, null, null);
    }

    static Result route(
        FocusManager<SaveAsBarView.Target> focusManager,
        SaveAsBarView.Placement placement,
        TuiInputEvent event,
        WindowPointerCapture capture,
        Object surfaceId
    ) {
        Objects.requireNonNull(focusManager, "focusManager");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(event, "event");
        placement.activate(focusManager);

        FocusScope<SaveAsBarView.Target, SaveAsBarView.Action> scope = placement.focusScope();
        boolean interactive = scope.tabOrder().stream().map(id -> scope.target(id).orElseThrow())
            .anyMatch(RoutedTarget::focusEligible);

        // The application owns cancellation even when the field is hidden.
        if (event instanceof KeyStroke stroke && isApplicationOwnedCancel(stroke)) {
            return Result.IGNORED;
        }

        SaveAsBarView.Target focused = focusManager.focused().orElse(null);
        if (event instanceof PasteEvent && focused != null && focused != SaveAsBarView.Target.FIELD) {
            return Result.CAPTURED;
        }
        FocusRoute<SaveAsBarView.Target, SaveAsBarView.Action> route = focusManager.route(scope, event);
        if (route.navigationConsumed()) {
            return Result.CAPTURED;
        }

        if (route.target().isPresent()) {
            RoutedTarget<SaveAsBarView.Target, SaveAsBarView.Action> target = route.target().orElseThrow();
            InteractionResult<SaveAsBarView.Action> interaction = target.placement()
                .handle(event, route.context().orElseThrow());
            focusManager.apply(scope, target.id(), interaction.focusRequest());
            if (capture != null) {
                capture.observe(surfaceId, scope, target.id(), interaction);
            }
            if (interaction.output().isPresent()) {
                SaveAsBarView.Action action = interaction.output().orElseThrow();
                return Result.action(action == SaveAsBarView.Action.SUBMITTED && !placement.submissionAvailable()
                    ? SaveAsBarView.Action.SUBMIT_REJECTED : action);
            }
            if (interaction.consumed()) {
                return Result.CAPTURED;
            }
        }

        if (event instanceof PasteEvent paste && paste.truncated()) {
            return Result.action(SaveAsBarView.Action.INPUT_REJECTED);
        }
        if (!placement.submissionAvailable() && event instanceof KeyStroke stroke
            && stroke.key() == KeyStroke.Key.ENTER && stroke.modifiers().isEmpty()) {
            return Result.action(SaveAsBarView.Action.SUBMIT_REJECTED);
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
        if (!(event instanceof KeyStroke stroke)) {
            return false;
        }
        if (TextField.ownsEditingKey(stroke)) {
            return true;
        }
        if (!stroke.modifiers().isEmpty()) {
            return false;
        }
        return switch (stroke.key()) {
            case TAB, ENTER, SPACE, BACKSPACE, DELETE, ARROW_LEFT, ARROW_RIGHT, HOME, END -> true;
            default -> false;
        };
    }
}
