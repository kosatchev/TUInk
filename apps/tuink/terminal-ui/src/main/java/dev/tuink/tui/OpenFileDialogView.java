package dev.tuink.tui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.DialogFocusTraversal;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.Modal;
import dev.tuiframe.widget.PlacedModal;
import dev.tuiframe.widget.PlacedInteractiveWidget;
import dev.tuiframe.widget.PlacedCursorSource;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuiframe.explorer.ui.FileTreeView;
import dev.tuiframe.explorer.ui.PathPresentation;
import dev.tuink.tui.ui.OpenFileDialogController;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;
import dev.tuink.tui.ui.NotificationPresentation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.nio.file.Path;

/** Exact-frame composition and focus ownership for the open-file dialog. */
final class OpenFileDialogView {
    enum Control {
        LIST, LOCATION, OK, CANCEL, HIDDEN, CLOSE
    }

    enum Action { ACTIVATE, LOCATION_CHANGED, LOCATION_SUBMITTED, INPUT_REJECTED }

    private OpenFileDialogView() {
    }

    static Placement place(
        Object scopeId,
        OpenDialogLayout layout,
        FileTreeView.Placement tree,
        boolean showHiddenFiles,
        Rect interactionClip
    ) {
        return place(scopeId, layout, tree, showHiddenFiles, interactionClip, null);
    }

    static Placement place(
        Object scopeId,
        OpenDialogLayout layout,
        FileTreeView.Placement tree,
        boolean showHiddenFiles,
        Rect interactionClip,
        FilePickerOpenWorkflow.Notice notice
    ) {
        return place(scopeId, layout, tree, showHiddenFiles, interactionClip, notice, new TextField());
    }

    static Placement place(Object scopeId, OpenDialogLayout layout, FileTreeView.Placement tree,
        boolean showHiddenFiles, Rect interactionClip, FilePickerOpenWorkflow.Notice notice, TextField location) {
        return place(scopeId, layout, tree, showHiddenFiles, interactionClip, notice, location, null);
    }

    static Placement place(Object scopeId, OpenDialogLayout layout, FileTreeView.Placement tree,
        boolean showHiddenFiles, Rect interactionClip, FilePickerOpenWorkflow.Notice notice, TextField location,
        Path folderRoot) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(location, "location");
        FocusSpace space = FocusSpace.root(Objects.requireNonNull(interactionClip, "interactionClip"));
        CompactDialogView.Placement compact = CompactDialogView.required(interactionClip.height(), interactionClip.width())
            ? CompactDialogView.place(interactionClip,
                notice != null && interactionClip.height() < 3
                    ? NotificationPresentation.fitReasonWithPath(notice.summary(), notice.path(), interactionClip.width())
                    : folderRoot == null ? "Open file" : "Open folder")
            : null;
        var buttons = OpenFileDialogController.buttonsForLayout(layout);
        if (folderRoot != null && layout.width() >= 27) {
            String label = "Open folder";
            String cancel = " Cancel ";
            int total = label.length() + 2 + 2 + cancel.length() + 2;
            int start = layout.left() + Math.max(1, (layout.width() - 2 - total) / 2);
            Button ok = new Button(buttons.ok().row(), start, label);
            buttons = new OpenFileDialogController.OpenDialogButtons(ok,
                new Button(buttons.cancel().row(), start + ok.width() + 2, cancel));
        }
        String hiddenLabel = showHiddenFiles ? "Hidden [x]" : "Hidden [ ]";
        Button hidden = Button.plain(layout.top() + 1,
            Math.max(layout.left() + 1, layout.right() - hiddenLabel.length()), hiddenLabel);
        Button close = compact == null ? Button.plain(layout.top(), layout.right() - 2, "X") : compact.closeButton();
        int locationLeft = layout.left() + 7;
        Rect locationBounds = new Rect(locationLeft, layout.top() + 1,
            Math.max(0, hidden.col() - locationLeft - 1), 1);
        List<RoutedTarget<Control, Action>> targets = List.of(
            new RoutedTarget<>(Control.LIST, new TreeFocusTarget(tree.bounds()), space,
                true, tree.enabled()),
            new RoutedTarget<>(Control.LOCATION, location.layout(locationBounds).mapOutput(action -> switch (action) {
                case VALUE_CHANGED -> Action.LOCATION_CHANGED;
                case SUBMITTED -> Action.LOCATION_SUBMITTED;
                case INPUT_REJECTED -> Action.INPUT_REJECTED;
            }), space, true, compact == null && locationBounds.width() >= 3),
            buttonTarget(Control.OK, buttons.ok(), space, tree.enabled(), true),
            buttonTarget(Control.CANCEL, buttons.cancel(), space, tree.enabled(), true),
            buttonTarget(Control.HIDDEN, hidden, space, tree.enabled(), true),
            buttonTarget(Control.CLOSE, close, space,
                compact != null || tree.enabled(), compact != null)
        );
        String title = folderRoot == null ? "Open File" : "Open Folder";
        PlacedModal frame = compact != null ? compact.frame() : new Modal(layout.width(), layout.height(),
            " " + title + (tree.loading() ? " (Loading...) " : " "))
            .layout(new Rect(layout.left(), layout.top(), layout.width(), layout.height()));
        return new Placement(layout, frame, tree, new FocusScope<>(
            scopeId, FocusResetPolicy.FIRST_FOCUSABLE, targets,
            compact == null ? List.of(Control.LIST, Control.LOCATION, Control.HIDDEN, Control.OK, Control.CANCEL)
                : List.of(Control.CLOSE),
            List.of(Control.CLOSE, Control.HIDDEN, Control.CANCEL, Control.OK, Control.LOCATION, Control.LIST)
        ), showHiddenFiles, compact, notice, location, folderRoot);
    }

    private static RoutedTarget<Control, Action> buttonTarget(
        Control id, Button button, FocusSpace space, boolean enabled, boolean focusable
    ) {
        Rect bounds = new Rect(button.col(), button.row(), button.width(), 1);
        return new RoutedTarget<>(id, button.layout(bounds).mapOutput(ignored -> Action.ACTIVATE), space, focusable, enabled);
    }

    /** The only retained focus owner; the public enum is just its application-facing projection. */
    static final class FocusState {
        private final FocusManager<Control> manager = new FocusManager<>();
        private FocusScope<Control, Action> scope;
        private final TextField location = new TextField(TextField.PastePolicy.PRESERVE_TABS);
        private Path locationRoot;
        private Path selectedDirectory;
        private String locationBaseline;
        private OpenFileInputRouter.Focus pendingFocus = OpenFileInputRouter.Focus.LIST;
        private boolean compact;
        private Control focusBeforeCompact;

        TextField location() { return location; }

        boolean locationEdited() {
            return locationBaseline != null && !location.text().equals(locationBaseline);
        }

        void synchronizeRoot(Path root) {
            if (!Objects.equals(root, locationRoot)) resetLocation(root);
        }

        void resetLocation(Path root) {
            if (location.set(PathPresentation.full(root))) {
                locationRoot = root;
                selectedDirectory = root;
                locationBaseline = location.text();
            }
        }

        Path selectedDirectory() { return selectedDirectory; }

        void selectDirectory(dev.tuiframe.explorer.file.FileTreeModel.EntryId selected) {
            if (selected == null || selected.kind() != dev.tuiframe.explorer.file.FileTreeModel.Kind.DIRECTORY
                && selected.kind() != dev.tuiframe.explorer.file.FileTreeModel.Kind.PARENT) return;
            if (location.set(PathPresentation.full(selected.path()))) {
                selectedDirectory = selected.path();
                locationBaseline = location.text();
            }
        }

        OpenFileInputRouter.Focus focus() {
            return scope == null ? pendingFocus : external(manager.focused().orElse(Control.LIST));
        }

        void focus(OpenFileInputRouter.Focus focus) {
            Objects.requireNonNull(focus, "focus");
            if (scope == null || compact) {
                pendingFocus = focus;
            } else {
                manager.focus(scope, control(focus));
            }
        }

        private void bind(Placement placement) {
            boolean enteringCompact = placement.compactDialog() != null;
            if (enteringCompact && !compact) {
                focusBeforeCompact = manager.focused().orElse(Control.LIST);
            }
            scope = placement.focusScope();
            manager.context(scope, Control.LIST);
            if (!enteringCompact && pendingFocus != null) {
                manager.focus(scope, control(pendingFocus));
                pendingFocus = null;
            } else if (!enteringCompact && compact && focusBeforeCompact != null) {
                manager.focus(scope, focusBeforeCompact);
            }
            if (!enteringCompact) {
                focusBeforeCompact = null;
            }
            compact = enteringCompact;
        }

        FocusManager<Control> manager() {
            return manager;
        }
    }

    record Placement(
        OpenDialogLayout layout,
        PlacedModal frame,
        FileTreeView.Placement tree,
        FocusScope<Control, Action> focusScope,
        boolean showHiddenFiles,
        CompactDialogView.Placement compactDialog,
        FilePickerOpenWorkflow.Notice notice,
        TextField location,
        Path folderRoot
    ) {
        Placement {
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(tree, "tree");
            Objects.requireNonNull(focusScope, "focusScope");
        }

        void activate(FocusState focus) {
            Objects.requireNonNull(focus, "focus").bind(this);
        }

        /** Tree gesture delivery has its own output type and no keyboard-focus side effects. */
        FocusScope<Control, FileTreeView.Action> captureScope() {
            RoutedTarget<Control, Action> list = focusScope.target(Control.LIST).orElseThrow();
            return FocusScope.single(new TreeCaptureScopeId(focusScope.id()), FocusResetPolicy.CLEARED,
                new RoutedTarget<>(Control.LIST, tree.captureTarget(), list.space(), false, tree.enabled()));
        }

        void focusFirst(FocusState focus) {
            activate(focus);
            DialogFocusTraversal.focusFirst(focus.manager(), focusScope);
        }

        List<OpenFileInputRouter.Focus> focusOrder() {
            return focusScope.tabOrder().stream()
                .filter(id -> {
                    var target = focusScope.target(id).orElseThrow();
                    return target.focusEligible();
                })
                .map(OpenFileDialogView::external)
                .toList();
        }

        /** Paints the same placed controls used for routing; Modal paints its own close mark. */
        void paintControls(Canvas canvas, Theme theme, OpenFileInputRouter.Focus focused, boolean contentFocused) {
            var body = theme.resolveStyle(WidgetThemeKeys.MODAL_BODY);
            canvas.fill(new Rect(layout.left() + 1, layout.top() + 1, Math.max(0, layout.width() - 2), 1), body);
            CellText.drawClipped(canvas, layout.left() + 1, layout.top() + 1, "Path: ", body,
                Math.max(0, layout.width() - 2));
            for (Control id : List.of(Control.LOCATION, Control.HIDDEN, Control.OK, Control.CANCEL)) {
                var target = focusScope.target(id).orElseThrow();
                boolean highlighted = !contentFocused && control(focused) == id;
                target.placement().paint(canvas, theme, new WidgetState(highlighted, target.interactive()));
            }
            paintNotice(canvas, theme, new Rect(layout.left() + 1, layout.bottom() - 1,
                Math.max(0, layout.width() - 2), 1));
        }

        Optional<Point> cursorPosition(OpenFileInputRouter.Focus focus) {
            if (focus != OpenFileInputRouter.Focus.LOCATION) return Optional.empty();
            var target = focusScope.target(Control.LOCATION).orElseThrow();
            if (!(target.placement() instanceof PlacedCursorSource source)) return Optional.empty();
            var context = new FocusContext(true, target.interactive(), target.space());
            return source.cursorPosition(context).flatMap(context.space()::terminalPoint);
        }

        void paintCompact(Canvas canvas, Theme theme, WidgetState closeState) {
            compactDialog.paint(canvas, theme, closeState);
            Rect bounds = compactDialog.bounds();
            if (notice != null && bounds.height() >= 3) {
                paintNotice(canvas, theme, new Rect(bounds.x() + 1, bounds.y() + 1,
                    Math.max(0, bounds.width() - 2), 1));
            }
        }

        private void paintNotice(Canvas canvas, Theme theme, Rect bounds) {
            if (bounds.isEmpty()) return;
            CellStyle body = theme.resolveStyle(WidgetThemeKeys.MODAL_BODY);
            CellStyle style = notice == null ? body : new CellStyle(body.fg(), body.bg(), true, body.underline());
            canvas.fill(bounds, style);
            if (notice != null) {
                String message = NotificationPresentation.fitReasonWithPath(notice.summary(), notice.path(), bounds.width());
                CellText.drawClipped(canvas, bounds.x(), bounds.y(), message, style, bounds.width());
            }
        }
    }

    private record TreeCaptureScopeId(Object dialogScopeId) {
    }

    private static Control control(OpenFileInputRouter.Focus focus) {
        return switch (focus) {
            case LIST -> Control.LIST;
            case LOCATION -> Control.LOCATION;
            case HIDDEN -> Control.HIDDEN;
            case OK -> Control.OK;
            case CANCEL -> Control.CANCEL;
        };
    }

    private static OpenFileInputRouter.Focus external(Control control) {
        return switch (control) {
            case OK -> OpenFileInputRouter.Focus.OK;
            case CANCEL -> OpenFileInputRouter.Focus.CANCEL;
            case LOCATION -> OpenFileInputRouter.Focus.LOCATION;
            case HIDDEN -> OpenFileInputRouter.Focus.HIDDEN;
            case LIST, CLOSE -> OpenFileInputRouter.Focus.LIST;
        };
    }

    private record TreeFocusTarget(Rect bounds) implements PlacedInteractiveWidget<Action> {
        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState state) {
            // The file-tree adapter owns content painting; this target supplies focus geometry.
        }

        @Override
        public InteractionResult<Action> handle(TuiInputEvent event, FocusContext context) {
            return InteractionResult.ignored();
        }
    }
}
