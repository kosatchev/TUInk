package dev.tuink.editor.session.command;

import dev.tuink.editor.command.CommandInvocation;
import java.util.Objects;

/** Typed outcome of resolving an invocation in a session command registry. */
public sealed interface InvocationResult<R>
    permits InvocationResult.Executed, InvocationResult.Invalid, InvocationResult.Unknown {

    CommandInvocation invocation();

    record Executed<R>(CommandInvocation invocation, R result) implements InvocationResult<R> {
        public Executed {
            Objects.requireNonNull(invocation, "invocation");
        }
    }

    record Unknown<R>(CommandInvocation invocation) implements InvocationResult<R> {
        public Unknown {
            Objects.requireNonNull(invocation, "invocation");
        }
    }

    record Invalid<R>(
        CommandInvocation invocation,
        java.util.List<CommandArgumentError> errors
    ) implements InvocationResult<R> {
        public Invalid {
            Objects.requireNonNull(invocation, "invocation");
            errors = java.util.List.copyOf(errors);
            if (errors.isEmpty()) {
                throw new IllegalArgumentException("Invalid invocation must contain an error");
            }
        }
    }
}
