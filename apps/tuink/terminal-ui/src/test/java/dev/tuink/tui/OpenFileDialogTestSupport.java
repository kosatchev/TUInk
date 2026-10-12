package dev.tuink.tui;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuink.tui.ui.FileTreeView;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;

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
        return OpenFileInputRouter.route(placement(tree), focus, event, nowNanos, doubleClickWindowNanos);
    }
}
