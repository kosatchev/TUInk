package dev.tuink.editor.session.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuink.editor.command.CommandDescriptor;
import dev.tuink.editor.command.CommandArgumentDescriptor;
import dev.tuink.editor.command.CommandArgumentType;
import dev.tuink.editor.command.CommandId;
import dev.tuink.editor.command.CommandInvocation;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {
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
