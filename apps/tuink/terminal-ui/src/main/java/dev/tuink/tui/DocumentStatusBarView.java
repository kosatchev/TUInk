package dev.tuink.tui;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;

/** Footer text and complete-label hit regions are prepared together. */
final class DocumentStatusBarView {
    private DocumentStatusBarView() {}

    static Placement place(String left, String access, String encoding, String endings, boolean reading,
                           int columns, int row) {
        int width = Math.max(0, columns);
        String enc = "ENC:" + encoding;
        String eol = "EOL:" + endings;
        String prefix = access + " | ";
        String right = prefix + enc + " | " + eol + " | " + (reading ? "READ" : "INS") + " ";
        int leftWidth = CellText.width(left);
        if (leftWidth >= width) return new Placement(CellText.clip(left, 0, width), empty(row), empty(row));
        int rightWidth = CellText.width(right);
        int start = rightWidth >= width ? 0 : Math.max(leftWidth, width - rightWidth);
        int visible = width - start;
        String text = (start == 0 ? "" : left + " ".repeat(start - leftWidth)) + CellText.clip(right, 0, visible);
        int encodingStart = start + CellText.width(prefix);
        int eolStart = encodingStart + CellText.width(enc) + 3;
        Rect encBounds = encodingStart + CellText.width(enc) <= width
            ? new Rect(encodingStart, row, CellText.width(enc), 1) : empty(row);
        Rect eolBounds = eolStart + CellText.width(eol) <= width
            ? new Rect(eolStart, row, CellText.width(eol), 1) : empty(row);
        return new Placement(text, encBounds, eolBounds);
    }

    private static Rect empty(int row) { return new Rect(0, row, 0, 0); }
    record Placement(String text, Rect encoding, Rect endings) {}
}
