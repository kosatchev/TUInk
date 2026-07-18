package dev.tuink.tui.command;

import java.util.Objects;

/**
 * Typed outcome of resolving an invocation in a command registry.
 */
public sealed interface InvocationResult<R>
    permits InvocationResult.Executed, InvocationResult.Unknown {

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
}
