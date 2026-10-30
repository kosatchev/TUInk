package dev.tuink.tui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.Modal;
import dev.tuiframe.widget.PlacedModal;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;

/** TUInk's visible fallback when an exclusive help/file dialog cannot fit. */
final class CompactDialogView {
    private CompactDialogView() {
    }

    static boolean required(int rows, int columns) {
        return rows < 8 || columns < 24;
    }

    static Placement place(Rect viewport, String title) {
        int width = Math.min(32, viewport.width());
        int height = Math.min(5, viewport.height());
        Rect bounds = new Rect(viewport.x() + (viewport.width() - width) / 2,
            viewport.y() + (viewport.height() - height) / 2, width, height);
        if (bounds.isEmpty()) {
            return new Placement(new Modal(width, height, title).layout(bounds),
                Button.plain(bounds.y(), bounds.x(), ""));
        }
        Button label = width >= 16 ? new Button(" Esc: close ")
            : width >= 5 ? Button.plain("Esc") : Button.plain("X");
        Button close = width >= 16
            ? new Button(bounds.bottomExclusive() - 1,
                bounds.x() + (width - label.width()) / 2, label.text())
            : Button.plain(bounds.bottomExclusive() - 1,
                bounds.x() + Math.max(0, (width - label.width()) / 2), label.text());
        return new Placement(new Modal(width, height, title).layout(bounds), close);
    }

    static void paint(Canvas canvas, PlacedModal frame, Button close, Theme theme, WidgetState closeState) {
        if (frame.bounds().isEmpty()) return;
        frame.paint(canvas, theme, WidgetState.NORMAL);
        Rect bounds = frame.bounds();
        int columns = Math.max(0, bounds.width() - 2);
        var style = theme.resolveStyle(WidgetThemeKeys.MODAL_BODY);
        if (bounds.height() >= 3) {
            CellText.drawClipped(canvas, bounds.x() + 1, bounds.y() + 1,
                "Enlarge terminal", style, columns);
        }
        if (bounds.height() >= 4) {
            CellText.drawClipped(canvas, bounds.x() + 1, bounds.y() + 2,
                "Minimum: 24x8", style, columns);
        }
        close.layout(closeBounds(close)).paint(canvas, theme, closeState);
    }

    static Rect closeBounds(Button close) {
        return new Rect(close.col(), close.row(), close.width(), 1);
    }

    record Placement(PlacedModal frame, Button closeButton) {
        Rect bounds() {
            return frame.bounds();
        }

        void paint(Canvas canvas, Theme theme, WidgetState closeState) {
            CompactDialogView.paint(canvas, frame, closeButton, theme, closeState);
        }
    }
}
