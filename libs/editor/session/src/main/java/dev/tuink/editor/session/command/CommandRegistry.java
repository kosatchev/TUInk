package dev.tuink.editor.session.command;

import dev.tuink.editor.command.CommandDescriptor;
import dev.tuink.editor.command.CommandArgumentDescriptor;
import dev.tuink.editor.command.CommandId;
import dev.tuink.editor.command.CommandInvocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry and single dispatch path for session commands.
 *
 * <p>The registry stores no editor or frontend state. The owner supplies an
 * execution context and invokes handlers on its mutation thread.</p>
 */
public final class CommandRegistry<C, R> {
    private final Map<CommandId, RegisteredCommand<C, R>> commands = new LinkedHashMap<>();

    public synchronized void register(CommandDescriptor descriptor, CommandHandler<C, R> handler) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(handler, "handler");
        if (commands.containsKey(descriptor.id())) {
            throw new IllegalStateException("Command is already registered: " + descriptor.id());
        }
        commands.put(descriptor.id(), new RegisteredCommand<>(descriptor, handler));
    }

    public synchronized boolean unregister(CommandId id) {
        Objects.requireNonNull(id, "id");
        return commands.remove(id) != null;
    }

    public synchronized boolean contains(CommandId id) {
        Objects.requireNonNull(id, "id");
        return commands.containsKey(id);
    }

    public synchronized Optional<CommandDescriptor> descriptor(CommandId id) {
        Objects.requireNonNull(id, "id");
        RegisteredCommand<C, R> registered = commands.get(id);
        return registered == null ? Optional.empty() : Optional.of(registered.descriptor());
    }

    public synchronized List<CommandDescriptor> descriptors() {
        List<CommandDescriptor> result = new ArrayList<>(commands.size());
        commands.values().forEach(command -> result.add(command.descriptor()));
        return List.copyOf(result);
    }

    public synchronized int size() {
        return commands.size();
    }

    /** Checks current registration and argument schema without executing a handler; invoke revalidates before execution. */
    public boolean accepts(CommandInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        RegisteredCommand<C, R> registered;
        synchronized (this) { registered = commands.get(invocation.id()); }
        return registered != null && validateArguments(registered.descriptor(), invocation).isEmpty();
    }

    public InvocationResult<R> invoke(CommandInvocation invocation, C context) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");

        RegisteredCommand<C, R> registered;
        synchronized (this) {
            registered = commands.get(invocation.id());
        }
        if (registered == null) {
            return new InvocationResult.Unknown<>(invocation);
        }
        List<CommandArgumentError> argumentErrors = validateArguments(
            registered.descriptor(),
            invocation
        );
        if (!argumentErrors.isEmpty()) {
            return new InvocationResult.Invalid<>(invocation, argumentErrors);
        }
        return new InvocationResult.Executed<>(
            invocation,
            registered.handler().execute(invocation, context)
        );
    }

    private static List<CommandArgumentError> validateArguments(
        CommandDescriptor descriptor,
        CommandInvocation invocation
    ) {
        Map<String, CommandArgumentDescriptor> schema = new LinkedHashMap<>();
        descriptor.arguments().forEach(argument -> schema.put(argument.name(), argument));
        List<CommandArgumentError> errors = new ArrayList<>();
        invocation.arguments().forEach((name, value) -> {
            CommandArgumentDescriptor argument = schema.get(name);
            if (argument == null) {
                errors.add(new CommandArgumentError(name, CommandArgumentError.Reason.UNKNOWN));
            } else if (!argument.type().accepts(value)) {
                errors.add(new CommandArgumentError(name, CommandArgumentError.Reason.INVALID_TYPE));
            } else if (!argument.acceptsValue(value)) {
                errors.add(new CommandArgumentError(name, CommandArgumentError.Reason.OUT_OF_RANGE));
            }
        });
        descriptor.arguments().stream()
            .filter(CommandArgumentDescriptor::required)
            .filter(argument -> !invocation.arguments().containsKey(argument.name()))
            .forEach(argument -> errors.add(new CommandArgumentError(
                argument.name(),
                CommandArgumentError.Reason.MISSING_REQUIRED
            )));
        return List.copyOf(errors);
    }

    private record RegisteredCommand<C, R>(
        CommandDescriptor descriptor,
        CommandHandler<C, R> handler
    ) {
    }
}
