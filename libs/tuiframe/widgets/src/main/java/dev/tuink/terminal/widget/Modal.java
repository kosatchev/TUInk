package dev.tuink.terminal.widget;

import dev.tuink.terminal.cell.Cell;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.CellText;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.geometry.Size;
import dev.tuink.terminal.style.CellStyle;
import java.util.Objects;

/**
 * Reusable modal frame element with title and close button.
 */
public final class Modal implements Widget {
    private final int top;
    private final int left;
    private final int width;
    private final int height;
    private final String title;
    private final CellStyle bodyStyle;
    private final CellStyle borderStyle;
    private final CellStyle titleStyle;

    /** Creates a geometry-free modal model for the measure/layout contract. */
    public Modal(int preferredWidth, int preferredHeight, String title) {
        this(
            0,
            0,
            preferredWidth,
            preferredHeight,
            title,
            CellStyle.DEFAULT,
            CellStyle.DEFAULT,
            CellStyle.DEFAULT
        );
        if (preferredWidth < 0 || preferredHeight < 0) {
            throw new IllegalArgumentException("preferred width and height must not be negative");
        }
    }

    public Modal(
        int top,
        int left,
        int width,
        int height,
        String title,
        CellStyle bodyStyle,
        CellStyle borderStyle,
        CellStyle titleStyle
    ) {
        this.top = top;
        this.left = left;
        this.width = width;
        this.height = height;
        this.title = title;
        this.bodyStyle = bodyStyle;
        this.borderStyle = borderStyle;
        this.titleStyle = titleStyle;
    }

    public int right() {
        return left + width - 1;
    }

    public int bottom() {
        return top + height - 1;
    }

    public Button closeButton() {
        return Button.plain(top, right() - 2, "X");
    }

    public void draw(CellBuffer buffer, int rows, int cols) {
        fillRect(buffer, rows, cols, top, left, height, width, bodyStyle);
        drawLine(buffer, cols, top, left, "┌" + "─".repeat(Math.max(0, width - 2)) + "┐", borderStyle);
        drawLine(buffer, cols, bottom(), left, "└", borderStyle);
        drawLine(buffer, cols, bottom(), right(), "┘", borderStyle);
        drawLine(buffer, cols, bottom(), left + 1, "─".repeat(Math.max(0, width - 2)), borderStyle);
        for (int row = top + 1; row < bottom(); row++) {
            drawLine(buffer, cols, row, left, "│", borderStyle);
            drawLine(buffer, cols, row, right(), "│", borderStyle);
        }
        drawLine(buffer, cols, top, left + 2, title, titleStyle);
        closeButton().draw(buffer, cols, titleStyle);
    }

    /**
     * Draws inside the modal's local geometry and restores the caller's Canvas
     * state. The title is clipped before the close button so a wide-grapheme
     * footprint crossing that boundary cannot hide the button or overwrite
     * the right frame edge.
     */
    public void draw(Canvas canvas) {
        paint(
            Objects.requireNonNull(canvas, "canvas"),
            top,
            left,
            width,
            height,
            title,
            bodyStyle,
            borderStyle,
            titleStyle
        );
    }

    @Override
    public Size measure(Size available) {
        Objects.requireNonNull(available, "available");
        return new Size(
            Math.min(Math.max(0, width), available.width()),
            Math.min(Math.max(0, height), available.height())
        );
    }

    @Override
    public PlacedModal layout(Rect bounds) {
        return new PlacedModal(title, Objects.requireNonNull(bounds, "bounds"));
    }

    static void paint(
        Canvas canvas,
        int top,
        int left,
        int width,
        int height,
        String title,
        CellStyle bodyStyle,
        CellStyle borderStyle,
        CellStyle titleStyle
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }

        int drawRight = Math.addExact(left, width - 1);
        int drawBottom = Math.addExact(top, height - 1);

        int mark = canvas.save();
        try {
            if (!canvas.clip(left, top, width, height)) {
                return;
            }

            Cell horizontal = new Cell("─", borderStyle);
            Cell vertical = new Cell("│", borderStyle);
            Cell topLeft = new Cell("┌", borderStyle);
            Cell topRight = new Cell("┐", borderStyle);
            Cell bottomLeft = new Cell("└", borderStyle);
            Cell bottomRight = new Cell("┘", borderStyle);

            canvas.fill(left, top, width, height, bodyStyle);
            drawBorder(
                canvas,
                top,
                left,
                width,
                height,
                drawRight,
                drawBottom,
                horizontal,
                vertical,
                topLeft,
                topRight,
                bottomLeft,
                bottomRight
            );
            drawTitle(canvas, top, left, width, title, titleStyle);
            if (width >= 4) {
                Button.plain(top, drawRight - 2, "X").draw(canvas, titleStyle);
            }
        } finally {
            canvas.restore(mark);
        }
    }

    private static void drawBorder(
        Canvas canvas,
        int top,
        int left,
        int width,
        int height,
        int drawRight,
        int drawBottom,
        Cell horizontal,
        Cell vertical,
        Cell topLeft,
        Cell topRight,
        Cell bottomLeft,
        Cell bottomRight
    ) {
        canvas.put(left, top, topLeft);
        if (width > 2) {
            canvas.fillCell(left + 1, top, width - 2, 1, horizontal);
        }
        if (width > 1) {
            canvas.put(drawRight, top, topRight);
        }

        if (height > 1) {
            canvas.put(left, drawBottom, bottomLeft);
            if (width > 2) {
                canvas.fillCell(left + 1, drawBottom, width - 2, 1, horizontal);
            }
            if (width > 1) {
                canvas.put(drawRight, drawBottom, bottomRight);
            }
        }

        if (height > 2) {
            canvas.fillCell(left, top + 1, 1, height - 2, vertical);
            if (width > 1) {
                canvas.fillCell(drawRight, top + 1, 1, height - 2, vertical);
            }
        }
    }

    private static void drawTitle(
        Canvas canvas,
        int top,
        int left,
        int width,
        String title,
        CellStyle titleStyle
    ) {
        int titleWidth = width - 5;
        if (titleWidth <= 0 || title == null || title.isEmpty()) {
            return;
        }
        int titleLeft = left + 2;
        int mark = canvas.save();
        try {
            if (canvas.clip(titleLeft, top, titleWidth, 1)) {
                CellText.draw(canvas, titleLeft, top, title, titleStyle);
            }
        } finally {
            canvas.restore(mark);
        }
    }

    private static void fillRect(
        CellBuffer buffer,
        int rows,
        int cols,
        int top,
        int left,
        int height,
        int width,
        CellStyle style
    ) {
        for (int r = 0; r < height; r++) {
            int row = top + r;
            if (row < 0 || row >= rows) {
                continue;
            }
            for (int c = 0; c < width; c++) {
                int col = left + c;
                if (col < 0 || col >= cols) {
                    continue;
                }
                buffer.set(row, col, " ", style);
            }
        }
    }

    private static void drawLine(CellBuffer buffer, int cols, int row, int startCol, String text, CellStyle style) {
        CellText.draw(buffer, row, startCol, text, style, cols);
    }
}
