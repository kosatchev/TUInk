package dev.tuink.tui;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.InteractionResult;
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
        FileTreeView.ScrollbarPress scrollbarPress,
        Intent intent,
        boolean consumed
    ) {
        Result {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(focus, "focus");
            if (intent != null && !consumed) {
                throw new IllegalArgumentException("an ignored event cannot request an intent");
            }
        }
    }

    private OpenFileInputRouter() {
    }

    static Result route(
        FileTreeView.Placement placement,
        Focus focus,
        FileTreeView.ScrollbarPress scrollbarPress,
        TuiInputEvent event,
        long nowNanos,
        long doubleClickWindowNanos
    ) {
        Objects.requireNonNull(placement, "placement");
        Focus requiredFocus = Objects.requireNonNull(focus, "focus");
        Objects.requireNonNull(event, "event");
        if (doubleClickWindowNanos < 0L) {
            throw new IllegalArgumentException("doubleClickWindowNanos must not be negative");
        }

        FileTreeView.State current = placement.state();
        if (event instanceof ResizeEvent) {
            return ignored(current, requiredFocus, scrollbarPress);
        }
        if (event instanceof TextInput || event instanceof PasteEvent) {
            return captured(current, requiredFocus, scrollbarPress);
        }
        if (event instanceof KeyStroke stroke) {
            return routeKey(
                placement,
                requiredFocus,
                scrollbarPress,
                stroke,
                nowNanos,
                doubleClickWindowNanos
            );
        }
        if (event instanceof PointerEvent pointer) {
            return routePointer(
                placement,
                requiredFocus,
                scrollbarPress,
                pointer,
                nowNanos,
                doubleClickWindowNanos
            );
        }
        return captured(current, requiredFocus, scrollbarPress);
    }

    private static Result routeKey(
        FileTreeView.Placement placement,
        Focus focus,
        FileTreeView.ScrollbarPress scrollbarPress,
        KeyStroke stroke,
        long nowNanos,
        long doubleClickWindowNanos
    ) {
        FileTreeView.State current = placement.state();
        if (stroke.modifiers().isEmpty() && stroke.key() == KeyStroke.Key.ESCAPE) {
            return intent(current.resetClickTracking(), focus, null, new CloseDialog());
        }
        if (!placement.enabled()) {
            // The modal remains the raw owner while resize hides its controls.
            // Only the always-safe close command is allowed above.
            return captured(current.resetClickTracking(), focus, null);
        }
        if (stroke.key() == KeyStroke.Key.TAB
            && (stroke.modifiers().isEmpty()
                || stroke.modifiers().equals(java.util.Set.of(KeyStroke.Modifier.SHIFT)))) {
            Focus next = stroke.modifiers().isEmpty() ? next(focus) : previous(focus);
            return captured(current.resetClickTracking(), next, null);
        }
        if (stroke.key() == KeyStroke.Key.H
            && stroke.modifiers().equals(java.util.Set.of(KeyStroke.Modifier.CTRL))) {
            return intent(current.resetClickTracking(), Focus.LIST, null, new ToggleHidden());
        }

        if (focus == Focus.CANCEL) {
            if (stroke.modifiers().isEmpty() && stroke.key() == KeyStroke.Key.ENTER) {
                return intent(current.resetClickTracking(), focus, null, new CloseDialog());
            }
            if (stroke.modifiers().isEmpty() && stroke.key() == KeyStroke.Key.ARROW_LEFT) {
                return captured(current.resetClickTracking(), Focus.OK, null);
            }
            return captured(current, focus, scrollbarPress);
        }
        if (focus == Focus.OK) {
            if (stroke.modifiers().isEmpty() && stroke.key() == KeyStroke.Key.ENTER) {
                return confirmSelection(placement, Focus.OK);
            }
            if (stroke.modifiers().isEmpty() && stroke.key() == KeyStroke.Key.ARROW_LEFT) {
                return captured(current.resetClickTracking(), Focus.LIST, null);
            }
            if (stroke.modifiers().isEmpty() && stroke.key() == KeyStroke.Key.ARROW_RIGHT) {
                return captured(current.resetClickTracking(), Focus.CANCEL, null);
            }
            return captured(current, focus, scrollbarPress);
        }

        if (stroke.modifiers().isEmpty() && stroke.key() == KeyStroke.Key.BACKSPACE) {
            return intent(
                current.resetClickTracking(),
                Focus.LIST,
                null,
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
                    null,
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
            scrollbarPress,
            true
        );
    }

    private static Result routePointer(
        FileTreeView.Placement placement,
        Focus focus,
        FileTreeView.ScrollbarPress scrollbarPress,
        PointerEvent pointer,
        long nowNanos,
        long doubleClickWindowNanos
    ) {
        FileTreeView.State current = placement.state();
        if (scrollbarPress != null) {
            if (pointer.kind() == PointerEvent.Kind.RELEASE) {
                return captured(current.resetClickTracking(), Focus.LIST, null);
            }
            if (pointer.kind() == PointerEvent.Kind.DRAG) {
                return captured(placement.drag(pointer, scrollbarPress), Focus.LIST, scrollbarPress);
            }
            return captured(current.resetClickTracking(), Focus.LIST, scrollbarPress);
        }
        if (!pointer.modifiers().isEmpty()) {
            return captured(current.resetClickTracking(), focus, scrollbarPress);
        }
        if (!placement.enabled()) {
            return captured(current.resetClickTracking(), focus, null);
        }
        if (pointer.kind() == PointerEvent.Kind.DRAG) {
            FileTreeView.State reset = current.resetClickTracking();
            return placement.contains(pointer)
                ? captured(reset, Focus.LIST, null)
                : ignored(reset, focus, null);
        }
        if (pointer.kind() == PointerEvent.Kind.RELEASE) {
            return placement.contains(pointer)
                ? captured(current, Focus.LIST, null)
                : ignored(current.resetClickTracking(), focus, null);
        }
        if (pointer.kind() == PointerEvent.Kind.PRESS
            && pointer.button() == PointerEvent.Button.LEFT) {
            var press = placement.scrollbarPress(pointer);
            if (press.isPresent()) {
                return captured(current.resetClickTracking(), Focus.LIST, press.orElseThrow());
            }
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
            return ignored(next, focus, scrollbarPress);
        }
        boolean treeHit = placement.contains(pointer);
        return fromInteraction(
            placement,
            interaction,
            treeHit ? Focus.LIST : focus,
            scrollbarPress,
            true
        );
    }

    private static Result fromInteraction(
        FileTreeView.Placement placement,
        InteractionResult<FileTreeView.Action> interaction,
        Focus focus,
        FileTreeView.ScrollbarPress scrollbarPress,
        boolean captureWhenIgnored
    ) {
        if (interaction.output().isEmpty()) {
            return interaction.consumed() || captureWhenIgnored
                ? captured(placement.state().resetClickTracking(), focus, scrollbarPress)
                : ignored(placement.state(), focus, scrollbarPress);
        }
        FileTreeView.Action action = interaction.output().orElseThrow();
        if (action instanceof FileTreeView.StateChanged changed) {
            return captured(changed.state(), focus, scrollbarPress);
        }
        if (action instanceof FileTreeView.ToggleRequested toggle) {
            return intent(
                toggle.state(),
                focus,
                null,
                new ToggleDirectory(toggle.id(), toggle.expand())
            );
        }
        if (action instanceof FileTreeView.RootRequested root) {
            return intent(root.state(), focus, null, new ChangeRoot(root.root()));
        }
        if (action instanceof FileTreeView.FileRequested file) {
            return intent(file.state(), focus, null, new OpenFile(file.file()));
        }
        throw new IllegalStateException("Unsupported file-tree action: " + action);
    }

    private static Result confirmSelection(FileTreeView.Placement placement, Focus focus) {
        FileTreeView.State state = placement.state().resetClickTracking();
        var selected = placement.selectedEntry();
        if (selected.isEmpty()) {
            return intent(state, focus, null, new SelectionRejected(Rejection.NO_SELECTION));
        }
        FileTreeModel.Entry entry = selected.orElseThrow();
        return switch (entry.kind()) {
            case FILE -> intent(state, focus, null, new OpenFile(entry.path()));
            case ERROR -> intent(state, focus, null, new SelectionRejected(Rejection.ERROR));
            case PARENT, DIRECTORY -> intent(
                state,
                focus,
                null,
                new SelectionRejected(Rejection.DIRECTORY)
            );
        };
    }

    private static Focus next(Focus focus) {
        return switch (focus) {
            case LIST -> Focus.OK;
            case OK -> Focus.CANCEL;
            case CANCEL -> Focus.LIST;
        };
    }

    private static Focus previous(Focus focus) {
        return switch (focus) {
            case LIST -> Focus.CANCEL;
            case OK -> Focus.LIST;
            case CANCEL -> Focus.OK;
        };
    }

    private static Result ignored(
        FileTreeView.State state,
        Focus focus,
        FileTreeView.ScrollbarPress scrollbarPress
    ) {
        return new Result(state, focus, scrollbarPress, null, false);
    }

    private static Result captured(
        FileTreeView.State state,
        Focus focus,
        FileTreeView.ScrollbarPress scrollbarPress
    ) {
        return new Result(state, focus, scrollbarPress, null, true);
    }

    private static Result intent(
        FileTreeView.State state,
        Focus focus,
        FileTreeView.ScrollbarPress scrollbarPress,
        Intent intent
    ) {
        return new Result(
            state,
            focus,
            scrollbarPress,
            Objects.requireNonNull(intent, "intent"),
            true
        );
    }
}
