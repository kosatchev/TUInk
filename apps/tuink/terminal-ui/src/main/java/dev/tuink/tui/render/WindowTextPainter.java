package dev.tuink.tui.render;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.style.CellStyle;
import dev.tuink.render.Style;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.ui.NotificationPresentation;
import java.util.Objects;

/** Shared window text around any content adapter; uses the widget Unicode/clipping rules. */
public final class WindowTextPainter {
    private WindowTextPainter() {}

    public record Text(String top, String status, String prompt, Style promptStyle,
                       String notification, Style notificationStyle) {}

    public static void paint(Canvas canvas, int rows, int columns, Text text, UiStyles.Palette palette) {
        Objects.requireNonNull(canvas); Objects.requireNonNull(text); Objects.requireNonNull(palette);
        if (rows < 1 || columns < 1) return;
        line(canvas, 0, columns, text.top(), palette.renderTopbar());
        line(canvas, rows - 1, columns, text.status(), palette.renderStatus());
        int promptTop = rows - 1;
        if (text.prompt() != null) {
            String[] lines = text.prompt().split("\n", -1);
            promptTop -= lines.length;
            CellStyle style = text.promptStyle() == null ? palette.renderInteractive()
                : EditorStyleAdapter.toCell(text.promptStyle());
            for (int index = 0; index < lines.length; index++) {
                int row = promptTop + index;
                if (row >= 0 && row < rows - 1) line(canvas, row, columns, lines[index], style);
            }
        }
        if (text.notification() != null && promptTop > 0) {
            CellStyle style = text.notificationStyle() == null ? palette.renderNotification()
                : EditorStyleAdapter.toCell(text.notificationStyle());
            line(canvas, promptTop - 1, columns, NotificationPresentation.fit(text.notification(), columns), style);
        }
    }

    private static void line(Canvas canvas, int row, int columns, String text, CellStyle style) {
        CellText.drawClipped(canvas, 0, row, CellText.fit(text, columns), style, columns);
    }
}
