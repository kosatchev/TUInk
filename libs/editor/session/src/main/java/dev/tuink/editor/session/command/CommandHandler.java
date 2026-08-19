package dev.tuink.editor.session.command;

import dev.tuink.editor.command.CommandInvocation;

/** Executes a command against state owned by the session thread. */
@FunctionalInterface
public interface CommandHandler<C, R> {
    R execute(CommandInvocation invocation, C context);
}
