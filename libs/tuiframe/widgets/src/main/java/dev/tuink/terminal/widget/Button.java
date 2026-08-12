package dev.tuink.terminal.widget;

import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.CellText;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.geometry.Size;
import dev.tuink.terminal.text.TerminalText;
import dev.tuink.terminal.style.CellStyle;
import java.util.Objects;

/**
 * Reusable single-line text button for TUI overlays.
 */
public final class Button implements Widget {
    private static final int TAB_WIDTH = 4;

    private final int row;
    private final int col;
    private final String text;
    private final boolean framed;
    private final String renderedText;
    private final int renderedWidth;
    private final int cursorOffset;

    public Button(String text) {
        this(0, 0, text, true);
    }

    public Button(int row, int col, String text) {
        this(row, col, text, true);
    }

    private Button(int row, int col, String text, boolean framed) {
        this.row = row;
        this.col = col;
        this.text = text;
        this.framed = framed;
        this.renderedText = framed ? "[" + text + "]" : text;
        TerminalText.Metrics metrics = TerminalText.metrics(renderedText, TAB_WIDTH);
        this.renderedWidth = metrics.visualWidth();
        this.cursorOffset = resolveCursorOffset(metrics, framed);
    }

    public static Button plain(int row, int col, String text) {
        return new Button(row, col, text, false);
    }

    public static Button plain(String text) {
        return new Button(0, 0, text, false);
    }

    public int row() {
        return row;
    }

    public int col() {
        return col;
    }

    public String text() {
        return text;
    }

    public int cursorCol() {
        return col + cursorOffset;
    }

    public int width() {
        return renderedWidth;
    }

    public boolean hit(int hitRow, int hitCol) {
        return hitRow == row && hitCol >= col && hitCol < col + width();
    }

    public void draw(CellBuffer buffer, int cols, CellStyle style) {
        drawAt(buffer, row, cols, style);
    }

    public void drawAt(CellBuffer buffer, int drawRow, int cols, CellStyle style) {
        CellText.draw(buffer, drawRow, col, renderedText(), style, cols);
    }

    /** Draws at the button's local Canvas coordinates with strict clipping. */
    public void draw(Canvas canvas, CellStyle style) {
        drawAt(canvas, row, style);
    }

    /** Draws on an alternate local row without changing hit-test geometry. */
    public void drawAt(Canvas canvas, int drawRow, CellStyle style) {
        Objects.requireNonNull(canvas, "canvas");
        if (renderedWidth <= 0) {
            return;
        }
        int mark = canvas.save();
        try {
            if (!canvas.clip(col, drawRow, renderedWidth, 1)) {
                return;
            }
            CellText.draw(canvas, col, drawRow, renderedText, style);
        } finally {
            canvas.restore(mark);
        }
    }

    public String renderedText() {
        return renderedText;
    }

    @Override
    public Size measure(Size available) {
        Objects.requireNonNull(available, "available");
        return new Size(
            Math.min(renderedWidth, available.width()),
            Math.min(1, available.height())
        );
    }

    @Override
    public PlacedWidget layout(Rect bounds) {
        return new Placement(this, Objects.requireNonNull(bounds, "bounds"));
    }

    private static int resolveCursorOffset(TerminalText.Metrics metrics, boolean framed) {
        for (int index = 0; index < metrics.graphemeCount(); index++) {
            int codePoint = metrics.text().codePointAt(metrics.start(index));
            if (Character.isLetterOrDigit(codePoint)) {
                return metrics.visualColumnAtBoundary(index);
            }
        }
        return framed ? 1 : 0;
    }

    private record Placement(Button button, Rect bounds) implements PlacedWidget {
        private Placement {
            Objects.requireNonNull(button, "button");
            Objects.requireNonNull(bounds, "bounds");
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState state) {
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(state, "state");
            WidgetStyleRole role = !state.enabled()
                ? WidgetThemeKeys.BUTTON_DISABLED
                : state.focused() ? WidgetThemeKeys.BUTTON_FOCUSED : WidgetThemeKeys.BUTTON;
            paintText(canvas, bounds, button.renderedText, theme.resolveStyle(role));
        }
    }

    private static void paintText(Canvas canvas, Rect bounds, String text, CellStyle style) {
        Objects.requireNonNull(canvas, "canvas");
        if (bounds.isEmpty()) {
            return;
        }
        int mark = canvas.save();
        try {
            if (!canvas.clip(bounds)) {
                return;
            }
            CellText.draw(canvas, bounds.x(), bounds.y(), text, style);
        } finally {
            canvas.restore(mark);
        }
    }
}
