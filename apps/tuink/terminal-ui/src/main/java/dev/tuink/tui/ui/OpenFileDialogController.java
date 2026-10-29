package dev.tuink.tui.ui;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.Button;

/** Geometry-only composition helpers for TUInk's open-file dialog. */
public final class OpenFileDialogController {
    private OpenFileDialogController() {
    }

    public record OpenDialogLayout(
        int top,
        int left,
        int width,
        int height,
        int listStartRow,
        int listRows
    ) {
        public int right() {
            return left + width - 1;
        }

        public int bottom() {
            return top + height - 1;
        }
    }

    public record OpenDialogButtons(Button ok, Button cancel) {
    }

    public static OpenDialogLayout layoutForTerminal(int rows, int cols) {
        return layoutForTerminal(rows, cols, 0);
    }

    /** Centers the dialog below application chrome retained for tab navigation. */
    public static OpenDialogLayout layoutForTerminal(int rows, int cols, int topInset) {
        int terminalRows = Math.max(0, rows);
        int inset = Math.clamp(topInset, 0, terminalRows);
        Rect viewport = new Rect(0, inset, Math.max(0, cols), terminalRows - inset);
        int width = Math.min(viewport.width(),
            Math.max(24, Math.min(viewport.width() - 4, (int) Math.round(viewport.width() * 0.8))));
        int height = Math.min(viewport.height(),
            Math.max(8, Math.min(viewport.height() - 2, (int) Math.round(viewport.height() * 0.75))));
        int left = viewport.x() + Math.max(0, (viewport.width() - width) / 2);
        int top = viewport.y() + Math.max(0, (viewport.height() - height) / 2);
        int listStartRow = top + Math.min(2, height);
        // Keep a stable row above the buttons for local open/retry feedback.
        int listRows = Math.max(0, height - 4);
        return new OpenDialogLayout(top, left, width, height, listStartRow, listRows);
    }

    public static OpenDialogButtons buttonsForLayout(OpenDialogLayout layout) {
        String ok = "  OK  ";
        String cancel = " Cancel ";
        int total = (ok.length() + 2) + 2 + (cancel.length() + 2);
        int start = layout.left() + Math.max(1, (layout.width() - 2 - total) / 2);
        int row = layout.top() + Math.max(0, layout.height() - 1);
        Button okButton = new Button(row, start, ok);
        Button cancelButton = new Button(row, start + okButton.width() + 2, cancel);
        return new OpenDialogButtons(okButton, cancelButton);
    }
}
