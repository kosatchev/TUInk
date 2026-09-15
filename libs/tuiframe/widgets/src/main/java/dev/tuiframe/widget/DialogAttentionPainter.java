package dev.tuiframe.widget;

import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellStyle;
import java.util.Objects;

/** Restyles dialog cells without changing their text or wide-grapheme footprints. */
public final class DialogAttentionPainter {
    private DialogAttentionPainter() {
    }

    /**
     * Applies the accent to an inline area or, when framed, its original perimeter.
     * Bounds are clipped to the buffer; partially covered wide graphemes remain
     * unchanged in both cells. The caller owns buffer access and supplies the style.
     */
    public static void paint(CellBuffer buffer, Rect bounds, boolean framed, CellStyle accent) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(accent, "accent");
        if (bounds.isEmpty()) {
            return;
        }
        int left = Math.max(0, bounds.x());
        int right = Math.min(buffer.cols(), bounds.rightExclusive());
        int top = Math.max(0, bounds.y());
        int bottom = Math.min(buffer.rows(), bounds.bottomExclusive());
        if (left >= right || top >= bottom) {
            return;
        }
        for (int row = top; row < bottom; row++) {
            if (!framed || bounds.width() <= 2
                || row == bounds.y() || row == bounds.bottomExclusive() - 1) {
                paintRange(buffer, row, left, right, accent);
            } else {
                // Clipping retains the original perimeter; it does not create a new border.
                if (bounds.x() >= left && bounds.x() < right) {
                    paintRange(buffer, row, bounds.x(), bounds.x() + 1, accent);
                }
                int lastColumn = bounds.rightExclusive() - 1;
                if (lastColumn >= left && lastColumn < right) {
                    paintRange(buffer, row, lastColumn, lastColumn + 1, accent);
                }
            }
        }
    }

    private static void paintRange(CellBuffer buffer, int row, int left, int right, CellStyle accent) {
        for (int col = left; col < right; col++) {
            Cell cell = buffer.get(row, col);
            if (cell.continuation() || cell.width() == 2 && col + 1 >= right) {
                continue;
            }
            // Replacing the lead cell also restyles its continuation atomically.
            if (!cell.style().equals(accent)) {
                buffer.set(row, col, cell.grapheme(), accent);
            }
            if (cell.width() == 2) {
                col++;
            }
        }
    }
}
