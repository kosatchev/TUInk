package dev.tuiframe.widget;

import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.style.CellStyle;
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
}
