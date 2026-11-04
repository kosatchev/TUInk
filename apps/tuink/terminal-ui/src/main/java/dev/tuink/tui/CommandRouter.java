package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.SEARCH_CLOSE;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_FIELD_BACKSPACE;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_FIELD_DELETE;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_FIELD_END;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_FIELD_HOME;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_FOCUS_NEXT;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_NAVIGATE_DOWN;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_NAVIGATE_LEFT;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_NAVIGATE_RIGHT;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_NAVIGATE_UP;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_NEXT;
import static dev.tuink.tui.command.TuinkCommand.TAB_ACTIVATE;

import dev.tuink.editor.command.CommandArgumentDescriptor;
import dev.tuink.editor.command.CommandArgumentType;
import dev.tuink.editor.command.CommandDescriptor;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.editor.session.command.CommandRegistry;
import dev.tuink.editor.session.command.InvocationResult;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.keymap.Keymap;
import dev.tuink.tui.keymap.TuinkKeymap;
import dev.tuiframe.input.KeyStroke;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Resolves physical bindings and dispatches semantic commands through one registry. */
final class CommandRouter {
    private final Keymap<KeyStroke> keymap;
    private final CommandRegistry<ExecutionContext, RoutedCommand> registry = new CommandRegistry<>();

    CommandRouter() {
        this(TuinkKeymap.defaults());
    }

    CommandRouter(Keymap<KeyStroke> keymap) {
        this.keymap = Objects.requireNonNull(keymap, "keymap");
        registerBuiltins();
    }

    Optional<CommandInvocation> invocationFor(KeyStroke stroke) {
        return keymap.resolve(stroke);
    }

    /** Menu hints come from the current bindings, including runtime rebindings. */
    String shortcutFor(CommandInvocation invocation) {
        for (var binding : keymap.bindings().entrySet()) {
            if (binding.getValue().equals(invocation)) {
                return shortcutLabel(binding.getKey());
            }
        }
        return "";
    }

    private static String shortcutLabel(KeyStroke stroke) {
        StringBuilder label = new StringBuilder();
        for (KeyStroke.Modifier modifier : List.of(KeyStroke.Modifier.CTRL,
                KeyStroke.Modifier.ALT, KeyStroke.Modifier.SHIFT, KeyStroke.Modifier.SUPER)) {
            if (stroke.has(modifier)) {
                label.append(switch (modifier) {
                    case CTRL -> "Ctrl+";
                    case ALT -> "Alt+";
                    case SHIFT -> "Shift+";
                    case SUPER -> "Super+";
                });
            }
        }
        label.append(switch (stroke.key()) {
            case TAB -> "Tab";
            case ENTER -> "Enter";
            case ESCAPE -> "Esc";
            case PAGE_UP -> "PgUp";
            case PAGE_DOWN -> "PgDn";
            case ARROW_LEFT -> "Left";
            case ARROW_RIGHT -> "Right";
            case ARROW_UP -> "Up";
            case ARROW_DOWN -> "Down";
            case HOME -> "Home";
            case END -> "End";
            case BACKSPACE -> "Backspace";
            case DELETE -> "Delete";
            case INSERT -> "Insert";
            case SPACE -> "Space";
            case MINUS -> "-";
            case EQUALS -> "=";
            case LEFT_BRACKET -> "[";
            case RIGHT_BRACKET -> "]";
            case BACKSLASH -> "\\";
            case SEMICOLON -> ";";
            case APOSTROPHE -> "'";
            case GRAVE -> "`";
            case COMMA -> ",";
            case PERIOD -> ".";
            case SLASH -> "/";
            case DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4,
                DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9 -> stroke.key().name().substring(6);
            default -> stroke.key().name();
        });
        return label.toString();
    }

    CommandInvocation tabActivationInvocation(int tabIndex) {
        if (tabIndex < 0) {
            throw new IllegalArgumentException("tabIndex must be non-negative");
        }
        return new CommandInvocation(TAB_ACTIVATE.id(), Map.of("index", Integer.toString(tabIndex)));
    }

    boolean accepts(CommandInvocation invocation) {
        return registry.accepts(invocation);
    }

    Optional<TuinkCommand> invoke(CommandInvocation invocation) {
        return dispatch(invocation)
            .filter(RoutedAction.class::isInstance)
            .map(RoutedAction.class::cast)
            .map(RoutedAction::action);
    }

    OptionalInt invokeTabActivation(CommandInvocation invocation) {
        Optional<RoutedCommand> routed = dispatch(invocation);
        if (routed.isPresent() && routed.get() instanceof ActivateTab activateTab) {
            return OptionalInt.of(activateTab.tabIndex());
        }
        return OptionalInt.empty();
    }

    /** Validates the invocation and executes its registered route exactly once. */
    Optional<RoutedCommand> dispatch(CommandInvocation invocation) {
        InvocationResult<RoutedCommand> result = registry.invoke(invocation, ExecutionContext.INSTANCE);
        if (result instanceof InvocationResult.Executed<?> executed) {
            return Optional.of((RoutedCommand) executed.result());
        }
        return Optional.empty();
    }

    private void registerBuiltins() {
        for (TuinkCommand command : TuinkCommand.values()) {
            if (command == TAB_ACTIVATE) {
                registry.register(
                    new CommandDescriptor(
                        command.id(),
                        command.title(),
                        "",
                        List.of(new CommandArgumentDescriptor(
                            "index",
                            CommandArgumentType.INTEGER,
                            true,
                            0,
                            null
                        ))
                    ),
                    (request, context) -> new ActivateTab(requiredTabIndex(request))
                );
            } else if (command == TuinkCommand.TAB_CLOSE) {
                registry.register(new CommandDescriptor(command.id(), command.title(), "",
                    List.of(new CommandArgumentDescriptor("index", CommandArgumentType.INTEGER, false, 0, null))),
                    (request, context) -> new RoutedAction(command));
            } else {
                registry.register(
                    new CommandDescriptor(command.id(), command.title()),
                    (request, context) -> new RoutedAction(command)
                );
            }
        }
    }

    private static int requiredTabIndex(CommandInvocation invocation) {
        return Integer.parseInt(invocation.argument("index").orElseThrow());
    }

    sealed interface RoutedCommand permits RoutedAction, ActivateTab {
    }

    record RoutedAction(TuinkCommand action) implements RoutedCommand {
    }

    record ActivateTab(int tabIndex) implements RoutedCommand {
    }

    private enum ExecutionContext {
        INSTANCE
    }
}
