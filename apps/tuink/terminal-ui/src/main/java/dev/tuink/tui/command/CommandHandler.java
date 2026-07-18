package dev.tuink.tui.command;

/**
 * Executes a command against the context owned by the application event loop.
 */
@FunctionalInterface
public interface CommandHandler<C, R> {
    R execute(CommandInvocation invocation, C context);
}
