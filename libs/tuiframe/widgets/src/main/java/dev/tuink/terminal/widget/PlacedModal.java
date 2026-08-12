package dev.tuink.terminal.widget;

import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Rect;
import java.util.Objects;

/** Final modal placement, including hit geometry for its built-in close cell. */
public final class PlacedModal implements PlacedWidget {
    private final String title;
    private final Rect bounds;

    PlacedModal(String title, Rect bounds) {
        this.title = title;
        this.bounds = Objects.requireNonNull(bounds, "bounds");
    }

    @Override
    public Rect bounds() {
        return bounds;
    }

    @Override
    public void paint(Canvas canvas, Theme theme, WidgetState state) {
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(state, "state");
        Modal.paint(
            Objects.requireNonNull(canvas, "canvas"),
            bounds.y(),
            bounds.x(),
            bounds.width(),
            bounds.height(),
            title,
            theme.resolveStyle(WidgetThemeKeys.MODAL_BODY),
            theme.resolveStyle(WidgetThemeKeys.MODAL_BORDER),
            theme.resolveStyle(WidgetThemeKeys.MODAL_TITLE)
        );
    }

    /** Tests the visible close cell using parent-local row/column coordinates. */
    public boolean closeHit(int row, int column) {
        return !bounds.isEmpty()
            && bounds.width() >= 4
            && row == bounds.y()
            && column == bounds.rightExclusive() - 3;
    }
}
