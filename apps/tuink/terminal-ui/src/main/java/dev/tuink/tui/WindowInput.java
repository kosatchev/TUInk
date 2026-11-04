package dev.tuink.tui;

import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import java.util.Objects;
import java.util.Set;

/** One terminal input decision. A semantic command never retains executable physical input. */
sealed interface WindowInput {
    enum Consumed implements WindowInput { INSTANCE }

    record Command(CommandInvocation invocation, CommandRouter.RoutedCommand route,
                   UiRecipient recipient) implements WindowInput {
        public Command {
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(recipient, "recipient");
        }
    }

    record Text(String value, boolean pasted, boolean truncated, UiRecipient recipient) implements WindowInput {
        public Text {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(recipient, "recipient");
            if (!pasted) {
                if (truncated) throw new IllegalArgumentException("Only paste can be truncated");
                new TextInput(value);
            }
        }
    }

    record Gesture(Motion motion, UiRecipient recipient) implements WindowInput {
        public Gesture {
            Objects.requireNonNull(motion, "motion");
            Objects.requireNonNull(recipient, "recipient");
        }
    }

    sealed interface Motion permits Pointer, Pan { }
    record Pointer(PointerEvent event) implements Motion {
        public Pointer { Objects.requireNonNull(event, "event"); }
    }
    record Pan(int rows, int columns) implements Motion {
        public Pan {
            if (rows == 0 && columns == 0) throw new IllegalArgumentException("Pan must move the viewport");
        }
    }

    static WindowInput command(CommandRouter router, TuinkCommand action, UiRecipient recipient) {
        var invocation = CommandInvocation.of(Objects.requireNonNull(action, "action").id());
        return router.dispatch(invocation).<WindowInput>map(route -> new Command(invocation, route, recipient))
            .orElse(Consumed.INSTANCE);
    }

    static WindowInput physical(TuiInputEvent event, UiRecipient recipient) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(recipient, "recipient");
        return switch (event) {
            case TextInput text -> new Text(text.text(), false, false, recipient);
            case PasteEvent paste -> new Text(paste.text(), true, paste.truncated(), recipient);
            case PointerEvent pointer -> new Gesture(new Pointer(pointer), recipient);
            case KeyStroke key when key.modifiers().equals(Set.of(KeyStroke.Modifier.ALT)) -> switch (key.key()) {
                case ARROW_UP -> new Gesture(new Pan(-3, 0), recipient);
                case ARROW_DOWN -> new Gesture(new Pan(3, 0), recipient);
                case ARROW_LEFT -> new Gesture(new Pan(0, -3), recipient);
                case ARROW_RIGHT -> new Gesture(new Pan(0, 3), recipient);
                default -> Consumed.INSTANCE;
            };
            case KeyStroke key when key.key() == KeyStroke.Key.SPACE && key.modifiers().isEmpty() ->
                new Text(" ", false, false, recipient);
            default -> Consumed.INSTANCE;
        };
    }
}
