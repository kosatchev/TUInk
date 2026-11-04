package dev.tuink.editor.session.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuink.editor.command.CommandDescriptor;
import dev.tuink.editor.command.CommandArgumentDescriptor;
import dev.tuink.editor.command.CommandArgumentType;
import dev.tuink.editor.command.CommandId;
import dev.tuink.editor.command.CommandInvocation;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {
    @Test
    void preflightUsesTheRegisteredSchemaWithoutExecutingOrRetainingItsHandler() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        CommandRegistry<String, Integer> registry = new CommandRegistry<>();
        var id = CommandId.of("example.action");
        registry.register(new CommandDescriptor(id, "Action"), (invocation, context) -> calls.incrementAndGet());
        assertTrue(registry.accepts(CommandInvocation.of(id)));
        assertFalse(registry.accepts(new CommandInvocation(id, java.util.Map.of("extra", "value"))));
        assertFalse(registry.accepts(CommandInvocation.of("example.unknown")));
        assertEquals(0, calls.get());
        assertInstanceOf(InvocationResult.Executed.class, registry.invoke(CommandInvocation.of(id), "context"));
        assertEquals(1, calls.get());
        registry.unregister(id);
        assertFalse(registry.accepts(CommandInvocation.of(id)));
    }

    @Test
    void registryOwnsDispatchWithoutOwningEditorOrFrontendState() {
        CommandRegistry<String, String> registry = new CommandRegistry<>();
        CommandId id = CommandId.of("tuink.tabs.activate");
        registry.register(new CommandDescriptor(
            id,
            "Activate tab",
            "",
            java.util.List.of(
                new CommandArgumentDescriptor("index", CommandArgumentType.INTEGER, true)
            )
        ), (invocation, prefix) ->
            prefix + invocation.argument("index").orElseThrow()
        );

        InvocationResult<String> result = registry.invoke(
            new CommandInvocation(id, java.util.Map.of("index", "2")),
            "tab-"
        );

        InvocationResult.Executed<?> executed = assertInstanceOf(
            InvocationResult.Executed.class,
            result
        );
        assertEquals("tab-2", executed.result());
    }

    @Test
    void invalidArgumentsAreRejectedBeforeTheHandlerRuns() {
        CommandRegistry<String, String> registry = new CommandRegistry<>();
        CommandId id = CommandId.of("tuink.tabs.activate");
        registry.register(new CommandDescriptor(
            id,
            "Activate tab",
            "",
            java.util.List.of(
                new CommandArgumentDescriptor("index", CommandArgumentType.INTEGER, true)
            )
        ), (invocation, context) -> {
            throw new AssertionError("handler must not run");
        });

        InvocationResult.Invalid<?> invalid = assertInstanceOf(
            InvocationResult.Invalid.class,
            registry.invoke(
                new CommandInvocation(id, java.util.Map.of("index", "not-an-integer")),
                "context"
            )
        );

        assertEquals(CommandArgumentError.Reason.INVALID_TYPE, invalid.errors().get(0).reason());
    }

    @Test
    void duplicateAndUnknownIdsHaveDeterministicOutcomes() {
        CommandRegistry<String, String> registry = new CommandRegistry<>();
        CommandDescriptor descriptor = new CommandDescriptor(
            CommandId.of("tuink.editor.save"),
            "Save"
        );
        registry.register(descriptor, (invocation, context) -> context);

        assertThrows(
            IllegalStateException.class,
            () -> registry.register(descriptor, (invocation, context) -> "replacement")
        );
        assertInstanceOf(
            InvocationResult.Unknown.class,
            registry.invoke(CommandInvocation.of("tuink.editor.missing"), "context")
        );
    }
}
