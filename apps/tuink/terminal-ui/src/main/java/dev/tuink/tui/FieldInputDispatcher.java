package dev.tuink.tui;

import dev.tuink.core.EditorClipboard;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.TextField;
import java.util.Objects;
import java.util.function.Function;

/** Shared field editing and admission; owners supply their action mapping, focus and query policy. */
final class FieldInputDispatcher {
    static final int MEMORY_BOUNDED = Integer.MAX_VALUE;
    sealed interface CommandResult { }
    record Applied(boolean valueChanged, boolean inputRejected) implements CommandResult { }
    record Forward(KeyStroke event) implements CommandResult { }
    enum Unhandled implements CommandResult { INSTANCE }

    private FieldInputDispatcher() { }

    static CommandResult command(TuinkCommand command, TextField field, EditorClipboard clipboard, int maxUtf16) {
        Objects.requireNonNull(command); Objects.requireNonNull(clipboard);
        if (command == TuinkCommand.EDIT_COPY || command == TuinkCommand.EDIT_CUT || command == TuinkCommand.EDIT_PASTE) {
            if (field == null) return new Applied(false, false);
            if (command == TuinkCommand.EDIT_PASTE && exceeds(field, clipboard.text(), maxUtf16))
                return new Applied(false, true);
            String before = field.text();
            var result = FieldClipboard.handle(command, field, clipboard);
            return new Applied(!before.equals(field.text()), result == FieldClipboard.Result.INPUT_REJECTED);
        }
        if (ControlCommandRouter.unsupportedLocally(command)) return new Applied(false, false);
        return ControlCommandRouter.eventFor(command).<CommandResult>map(Forward::new).orElse(Unhandled.INSTANCE);
    }

    static <A> InteractionResult<A> event(TextField field, TuiInputEvent event, int maxUtf16,
        Function<TuiInputEvent, InteractionResult<A>> dispatch, A rejectedAction) {
        Objects.requireNonNull(event); Objects.requireNonNull(dispatch); Objects.requireNonNull(rejectedAction);
        String insertion = event instanceof TextInput text ? text.text() : event instanceof PasteEvent paste ? paste.text() : null;
        if (event instanceof PasteEvent paste && paste.truncated()
            || field != null && insertion != null && exceeds(field, insertion, maxUtf16))
            return InteractionResult.output(rejectedAction);
        return dispatch.apply(event);
    }

    static <K> InteractionResult<TextField.Action> input(WindowInput input, TextField field, EditorClipboard clipboard,
        FocusScope<K, TextField.Action> scope, K targetId, FocusContext context,
        WindowPointerCapture capture, Object surfaceId, int maxUtf16) {
        var target = scope.target(targetId).orElseThrow();
        boolean eligible = context.focused() && context.enabled() && target.interactive();
        TuiInputEvent fieldEvent;
        if (input instanceof WindowInput.Command resolved && resolved.route() instanceof CommandRouter.RoutedAction action) {
            if (!ControlCommandRouter.owns(action.action())) return InteractionResult.ignored();
            if (!eligible) return InteractionResult.handled();
            var editing = command(action.action(), field, clipboard, maxUtf16);
            if (editing instanceof Applied applied) {
                if (applied.inputRejected()) return InteractionResult.output(TextField.Action.INPUT_REJECTED);
                return applied.valueChanged() ? InteractionResult.output(TextField.Action.VALUE_CHANGED) : InteractionResult.handled();
            }
            if (!(editing instanceof Forward forward)) return InteractionResult.ignored();
            fieldEvent = forward.event();
        } else if (input instanceof WindowInput.Text text) {
            if (!eligible) return InteractionResult.handled();
            fieldEvent = text.pasted() ? new PasteEvent(text.value(), text.truncated()) : new TextInput(text.value());
        } else if (input instanceof WindowInput.Gesture gesture && gesture.motion() instanceof WindowInput.Pointer pointer) {
            if (!eligible) return InteractionResult.handled();
            fieldEvent = pointer.event();
        } else return InteractionResult.ignored();
        var result = event(field, fieldEvent, maxUtf16,
            event -> target.placement().handle(event, context),
            TextField.Action.INPUT_REJECTED);
        if (capture != null && fieldEvent instanceof PointerEvent) capture.observe(surfaceId, scope, target.id(), result);
        return result;
    }

    private static boolean exceeds(TextField field, String insertion, int maxUtf16) {
        if (maxUtf16 < 0) throw new IllegalArgumentException("Negative field admission limit");
        return (long) field.length() - (field.selectionEnd() - field.selectionStart()) + insertion.length() > maxUtf16;
    }
}
