package dev.tuink.tui.ui;

import java.io.PrintWriter;

public final class TerminalCursorController {
    private boolean visible = true;
    private int row = -1;
    private int col = -1;

    public ApplyResult apply(
        PrintWriter writer,
        String frameDiff,
        int desiredRow,
        int desiredCol,
        boolean desiredVisible
    ) {
        boolean cursorMoved = desiredRow != row || desiredCol != col;
        boolean visibilityChanged = desiredVisible != visible;
        if ((frameDiff == null || frameDiff.isEmpty()) && !cursorMoved && !visibilityChanged) {
            return new ApplyResult(row, col, visible, false);
        }

        if (frameDiff != null && !frameDiff.isEmpty()) {
            writer.print(frameDiff);
        }
        if (visibilityChanged) {
            writer.print(desiredVisible ? "\u001B[?25h" : "\u001B[?25l");
        }
        if (desiredVisible) {
            writer.print("\u001B[" + desiredRow + ";" + desiredCol + "H");
        }
        writer.flush();

        row = desiredRow;
        col = desiredCol;
        visible = desiredVisible;
        return new ApplyResult(row, col, visible, true);
    }

    public record ApplyResult(int row, int col, boolean visible, boolean updated) {
    }
}
