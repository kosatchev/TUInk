package dev.tuink.tui.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry and single dispatch path for application commands.
 *
 * <p>The registry stores no editor or terminal state. The caller supplies an
 * execution context to {@link #invoke(CommandInvocation, Object)}, normally on
 * the application event-loop thread.</p>
 */
public final class CommandRegistry<C, R> {
    private final Map<CommandId, RegisteredCommand<C, R>> commands = new LinkedHashMap<>();

    /**
     * Registers one command without replacing an existing command with the same ID.
     *
     * @throws IllegalStateException when the ID is already registered
     */
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

    /** Returns an immutable descriptor snapshot in registration order. */
    public synchronized List<CommandDescriptor> descriptors() {
        List<CommandDescriptor> result = new ArrayList<>(commands.size());
        commands.values().forEach(command -> result.add(command.descriptor()));
        return List.copyOf(result);
    }

    public synchronized int size() {
        return commands.size();
    }

    /**
     * Resolves and invokes a command. Unknown IDs are reported as data rather
     * than silently ignored or converted to handler failures.
     */
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

        R result = registered.handler().execute(invocation, context);
        return new InvocationResult.Executed<>(invocation, result);
    }

    private record RegisteredCommand<C, R>(
        CommandDescriptor descriptor,
        CommandHandler<C, R> handler
    ) {
    }
}
