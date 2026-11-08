package dev.tuink.tui;

import dev.tuiframe.explorer.Explorer;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalPlainText;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.Modal;
import dev.tuiframe.widget.PlacedCursorSource;
import dev.tuiframe.widget.PlacedModal;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuiframe.explorer.file.ExplorerFileService;
import dev.tuiframe.explorer.file.PathLabels;
import dev.tuiframe.explorer.ui.FileTreeView;
import dev.tuiframe.explorer.ui.PathPresentation;
import dev.tuink.tui.flow.SavePathInput;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Application-owned file action dialog; shares the ordinary tree and focus contracts. */
final class ExplorerFileDialogView {
    enum Target { TREE, FIELD, FULL_PATH, APPLY, CANCEL }
    enum Action { NONE, APPLY, CANCEL }

    static final class State implements AutoCloseable {
        final ExplorerFileService.Action action;
        final Path source;
        final Path parent;
        final TextField field = new TextField();
        final FocusManager<Target> focus = new FocusManager<>();
        Explorer tree;
        final Path relativeRoot;
        private Path destination;
        private String synchronizedPath;
        boolean fullPath;
        ExplorerFileService.Target inspected;
        Target requestedFocus;
        boolean busy;
        boolean operationAvailable = true;
        boolean destinationSelected;
        String error;

        State(ExplorerFileService.Action action, Path source, Path parent) {
            this(action, source, parent, parent);
        }

        State(ExplorerFileService.Action action, Path source, Path parent, Path root) {
            this.action = action; this.source = source; this.parent = parent;
            relativeRoot = (root == null ? parent == null ? Path.of(".") : parent : root).toAbsolutePath().normalize();
            tree = action == ExplorerFileService.Action.MOVE || action == ExplorerFileService.Action.COPY
                ? Explorer.directoryPicker(relativeRoot) : null;
            requestedFocus = action.removes() ? Target.CANCEL
                : tree == null ? Target.FIELD : Target.TREE;
            if (tree != null) {
                destination = parent.toAbsolutePath().normalize().startsWith(relativeRoot)
                    ? parent.toAbsolutePath().normalize() : relativeRoot;
                synchronizeDestination();
                tree.reveal(destination);
            }
            else if (action == ExplorerFileService.Action.RENAME) {
                field.set(source.getFileName().toString());
                field.selectAll();
            }
        }

        boolean creating() { return action == ExplorerFileService.Action.CREATE_FILE || action == ExplorerFileService.Action.CREATE_DIRECTORY; }
        boolean naming() { return creating() || action == ExplorerFileService.Action.RENAME; }
        Path destinationFolder() {
            return tree == null ? parent : destination;
        }
        boolean destinationAllowed() {
            var selected = tree == null ? null : tree.selectedEntry();
            return tree == null || field.text().equals(synchronizedPath)
                && (!destinationSelected || selected != null && (selected.directory()
                    || selected.kind() == dev.tuiframe.explorer.file.FileTreeModel.Kind.PARENT));
        }
        String display(Path path) {
            return tree == null || fullPath || !path.toAbsolutePath().normalize().startsWith(relativeRoot)
                ? PathLabels.full(path) : PathLabels.relative(relativeRoot, path);
        }
        private void synchronizeDestination() {
            synchronizedPath = display(destination);
            field.set(synchronizedPath);
        }
        void setFullPath(boolean enabled) {
            if (enabled == fullPath) return;
            if (!field.text().equals(synchronizedPath)) {
                error = "Press Enter to use the entered path before changing the view";
                return;
            }
            if (!enabled && !destination.startsWith(relativeRoot)) {
                error = "Destination is outside the open folder; use full path";
                return;
            }
            fullPath = enabled;
            replaceTree();
        }
        private void replaceTree() {
            boolean hidden = tree.showHiddenFiles();
            tree.close();
            tree = fullPath ? new Explorer(destination, false, false) : Explorer.directoryPicker(relativeRoot);
            if (hidden) tree.toggleHiddenFiles();
            if (!fullPath) tree.reveal(destination);
            destinationSelected = false;
            synchronizeDestination();
            error = null;
        }
        void navigate(String input) {
            Path base = fullPath ? tree.root() : relativeRoot;
            destination = base.resolve(SavePathInput.parse(input)).toAbsolutePath().normalize();
            if (!destination.startsWith(relativeRoot)) fullPath = true;
            replaceTree();
            requestedFocus = Target.TREE;
        }
        void treeAction(FileTreeView.Action action) {
            var previous = tree.state();
            boolean selected = !java.util.Objects.equals(previous.selectedId(), action.state().selectedId())
                || action instanceof FileTreeView.ToggleRequested
                || action.state().lastPressedId() != null
                    && (!java.util.Objects.equals(previous.lastPressedId(), action.state().lastPressedId())
                        || previous.lastPressedAtNanos() != action.state().lastPressedAtNanos());
            tree.adopt(action.state());
            if (selected) destinationSelected = true;
            if (action instanceof FileTreeView.ToggleRequested toggle) tree.requestExpansion(toggle.id(), toggle.expand());
            if (action instanceof FileTreeView.RootRequested root) {
                tree.setRoot(root.root()); destination = tree.root(); destinationSelected = false;
                synchronizeDestination();
            } else if (selected) {
                var entry = tree.selectedEntry();
                if (entry != null && (entry.directory()
                    || entry.kind() == dev.tuiframe.explorer.file.FileTreeModel.Kind.PARENT)) {
                    destination = entry.path();
                    synchronizeDestination();
                }
            }
            if (selected || action instanceof FileTreeView.RootRequested) error = null;
        }
        @Override public void close() { if (tree != null) tree.close(); }
    }

    static Placement place(State state, Rect viewport) {
        int width = Math.min(76, viewport.width());
        int height = Math.min(state.tree == null ? 10 : 22, viewport.height());
        Rect bounds = new Rect(viewport.x() + (viewport.width() - width) / 2,
            viewport.y() + (viewport.height() - height) / 2, width, height);
        boolean compact = width < 28 || height < (state.tree == null ? 7 : 10);
        String title = switch (state.action) {
            case CREATE_FILE -> "Create file"; case CREATE_DIRECTORY -> "Create folder";
            case COPY -> "Copy"; case MOVE -> "Move"; case RENAME -> "Rename";
            case DELETE -> "Delete permanently"; case TRASH -> "Move to Trash";
        };
        PlacedModal frame = new Modal(width, height, title).layout(bounds);
        FocusSpace space = FocusSpace.root(viewport);
        ArrayList<RoutedTarget<Target, Runnable>> targets = new ArrayList<>();
        FileTreeView.Placement tree = null;
        if (!compact) {
            if (state.tree != null) {
                Rect treeBounds = new Rect(bounds.x() + 2, bounds.y() + 4, Math.max(0, width - 4), Math.max(0, height - 8));
                boolean focused = state.requestedFocus == Target.TREE
                    || state.requestedFocus == null && state.focus.focused().orElse(null) == Target.TREE;
                tree = state.tree.place(treeBounds, viewport, focused, true);
                targets.add(new RoutedTarget<>(Target.TREE, tree.captureTarget().mapOutput(action ->
                    (Runnable) () -> state.treeAction(action)), space, true, !state.busy && tree.enabled()));
                var fullPath = Button.plain(0, 0, (state.fullPath ? "[x] " : "[ ] ") + "Use full path");
                targets.add(new RoutedTarget<>(Target.FULL_PATH, fullPath.layout(new Rect(bounds.x() + 2,
                    bounds.y() + 3, Math.min(width - 4, fullPath.width()), 1))
                    .mapOutput(ignored -> (Runnable) () -> state.setFullPath(!state.fullPath)), space, true, !state.busy));
            }
            if (!state.action.removes()) {
                int row = state.tree == null ? bounds.y() + 3 : bounds.y() + 2;
                var field = state.field.layout(new Rect(bounds.x() + 8, row, Math.max(0, width - 10), 1));
                targets.add(new RoutedTarget<>(Target.FIELD, field.mapOutput(action -> (Runnable) () -> {
                    state.error = null;
                    if (action == TextField.Action.SUBMITTED && state.tree != null) {
                        try {
                            state.navigate(state.field.text());
                        } catch (IllegalArgumentException failure) { state.error = "Invalid destination path"; }
                    }
                }), space, true, !state.busy));
            }
            String primary = switch (state.action) {
                case CREATE_FILE, CREATE_DIRECTORY -> "Create"; case COPY -> "Copy here"; case MOVE -> "Move here";
                case RENAME -> "Rename"; case DELETE -> "Delete"; case TRASH -> "Trash";
            };
            Button apply = new Button(primary);
            boolean ready = !state.busy && state.operationAvailable && (state.creating() || state.inspected != null)
                && state.destinationAllowed()
                && (state.tree == null || !state.tree.loading() && state.tree.directoryFailure() == null);
            targets.add(new RoutedTarget<>(Target.APPLY, apply.layout(new Rect(bounds.x() + 2,
                bounds.bottomExclusive() - 2, apply.width(), 1)).mapOutput(ignored -> (Runnable) () -> {}), space, true, ready));
        }
        Button cancel = new Button("Cancel");
        targets.add(new RoutedTarget<>(Target.CANCEL, cancel.layout(new Rect(
            Math.max(bounds.x(), bounds.rightExclusive() - cancel.width() - 2),
            Math.max(bounds.y(), bounds.bottomExclusive() - 2), Math.min(cancel.width(), width), Math.min(1, height)))
            .mapOutput(ignored -> (Runnable) () -> {}), space, true, !state.busy));
        var order = targets.stream().map(RoutedTarget::id).sorted().toList();
        var scope = new FocusScope<>(state, FocusResetPolicy.FIRST_FOCUSABLE, targets, order, order);
        state.focus.context(scope, Target.CANCEL);
        if (state.requestedFocus != null && !compact && !state.busy) {
            state.focus.focus(scope, state.requestedFocus); state.requestedFocus = null;
        }
        return new Placement(state, frame, scope, tree, compact);
    }

    record Placement(State state, PlacedModal frame, FocusScope<Target, Runnable> scope,
        FileTreeView.Placement tree, boolean compact) {
        Rect bounds() { return frame.bounds(); }

        Action handle(TuiInputEvent event, WindowPointerCapture capture, Object surface) {
            if (state.busy) return Action.NONE;
            if (event instanceof KeyStroke stroke && stroke.key() == KeyStroke.Key.ESCAPE && stroke.modifiers().isEmpty())
                return Action.CANCEL;
            if (event instanceof PointerEvent pointer && pointer.kind() == PointerEvent.Kind.PRESS
                && pointer.button() == PointerEvent.Button.LEFT && frame.closeHit(pointer.row(), pointer.column())) return Action.CANCEL;
            if (state.tree != null && event instanceof KeyStroke key
                && key.modifiers().equals(java.util.Set.of(KeyStroke.Modifier.CTRL))) {
                if (key.key() == KeyStroke.Key.L) { state.focus.focus(scope, Target.FIELD); state.field.selectAll(); return Action.NONE; }
                if (key.key() == KeyStroke.Key.H) { state.tree.toggleHiddenFiles(); return Action.NONE; }
            }
            var route = state.focus.route(scope, event);
            if (route.navigationConsumed() || route.target().isEmpty()) return Action.NONE;
            var target = route.target().orElseThrow();
            var result = target.id() == Target.TREE && tree != null
                ? tree.handle(event, System.nanoTime(), TerminalEditorApp.OPEN_DIALOG_DOUBLE_CLICK_NANOS)
                    .mapOutput(action -> (Runnable) () -> state.treeAction(action))
                : target.id() == Target.FIELD ? FieldInputDispatcher.event(state.field, event,
                    FieldInputDispatcher.MEMORY_BOUNDED,
                    input -> target.placement().handle(input, route.context().orElseThrow()),
                    (Runnable) () -> state.error = "Text input rejected")
                : target.placement().handle(event, route.context().orElseThrow());
            state.focus.apply(scope, target.id(), result.focusRequest());
            result.output().ifPresent(Runnable::run);
            capture.observe(surface, scope, target.id(), result);
            if (result.output().isPresent()) {
                if (target.id() == Target.CANCEL) return Action.CANCEL;
                if (target.id() == Target.APPLY || target.id() == Target.FIELD && state.naming()
                    && event instanceof KeyStroke key && key.key() == KeyStroke.Key.ENTER) return Action.APPLY;
            }
            return Action.NONE;
        }

        Optional<Point> cursor() {
            var focused = state.focus.focused().orElse(null);
            if (focused == Target.TREE && tree != null) return tree.cursorPosition();
            var target = focused == null ? null : scope.target(focused).orElse(null);
            if (target != null && target.placement() instanceof PlacedCursorSource cursor)
                return cursor.cursorPosition(state.focus.peekContext(scope, focused).orElseThrow());
            return Optional.empty();
        }

        void paint(Canvas canvas, Theme theme, CellStyle errorStyle) {
            frame.paint(canvas, theme, WidgetState.NORMAL);
            var body = theme.resolveStyle(WidgetThemeKeys.MODAL_BODY);
            int x = bounds().x() + 2, width = Math.max(0, bounds().width() - 4);
            if (compact) {
                CellText.drawClipped(canvas, x, bounds().y() + 1, "Enlarge terminal", body, width);
            } else {
                Path context = state.source == null ? state.parent : state.source;
                CellText.drawClipped(canvas, x, bounds().y() + 1, state.display(context), body, width);
                if (state.action.removes()) {
                    CellText.drawClipped(canvas, x, bounds().y() + 3,
                        state.action == ExplorerFileService.Action.TRASH ? "Move this entry and its contents to the system Trash?"
                            : "Permanently delete this entry and any folder contents?", body, width);
                    CellText.drawClipped(canvas, x, bounds().y() + 4,
                        "Open document text is kept; use Save As to save it again.", body, width);
                } else CellText.drawClipped(canvas, x, bounds().y() + (state.tree == null ? 3 : 2),
                    state.tree == null ? "Name:" : "Path:", body, 6);
                String message = state.busy ? "Working; wait for the operation to finish" : state.error;
                if (message == null && state.tree != null) message = "To: " + state.display(state.destinationFolder());
                if (message != null) CellText.drawClipped(canvas, x, bounds().bottomExclusive() - 3,
                    TerminalPlainText.singleLine(message), state.error == null ? body : errorStyle, width);
            }
            for (Target id : scope.tabOrder()) {
                var target = scope.target(id).orElseThrow();
                target.placement().paint(canvas, theme, state.focus.peekContext(scope, id).orElseThrow().widgetState());
            }
        }
    }
}
