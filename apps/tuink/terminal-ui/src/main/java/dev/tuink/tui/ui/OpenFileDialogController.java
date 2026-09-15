package dev.tuink.tui.ui;

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
        int width = Math.max(24, Math.min(cols - 4, (int) Math.round(cols * 0.8)));
        int height = Math.max(8, Math.min(rows - 2, (int) Math.round(rows * 0.75)));
        int left = Math.max(0, (cols - width) / 2);
        int top = Math.max(0, (rows - height) / 2);
        int listStartRow = top + 2;
        int listRows = Math.max(1, height - 3);
        return new OpenDialogLayout(top, left, width, height, listStartRow, listRows);
    }

    public static OpenDialogButtons buttonsForLayout(OpenDialogLayout layout) {
        String ok = "  OK  ";
        String cancel = " Cancel ";
        int total = (ok.length() + 2) + 2 + (cancel.length() + 2);
        int start = layout.left() + Math.max(1, (layout.width() - 2 - total) / 2);
        Button okButton = new Button(layout.bottom(), start, ok);
        Button cancelButton = new Button(layout.bottom(), start + okButton.width() + 2, cancel);
        return new OpenDialogButtons(okButton, cancelButton);
    }
}
