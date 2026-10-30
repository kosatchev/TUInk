package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.WidgetState;
import org.junit.jupiter.api.Test;

class CompactDialogViewTest {
    @Test
    void everySmallViewportKeepsItsPaintedCloseControlInsideTheVisibleFrame() {
        for (int rows = 2; rows <= 7; rows++) {
            for (int columns = 1; columns <= 80; columns++) {
                Rect viewport = new Rect(0, 0, columns, rows);
                var compact = CompactDialogView.place(viewport, "Open file");
                var close = compact.closeButton();
                assertTrue(viewport.contains(compact.bounds()));
                assertTrue(compact.bounds().contains(CompactDialogView.closeBounds(close)));
                CellBuffer buffer = new CellBuffer(rows, columns);
                compact.paint(new Canvas(buffer), role -> CellStyle.DEFAULT, WidgetState.FOCUSED);
                String visible = buffer.get(close.row(), close.cursorCol()).grapheme();
                assertEquals(close.text().contains("Esc") ? "E" : "X", visible,
                    "close control disappeared in " + columns + "x" + rows);
            }
        }
    }
}
