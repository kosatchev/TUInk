package dev.tuink.tui;

import dev.tuink.tui.command.TuinkCommand;
import java.util.function.Consumer;

/** One entry for application intents from either content adapter; I/O workflows retain ownership. */
final class ApplicationCommandHandler {
    enum Action { NONE, OPEN, OPEN_ENCODING, RECOVER, THEME, SAVE, SAVE_AS, QUIT }
    record Result(InputActionDispatcher.DispatchResult ui, Action action) {
        boolean handled() { return ui.handled() || action != Action.NONE; }
    }

    private ApplicationCommandHandler() {}

    static Result decide(TuinkCommand command, UiSessionState preferences, ActiveSurface surface,
        TopMenuController menu, Consumer<Boolean> mouseTracking, Runnable wrapEnabled) {
        if (command == null) return new Result(InputActionDispatcher.DispatchResult.notHandled(), Action.NONE);
        if (!surface.allows(command)) return new Result(InputActionDispatcher.DispatchResult.handled(surface.refusal(command)), Action.NONE);
        var ui = InputActionDispatcher.dispatchUi(command, preferences, surface, menu, mouseTracking, wrapEnabled);
        if (ui.handled()) return new Result(ui, Action.NONE);
        Action action = switch (command) {
            case DOCUMENT_OPEN -> Action.OPEN;
            case DOCUMENT_OPEN_ENCODING -> Action.OPEN_ENCODING;
            case DOCUMENT_RECOVER_DRAFTS -> Action.RECOVER;
            case VIEW_CYCLE_THEME -> Action.THEME;
            case DOCUMENT_SAVE -> Action.SAVE;
            case DOCUMENT_SAVE_AS -> Action.SAVE_AS;
            case APP_QUIT -> Action.QUIT;
            default -> Action.NONE;
        };
        return new Result(ui, action);
    }
}
