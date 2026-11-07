package dev.tuink.tui;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.PointerCaptureRequest;
import dev.tuiframe.explorer.ui.FileTreeView;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;
import java.util.concurrent.atomic.AtomicReference;

/** Composes tree-only fixtures with the same controls and focus router as the application. */
final class OpenFileDialogTestSupport {
    private OpenFileDialogTestSupport() {
    }

    static OpenFileDialogView.Placement placement(FileTreeView.Placement tree) {
        Rect bounds = tree.bounds();
        int top = Math.max(0, bounds.y() - 2);
        int left = Math.max(0, bounds.x() - 2);
        int width = Math.max(28, bounds.width() + 4);
        int height = Math.max(8, bounds.height() + 3);
        return OpenFileDialogView.place("test-open-file",
            new OpenDialogLayout(top, left, width, height, bounds.y(), bounds.height()),
            tree, false, new Rect(0, 0, left + width, top + height));
    }

    static OpenFileInputRouter.Result route(
        FileTreeView.Placement tree,
        OpenFileInputRouter.Focus initial,
        TuiInputEvent event,
        long nowNanos,
        long doubleClickWindowNanos
    ) {
        OpenFileDialogView.FocusState focus = new OpenFileDialogView.FocusState();
        focus.focus(initial);
        return route(tree, focus, event, nowNanos, doubleClickWindowNanos);
    }

    static OpenFileInputRouter.Result route(
        FileTreeView.Placement tree,
        OpenFileDialogView.FocusState focus,
        TuiInputEvent event,
        long nowNanos,
        long doubleClickWindowNanos
    ) {
        OpenFileDialogView.Placement dialog = placement(tree);
        dialog.activate(focus);
        WindowPointerCapture capture = new WindowPointerCapture();
        AtomicReference<FileTreeView.State> adopted = new AtomicReference<>(tree.state());
        // Snapshot tests can start inside an existing gesture. Bind that fixture's
        // identity explicitly; end-to-end controller tests acquire it through press.
        if (tree.state().widgetState().drag() != null) {
            capture.observe("test-window", dialog.captureScope(), OpenFileDialogView.Control.LIST,
                InteractionResult.<FileTreeView.Action>handled().withPointerCapture(PointerCaptureRequest.ACQUIRE));
        }
        WindowPointerCapture.Frame frame = WindowPointerCapture.frame("test-window")
            .addScope(dialog.captureScope(), action -> {
                if (action instanceof FileTreeView.StateChanged changed) {
                    adopted.set(changed.state());
                } else {
                    throw new AssertionError("captured gesture activated an entry");
                }
            });
        boolean cancelled = capture.beforeInput(frame, event);
        if (event instanceof PointerEvent pointer && capture.dispatch(frame, pointer)) {
            return new OpenFileInputRouter.Result(adopted.get(), focus.focus(), null, true);
        }
        OpenFileInputRouter.Result result = OpenFileInputRouter.route(dialog, focus, event,
            nowNanos, doubleClickWindowNanos, capture, "test-window");
        // A fresh production frame is built after cancellation. These snapshot
        // fixtures keep their tree geometry and carry that one adopted state effect.
        return cancelled ? new OpenFileInputRouter.Result(result.state().cancelPointerInteraction(),
            result.focus(), result.intent(), result.consumed(), result.pointerCaptureRequest()) : result;
    }
}
