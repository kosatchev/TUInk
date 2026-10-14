package dev.tuink.tui;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.PointerCaptureRequest;
import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.ui.FileTreeView;
import java.nio.file.Path;
import java.util.Objects;

/** Raw-event owner policy for the open-file tree and its three focus stops. */
final class OpenFileInputRouter {
    enum Focus {
        LIST,
        OK,
        CANCEL
    }

    sealed interface Intent
        permits ToggleDirectory, ChangeRoot, OpenFile, MoveToParentRoot,
        CloseDialog, ToggleHidden, SelectionRejected {
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
        ERROR
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
        Result result = routeInput(dialog, focusState, event, nowNanos, doubleClickWindowNanos);
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
        long doubleClickWindowNanos
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
        if (event instanceof PointerEvent pointer && !pointer.modifiers().isEmpty()) {
            return captured(current.resetClickTracking(), requiredFocus);
        }
        if (!placement.enabled()) {
            return captured(current.resetClickTracking(), requiredFocus);
        }
        if (event instanceof KeyStroke stroke) {
            if (stroke.key() == KeyStroke.Key.H
                && stroke.modifiers().equals(java.util.Set.of(KeyStroke.Modifier.CTRL))) {
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
                InteractionResult<Button.Action> interaction = target.placement().handle(
                    event, routed.context().orElseThrow());
                focusState.manager().apply(dialog.focusScope(), target.id(), interaction.focusRequest());
                Focus buttonFocus = focusState.focus();
                if (interaction.output().isPresent()) {
                    return switch (target.id()) {
                        case OK -> confirmSelection(placement, buttonFocus);
                        case CANCEL, CLOSE -> intent(current.resetClickTracking(), buttonFocus, new CloseDialog());
                        case HIDDEN -> {
                            focusState.focus(Focus.LIST);
                            yield intent(current.resetClickTracking(), focusState.focus(), new ToggleHidden());
                        }
                        case LIST -> throw new IllegalStateException("tree is not a button");
                    };
                }
                return captured(current.resetClickTracking(), buttonFocus);
            }
        }
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
