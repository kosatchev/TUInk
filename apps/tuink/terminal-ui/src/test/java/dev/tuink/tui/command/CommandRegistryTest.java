package dev.tuink.tui.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {
    @Test
    void invokesRegisteredHandlerWithTheSuppliedInvocationAndContext() {
        CommandRegistry<ExecutionContext, String> registry = new CommandRegistry<>();
        CommandDescriptor descriptor = descriptor("tuink.tabs.activate", "Activate tab");
        CommandInvocation invocation = new CommandInvocation(descriptor.id(), java.util.Map.of("tab", "2"));
        ExecutionContext context = new ExecutionContext("tab-");
        registry.register(descriptor, (request, executionContext) -> {
            executionContext.seen.add(request);
            return executionContext.prefix + request.argument("tab").orElseThrow();
        });

        InvocationResult<String> result = registry.invoke(invocation, context);
        InvocationResult.Executed<?> executed = assertInstanceOf(InvocationResult.Executed.class, result);

        assertSame(invocation, executed.invocation());
        assertEquals("tab-2", executed.result());
        assertEquals(List.of(invocation), context.seen);
        assertEquals(descriptor, registry.descriptor(descriptor.id()).orElseThrow());
    }

    @Test
    void duplicateRegistrationFailsWithoutReplacingTheOriginalHandler() {
        CommandRegistry<String, String> registry = new CommandRegistry<>();
        CommandDescriptor original = descriptor("tuink.editor.save", "Save");
        registry.register(original, (invocation, context) -> "original:" + context);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> registry.register(
                descriptor("tuink.editor.save", "Replacement"),
                (invocation, context) -> "replacement"
            )
        );
        InvocationResult.Executed<?> executed = assertInstanceOf(
            InvocationResult.Executed.class,
            registry.invoke(CommandInvocation.of(original.id()), "context")
        );

        assertTrue(error.getMessage().contains("tuink.editor.save"));
        assertEquals("original:context", executed.result());
        assertEquals(original, registry.descriptor(original.id()).orElseThrow());
    }

    @Test
    void unknownCommandsReturnTypedResultInsteadOfFallingThrough() {
        CommandRegistry<String, String> registry = new CommandRegistry<>();
        CommandInvocation invocation = CommandInvocation.of("tuink.missing.command");

        InvocationResult<String> result = registry.invoke(invocation, "context");
        InvocationResult.Unknown<?> unknown = assertInstanceOf(InvocationResult.Unknown.class, result);

        assertSame(invocation, unknown.invocation());
        assertEquals(0, registry.size());
    }

    @Test
    void descriptorSnapshotsKeepRegistrationOrderAndCannotBeMutated() {
        CommandRegistry<String, String> registry = new CommandRegistry<>();
        CommandDescriptor save = descriptor("tuink.editor.save", "Save");
        CommandDescriptor open = descriptor("tuink.editor.open", "Open");
        registry.register(save, (invocation, context) -> context);
        registry.register(open, (invocation, context) -> context);

        List<CommandDescriptor> descriptors = registry.descriptors();

        assertEquals(List.of(save, open), descriptors);
        assertThrows(UnsupportedOperationException.class, () -> descriptors.add(save));
    }

    @Test
    void unregisterMakesACommandUnknownAndReportsWhetherItExisted() {
        CommandRegistry<String, String> registry = new CommandRegistry<>();
        CommandDescriptor save = descriptor("tuink.editor.save", "Save");
        registry.register(save, (invocation, context) -> context);

        assertTrue(registry.contains(save.id()));
        assertTrue(registry.unregister(save.id()));
        assertFalse(registry.unregister(save.id()));
        assertFalse(registry.contains(save.id()));
        assertInstanceOf(
            InvocationResult.Unknown.class,
            registry.invoke(CommandInvocation.of(save.id()), "context")
        );
    }

    @Test
    void nullInvocationOrContextIsRejectedBeforeDispatch() {
        CommandRegistry<String, String> registry = new CommandRegistry<>();
        CommandInvocation invocation = CommandInvocation.of("tuink.editor.save");
        registry.register(descriptor("tuink.editor.save", "Save"), (request, context) -> context);

        assertThrows(NullPointerException.class, () -> registry.invoke(null, "context"));
        assertThrows(NullPointerException.class, () -> registry.invoke(invocation, null));
    }

    private static CommandDescriptor descriptor(String id, String title) {
        return new CommandDescriptor(CommandId.of(id), title);
    }

    private static final class ExecutionContext {
        private final String prefix;
        private final List<CommandInvocation> seen = new ArrayList<>();

        private ExecutionContext(String prefix) {
            this.prefix = prefix;
        }
    }
}
