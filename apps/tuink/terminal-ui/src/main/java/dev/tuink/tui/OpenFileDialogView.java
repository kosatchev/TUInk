package dev.tuink.tui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
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
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuink.tui.ui.FileTreeView;
import dev.tuink.tui.ui.OpenFileDialogController;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;
import dev.tuink.tui.ui.NotificationPresentation;
import java.util.List;
import java.util.Objects;

/** Exact-frame composition and focus ownership for the open-file dialog. */
final class OpenFileDialogView {
    enum Control {
        LIST, OK, CANCEL, HIDDEN, CLOSE
    }

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
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(tree, "tree");
        FocusSpace space = FocusSpace.root(Objects.requireNonNull(interactionClip, "interactionClip"));
        var buttons = OpenFileDialogController.buttonsForLayout(layout);
        String hiddenLabel = showHiddenFiles ? "[hidden:x]" : "[hidden: ]";
        Button hidden = Button.plain(layout.top() + 1,
            Math.max(layout.left() + 1, layout.right() - hiddenLabel.length()), hiddenLabel);
        Button close = Button.plain(layout.top(), layout.right() - 2, "X");
        List<RoutedTarget<Control, Button.Action>> targets = List.of(
            new RoutedTarget<>(Control.LIST, new TreeFocusTarget(tree.bounds()), space,
                tree.enabled(), tree.enabled()),
            buttonTarget(Control.OK, buttons.ok(), space, tree.enabled(), true),
            buttonTarget(Control.CANCEL, buttons.cancel(), space, tree.enabled(), true),
            buttonTarget(Control.HIDDEN, hidden, space, tree.enabled(), false),
            buttonTarget(Control.CLOSE, close, space, tree.enabled(), false)
        );
        PlacedModal frame = new Modal(layout.width(), layout.height(),
            tree.loading() ? " Open File (Loading...) " : " Open File ")
            .layout(new Rect(layout.left(), layout.top(), layout.width(), layout.height()));
        return new Placement(layout, frame, tree, new FocusScope<>(
            scopeId, FocusResetPolicy.FIRST_FOCUSABLE, targets,
            List.of(Control.LIST, Control.OK, Control.CANCEL),
            List.of(Control.CLOSE, Control.HIDDEN, Control.CANCEL, Control.OK, Control.LIST)
        ), showHiddenFiles, notice);
    }

    private static RoutedTarget<Control, Button.Action> buttonTarget(
        Control id, Button button, FocusSpace space, boolean enabled, boolean focusable
    ) {
        Rect bounds = new Rect(button.col(), button.row(), button.width(), 1);
        boolean available = enabled && space.parentClip().contains(bounds);
        return new RoutedTarget<>(id, button.layout(bounds), space, available && focusable, available);
    }

    /** The only retained focus owner; the public enum is just its application-facing projection. */
    static final class FocusState {
        private final FocusManager<Control> manager = new FocusManager<>();
        private FocusScope<Control, Button.Action> scope;
        private OpenFileInputRouter.Focus pendingFocus = OpenFileInputRouter.Focus.LIST;

        OpenFileInputRouter.Focus focus() {
            return scope == null ? pendingFocus : external(manager.focused().orElse(Control.LIST));
        }

        void focus(OpenFileInputRouter.Focus focus) {
            Objects.requireNonNull(focus, "focus");
            if (scope == null) {
                pendingFocus = focus;
            } else {
                manager.focus(scope, control(focus));
            }
        }

        private void bind(Placement placement) {
            scope = placement.focusScope();
            manager.context(scope, Control.LIST);
            if (pendingFocus != null) {
                manager.focus(scope, control(pendingFocus));
                pendingFocus = null;
            }
        }

        FocusManager<Control> manager() {
            return manager;
        }
    }

    record Placement(
        OpenDialogLayout layout,
        PlacedModal frame,
        FileTreeView.Placement tree,
        FocusScope<Control, Button.Action> focusScope,
        boolean showHiddenFiles,
        FilePickerOpenWorkflow.Notice notice
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
            RoutedTarget<Control, Button.Action> list = focusScope.target(Control.LIST).orElseThrow();
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
                    return target.enabled() && target.focusable();
                })
                .map(OpenFileDialogView::external)
                .toList();
        }

        /** Paints the same placed controls used for routing; Modal paints its own close mark. */
        void paintControls(Canvas canvas, Theme theme, OpenFileInputRouter.Focus focused, boolean contentFocused) {
            for (Control id : List.of(Control.HIDDEN, Control.OK, Control.CANCEL)) {
                var target = focusScope.target(id).orElseThrow();
                boolean highlighted = id == Control.HIDDEN ? showHiddenFiles
                    : !contentFocused && control(focused) == id;
                target.placement().paint(canvas, theme, new WidgetState(highlighted, target.enabled()));
            }
            paintNotice(canvas, theme, new Rect(layout.left() + 1, layout.bottom() - 1,
                Math.max(0, layout.width() - 2), 1));
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
            case OK -> Control.OK;
            case CANCEL -> Control.CANCEL;
        };
    }

    private static OpenFileInputRouter.Focus external(Control control) {
        return switch (control) {
            case OK -> OpenFileInputRouter.Focus.OK;
            case CANCEL -> OpenFileInputRouter.Focus.CANCEL;
            case LIST, HIDDEN, CLOSE -> OpenFileInputRouter.Focus.LIST;
        };
    }

    private record TreeFocusTarget(Rect bounds) implements PlacedInteractiveWidget<Button.Action> {
        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState state) {
            // The file-tree adapter owns content painting; this target supplies focus geometry.
        }

        @Override
        public InteractionResult<Button.Action> handle(TuiInputEvent event, FocusContext context) {
            return InteractionResult.ignored();
        }
    }
}
