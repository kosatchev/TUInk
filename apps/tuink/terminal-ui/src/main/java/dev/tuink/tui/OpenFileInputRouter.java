package dev.tuink.tui;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.PointerCaptureRequest;
import dev.tuink.core.EditorClipboard;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.ui.FileTreeView;
import java.nio.file.Path;
import java.util.Objects;

/** Raw-event owner policy for the open-file tree, location field, and controls. */
final class OpenFileInputRouter {
    enum Focus {
        LIST,
        LOCATION,
        HIDDEN,
        OK,
        CANCEL
    }

    sealed interface Intent
        permits ToggleDirectory, ChangeRoot, OpenFile, MoveToParentRoot,
        CloseDialog, ToggleHidden, SelectionRejected, OpenLocation {
    }

    record OpenLocation(String value) implements Intent {
        OpenLocation { Objects.requireNonNull(value, "value"); }
    }

    record ToggleDirectory(FileTreeModel.EntryId id, boolean expand) implements Intent {
        ToggleDirectory {
            Objects.requireNonNull(id, "id");
        }
    }

    record ChangeRoot(Path root) implements Intent {
        ChangeRoot {
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        }
    }

    record OpenFile(Path file) implements Intent {
        OpenFile {
            file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        }
    }

    record MoveToParentRoot() implements Intent {
    }

    record CloseDialog() implements Intent {
    }

    record ToggleHidden() implements Intent {
    }

    record SelectionRejected(Rejection reason) implements Intent {
        SelectionRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum Rejection {
        NO_SELECTION,
        DIRECTORY,
        ERROR,
        INPUT_TOO_LARGE
    }

    record Result(
        FileTreeView.State state,
        Focus focus,
        Intent intent,
        boolean consumed,
        PointerCaptureRequest pointerCaptureRequest
    ) {
        Result {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(focus, "focus");
            Objects.requireNonNull(pointerCaptureRequest, "pointerCaptureRequest");
            if (intent != null && !consumed) {
                throw new IllegalArgumentException("an ignored event cannot request an intent");
            }
        }

        Result(FileTreeView.State state, Focus focus, Intent intent, boolean consumed) {
            this(state, focus, intent, consumed, PointerCaptureRequest.NONE);
        }

        private Result withPointerCapture(PointerCaptureRequest request) {
            return new Result(state, focus, intent, consumed, request);
        }
    }

    private OpenFileInputRouter() {
    }

    static Result routeCommand(OpenFileDialogView.Placement dialog, OpenFileDialogView.FocusState focusState,
        TuinkCommand command, EditorClipboard clipboard) {
        dialog.activate(focusState);
        var field = focusState.focus() == Focus.LOCATION ? dialog.location() : null;
        var copied = ControlCommandRouter.clipboard(command, field, clipboard);
        if (copied.handled()) {
            return copied.inputRejected() ? intent(dialog.tree().state(), focusState.focus(),
                new SelectionRejected(Rejection.INPUT_TOO_LARGE)) : captured(dialog.tree().state(), focusState.focus());
        }
        if (focusState.focus() == Focus.LOCATION && ControlCommandRouter.owns(command)) {
            var event = ControlCommandRouter.eventFor(command).orElse(null);
            return event == null ? captured(dialog.tree().state(), focusState.focus())
                : route(dialog, focusState, event, 0, 0);
        }
        return ignored(dialog.tree().state(), focusState.focus());
    }

    static Result route(
        OpenFileDialogView.Placement dialog,
        OpenFileDialogView.FocusState focusState,
        TuiInputEvent event,
        long nowNanos,
        long doubleClickWindowNanos
    ) {
        return route(dialog, focusState, event, nowNanos, doubleClickWindowNanos, null, null);
    }

    static Result route(
        OpenFileDialogView.Placement dialog,
        OpenFileDialogView.FocusState focusState,
        TuiInputEvent event,
        long nowNanos,
        long doubleClickWindowNanos,
        WindowPointerCapture capture,
        Object surfaceId
    ) {
        Result result = routeInput(dialog, focusState, event, nowNanos, doubleClickWindowNanos, capture, surfaceId);
        if (capture != null) {
            capture.observe(surfaceId, dialog.captureScope(), OpenFileDialogView.Control.LIST,
                InteractionResult.<FileTreeView.Action>handled().withPointerCapture(result.pointerCaptureRequest()));
        }
        return result;
    }

    private static Result routeInput(
        OpenFileDialogView.Placement dialog,
        OpenFileDialogView.FocusState focusState,
        TuiInputEvent event,
        long nowNanos,
        long doubleClickWindowNanos,
        WindowPointerCapture capture,
        Object surfaceId
    ) {
        Objects.requireNonNull(dialog, "dialog");
        Objects.requireNonNull(focusState, "focusState");
        dialog.activate(focusState);
        FileTreeView.Placement placement = dialog.tree();
        Focus requiredFocus = focusState.focus();
        Objects.requireNonNull(event, "event");
        if (doubleClickWindowNanos < 0L) {
            throw new IllegalArgumentException("doubleClickWindowNanos must not be negative");
        }

        FileTreeView.State current = placement.state();
        if (event instanceof ResizeEvent) {
            return ignored(current, requiredFocus);
        }
        if (event instanceof KeyStroke stroke
            && stroke.modifiers().isEmpty() && stroke.key() == KeyStroke.Key.ESCAPE) {
            return intent(current.resetClickTracking(), requiredFocus, new CloseDialog());
        }
        if (event instanceof PointerEvent pointer && !pointer.modifiers().isEmpty()
            && !dialog.focusScope().target(OpenFileDialogView.Control.LOCATION).orElseThrow()
                .placement().bounds().contains(pointer.column(), pointer.row())) {
            return captured(current.resetClickTracking(), requiredFocus);
        }
        if (dialog.compactDialog() != null) {
            var compact = dialog.compactDialog();
            if (event instanceof PointerEvent pointer && pointer.modifiers().isEmpty()
                && pointer.kind() == PointerEvent.Kind.PRESS && pointer.button() == PointerEvent.Button.LEFT
                && compact.frame().closeHit(pointer.row(), pointer.column())) {
                return intent(current.resetClickTracking(), requiredFocus, new CloseDialog());
            }
            var route = focusState.manager().route(dialog.focusScope(), event);
            if (!route.navigationConsumed() && route.target().isPresent()) {
                var interaction = route.target().orElseThrow().placement().handle(event, route.context().orElseThrow());
                if (interaction.output().orElse(null) == OpenFileDialogView.Action.ACTIVATE) {
                    return intent(current.resetClickTracking(), requiredFocus, new CloseDialog());
                }
            }
            return captured(current.resetClickTracking(), requiredFocus);
        }
        if (event instanceof KeyStroke stroke) {
            if (stroke.key() == KeyStroke.Key.L && stroke.modifiers().equals(java.util.Set.of(KeyStroke.Modifier.CTRL))) {
                dialog.location().selectAll();
                focusState.focus(Focus.LOCATION);
                return captured(current.resetClickTracking(), focusState.focus());
            }
            if (stroke.key() == KeyStroke.Key.H
                && stroke.modifiers().equals(java.util.Set.of(KeyStroke.Modifier.CTRL))
                || stroke.key() == KeyStroke.Key.PERIOD
                    && stroke.modifiers().equals(java.util.Set.of(KeyStroke.Modifier.ALT))) {
                if (!placement.enabled()) return captured(current.resetClickTracking(), requiredFocus);
                focusState.focus(Focus.LIST);
                return intent(current.resetClickTracking(), focusState.focus(), new ToggleHidden());
            }
            if (stroke.modifiers().isEmpty()
                && (requiredFocus == Focus.OK && stroke.key() == KeyStroke.Key.ARROW_LEFT
                    || requiredFocus == Focus.CANCEL && stroke.key() == KeyStroke.Key.ARROW_LEFT)) {
                focusState.manager().previous(dialog.focusScope());
                return captured(current.resetClickTracking(), focusState.focus());
            }
            if (stroke.modifiers().isEmpty() && requiredFocus == Focus.OK
                && stroke.key() == KeyStroke.Key.ARROW_RIGHT) {
                focusState.manager().next(dialog.focusScope());
                return captured(current.resetClickTracking(), focusState.focus());
            }
        }
        var routed = focusState.manager().route(dialog.focusScope(), event);
        Focus focused = focusState.focus();
        if (routed.navigationConsumed()) {
            return captured(current.resetClickTracking(), focused);
        }
        if (routed.target().isPresent()) {
            var target = routed.target().orElseThrow();
            if (target.id() != OpenFileDialogView.Control.LIST) {
                InteractionResult<OpenFileDialogView.Action> interaction = target.placement().handle(
                    event, routed.context().orElseThrow());
                focusState.manager().apply(dialog.focusScope(), target.id(), interaction.focusRequest());
                if (capture != null) capture.observe(surfaceId, dialog.focusScope(), target.id(), interaction);
                Focus buttonFocus = focusState.focus();
                if (interaction.output().isPresent()) {
                    if (target.id() == OpenFileDialogView.Control.LOCATION) {
                        return switch (interaction.output().orElseThrow()) {
                            case LOCATION_SUBMITTED -> intent(current.resetClickTracking(), buttonFocus,
                                new OpenLocation(dialog.location().text()));
                            case INPUT_REJECTED -> intent(current.resetClickTracking(), buttonFocus,
                                new SelectionRejected(Rejection.INPUT_TOO_LARGE));
                            default -> captured(current.resetClickTracking(), buttonFocus);
                        };
                    }
                    return switch (target.id()) {
                        case OK -> focusState.locationEdited()
                            ? intent(current.resetClickTracking(), buttonFocus, new OpenLocation(dialog.location().text()))
                            : confirmSelection(placement, buttonFocus);
                        case CANCEL, CLOSE -> intent(current.resetClickTracking(), buttonFocus, new CloseDialog());
                        case HIDDEN -> {
                            yield intent(current.resetClickTracking(), focusState.focus(), new ToggleHidden());
                        }
                        case LIST, LOCATION -> throw new IllegalStateException("not a button");
                    };
                }
                return captured(current.resetClickTracking(), buttonFocus);
            }
        }
        if (!placement.enabled()) return captured(current.resetClickTracking(), focused);
        if (event instanceof KeyStroke stroke && focused == Focus.LIST) {
            return routeTreeKey(placement, stroke, nowNanos, doubleClickWindowNanos);
        }
        if (event instanceof PointerEvent pointer) {
            Result result = routePointer(placement, focused, pointer, nowNanos, doubleClickWindowNanos);
            focusState.focus(result.focus());
            return result;
        }
        return captured(current, focused);
    }

    private static Result routeTreeKey(
        FileTreeView.Placement placement,
        KeyStroke stroke,
        long nowNanos,
        long doubleClickWindowNanos
    ) {
        FileTreeView.State current = placement.state();
        if (stroke.modifiers().isEmpty() && stroke.key() == KeyStroke.Key.BACKSPACE) {
            return intent(
                current.resetClickTracking(),
                Focus.LIST,
                new MoveToParentRoot()
            );
        }
        if (stroke.modifiers().isEmpty() && stroke.key() == KeyStroke.Key.ARROW_RIGHT) {
            var selected = placement.selectedEntry();
            if (selected.isPresent()
                && selected.orElseThrow().kind() == FileTreeModel.Kind.PARENT) {
                return intent(
                    current.resetClickTracking(),
                    Focus.LIST,
                    new ChangeRoot(selected.orElseThrow().path())
                );
            }
        }
        InteractionResult<FileTreeView.Action> interaction = placement.handle(
            stroke,
            nowNanos,
            doubleClickWindowNanos
        );
        return fromInteraction(
            placement,
            interaction,
            Focus.LIST,
            true
        );
    }

    private static Result routePointer(
        FileTreeView.Placement placement,
        Focus focus,
        PointerEvent pointer,
        long nowNanos,
        long doubleClickWindowNanos
    ) {
        FileTreeView.State current = placement.state();
        if (!pointer.modifiers().isEmpty()) {
            return captured(current.resetClickTracking(), focus);
        }
        if (!placement.enabled()) {
            return captured(current.resetClickTracking(), focus);
        }
        if (pointer.kind() == PointerEvent.Kind.DRAG) {
            FileTreeView.State reset = current.resetClickTracking();
            return placement.contains(pointer)
                ? captured(reset, focus)
                : ignored(reset, focus);
        }
        if (pointer.kind() == PointerEvent.Kind.RELEASE) {
            return placement.contains(pointer)
                ? captured(current, focus)
                : ignored(current.resetClickTracking(), focus);
        }
        InteractionResult<FileTreeView.Action> interaction = placement.handle(
            pointer,
            nowNanos,
            doubleClickWindowNanos
        );
        if (!interaction.consumed()) {
            FileTreeView.State next = pointer.kind() == PointerEvent.Kind.PRESS
                ? current.resetClickTracking()
                : current;
            return ignored(next, focus);
        }
        // FocusManager already handles pointer presses; scrolling keeps that focus owner.
        return fromInteraction(
            placement,
            interaction,
            focus,
            true
        );
    }

    private static Result fromInteraction(
        FileTreeView.Placement placement,
        InteractionResult<FileTreeView.Action> interaction,
        Focus focus,
        boolean captureWhenIgnored
    ) {
        return fromWidgetOutput(placement, interaction, focus, captureWhenIgnored)
            .withPointerCapture(interaction.pointerCaptureRequest());
    }

    private static Result fromWidgetOutput(
        FileTreeView.Placement placement,
        InteractionResult<FileTreeView.Action> interaction,
        Focus focus,
        boolean captureWhenIgnored
    ) {
        if (interaction.output().isEmpty()) {
            return interaction.consumed() || captureWhenIgnored
                ? captured(placement.state().resetClickTracking(), focus)
                : ignored(placement.state(), focus);
        }
        FileTreeView.Action action = interaction.output().orElseThrow();
        if (action instanceof FileTreeView.StateChanged changed) {
            return captured(changed.state(), focus);
        }
        if (action instanceof FileTreeView.ToggleRequested toggle) {
            return intent(
                toggle.state(),
                focus,
                new ToggleDirectory(toggle.id(), toggle.expand())
            );
        }
        if (action instanceof FileTreeView.RootRequested root) {
            return intent(root.state(), focus, new ChangeRoot(root.root()));
        }
        if (action instanceof FileTreeView.FileRequested file) {
            return intent(file.state(), focus, new OpenFile(file.file()));
        }
        throw new IllegalStateException("Unsupported file-tree action: " + action);
    }

    private static Result confirmSelection(FileTreeView.Placement placement, Focus focus) {
        FileTreeView.State state = placement.state().resetClickTracking();
        var selected = placement.selectedEntry();
        if (selected.isEmpty()) {
            return intent(state, focus, new SelectionRejected(Rejection.NO_SELECTION));
        }
        FileTreeModel.Entry entry = selected.orElseThrow();
        return switch (entry.kind()) {
            case FILE -> intent(state, focus, new OpenFile(entry.path()));
            case ERROR -> intent(state, focus, new SelectionRejected(Rejection.ERROR));
            case PARENT, DIRECTORY -> intent(
                state,
                focus,
                new SelectionRejected(Rejection.DIRECTORY)
            );
        };
    }

    private static Result ignored(
        FileTreeView.State state,
        Focus focus
    ) {
        return new Result(state, focus, null, false);
    }

    private static Result captured(
        FileTreeView.State state,
        Focus focus
    ) {
        return new Result(
            focus == Focus.LIST ? state : state.cancelPointerInteraction(),
            focus,
            null,
            true
        );
    }

    private static Result intent(
        FileTreeView.State state,
        Focus focus,
        Intent intent
    ) {
        return new Result(
            state.cancelPointerInteraction(),
            focus,
            Objects.requireNonNull(intent, "intent"),
            true
        );
    }
}
