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

    CommandInvocation tabActivationInvocation(int tabIndex) {
        if (tabIndex < 0) {
            throw new IllegalArgumentException("tabIndex must be non-negative");
        }
        return new CommandInvocation(TAB_ACTIVATE.id(), Map.of("index", Integer.toString(tabIndex)));
    }

    CommandInvocation invocationForSearch(
        TuinkCommand action,
        SearchFocus focus,
        CommandInvocation globalInvocation
    ) {
        if (action == null) {
            return globalInvocation;
        }
        TuinkCommand focused = switch (action) {
            case UI_ESCAPE -> SEARCH_CLOSE;
            case EDIT_INSERT_TAB -> SEARCH_FOCUS_NEXT;
            case EDIT_BACKSPACE -> SEARCH_FIELD_BACKSPACE;
            case EDIT_DELETE_FORWARD -> SEARCH_FIELD_DELETE;
            case CURSOR_LEFT -> SEARCH_NAVIGATE_LEFT;
            case CURSOR_RIGHT -> SEARCH_NAVIGATE_RIGHT;
            case CURSOR_UP -> SEARCH_NAVIGATE_UP;
            case CURSOR_DOWN -> SEARCH_NAVIGATE_DOWN;
            case CURSOR_HOME -> SearchController.isSearchFieldFocus(focus) ? SEARCH_FIELD_HOME : null;
            case CURSOR_END -> SearchController.isSearchFieldFocus(focus) ? SEARCH_FIELD_END : null;
            case EDIT_INSERT_NEWLINE -> SearchController.actionForSearchFocus(focus);
            case SEARCH_OPEN -> SEARCH_NEXT;
            default -> null;
        };
        if (focused != null) {
            return CommandInvocation.of(focused.id());
        }
        return globalInvocation == null ? CommandInvocation.of(action.id()) : globalInvocation;
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
