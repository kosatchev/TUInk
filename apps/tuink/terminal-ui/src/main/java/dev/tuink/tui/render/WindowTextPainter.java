package dev.tuink.tui.render;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.SurfaceContext;
import dev.tuiframe.widget.Theme;
import dev.tuink.render.Style;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.ui.NotificationPresentation;
import java.util.Objects;

/** Shared window text around any content adapter; uses the widget Unicode/clipping rules. */
public final class WindowTextPainter {
    private WindowTextPainter() {}

    public record Text(String top, String status, String prompt, Style promptStyle,
                       String notification, Style notificationStyle) {}

    /** Prepared child surfaces; the same bounds also place controls and their cursor. */
    public record Layout(SurfaceContext status, SurfaceContext prompt, SurfaceContext notification) {
        public Layout { Objects.requireNonNull(status); Objects.requireNonNull(prompt); Objects.requireNonNull(notification); }
        public static Layout fullWindow(int rows, int columns, int promptHeight, int notificationHeight) {
            var window = SurfaceContext.root(new Rect(0, 0, columns, rows), Theme.fixed(CellStyle.DEFAULT));
            int top = rows - 1 - promptHeight;
            return new Layout(window.child(new Rect(0, rows - 1, columns, 1)),
                window.child(new Rect(0, top, columns, promptHeight)),
                window.child(new Rect(0, top - notificationHeight, columns, notificationHeight)));
        }
    }

    public static void paint(Canvas canvas, int rows, int columns, Text text, UiStyles.Palette palette) {
        Objects.requireNonNull(canvas); Objects.requireNonNull(text); Objects.requireNonNull(palette);
        if (rows < 1 || columns < 1) return;
        int promptHeight = text.prompt() == null ? 0 : text.prompt().split("\n", -1).length;
        paint(canvas, rows, columns, text, palette,
            Layout.fullWindow(rows, columns, promptHeight, text.notification() == null ? 0 : 1));
    }

    public static void paint(Canvas canvas, int rows, int columns, Text text, UiStyles.Palette palette, Layout layout) {
        Objects.requireNonNull(canvas); Objects.requireNonNull(text); Objects.requireNonNull(palette);
        if (rows < 1 || columns < 1) return;
        line(canvas, 0, columns, text.top(), palette.windows().renderTopbar());
        regionLine(canvas, layout.status(), 0, text.status(), palette.windows().renderStatus());
        if (text.prompt() != null) {
            String[] lines = text.prompt().split("\n", -1);
            CellStyle style = text.promptStyle() == null ? palette.windows().renderInteractive()
                : EditorStyleAdapter.toCell(text.promptStyle());
            for (int index = 0; index < lines.length; index++) {
                regionLine(canvas, layout.prompt(), index, lines[index], style);
            }
        }
        if (text.notification() != null) {
            CellStyle style = text.notificationStyle() == null ? palette.windows().renderNotification()
                : EditorStyleAdapter.toCell(text.notificationStyle());
            regionLine(canvas, layout.notification(), 0,
                NotificationPresentation.fit(text.notification(), layout.notification().bounds().width()), style);
        }
    }

    private static void regionLine(Canvas canvas, SurfaceContext region, int offset, String text, CellStyle style) {
        Rect bounds = region.bounds();
        if (offset >= bounds.height() || region.clip().isEmpty()) return;
        int mark = canvas.save();
        try {
            canvas.clip(region.clip());
            CellText.drawClipped(canvas, bounds.x(), bounds.y() + offset,
                CellText.fit(text, bounds.width()), style, bounds.width());
        } finally { canvas.restore(mark); }
    }

    private static void line(Canvas canvas, int row, int columns, String text, CellStyle style) {
        CellText.drawClipped(canvas, 0, row, CellText.fit(text, columns), style, columns);
    }
}
