package dev.tuink.tui.ui;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.scroll.ScrollLayout;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuink.render.RenderPlan;

/** Maps the editor's render plan onto the common scrollbar geometry. */
public record EditorScrollbars(ScrollLayout layout, Scrollbar vertical, Scrollbar horizontal) {
    public static EditorScrollbars from(RenderPlan plan, int rows, int cols, int contentTopRow) {
        int top = Math.max(1, contentTopRow);
        int contentLeft = Math.min(Math.max(0, cols), Math.max(0, plan.gutterWidth()));
        int contentWidth = Math.max(0, cols - plan.gutterWidth() - plan.scrollbarWidth());
        int contentHeight = Math.min(Math.max(0, plan.contentRows()), Math.max(0, rows - top - 1));
        Rect content = new Rect(contentLeft, top, contentWidth, contentHeight);

        Rect verticalTrack = plan.scrollbarWidth() > 0 && cols > 0 && rows > 2 && contentHeight >= 2
            ? new Rect(cols - 1, top, 1, contentHeight)
            : null;
        int horizontalRow = top + Math.max(0, plan.contentRows());
        boolean hasHorizontal = !plan.wrapLines()
            && rows > 2
            && contentHeight > 0
            && contentWidth >= 2
            && (plan.totalColumns() > contentWidth || plan.leftVisualColumn() > 0)
            && horizontalRow >= top
            && horizontalRow < rows - 1;
        Rect horizontalTrack = hasHorizontal
            ? new Rect(contentLeft, horizontalRow, contentWidth, 1)
            : null;

        ScrollLayout layout = new ScrollLayout(content, content, null, verticalTrack, horizontalTrack);
        Scrollbar vertical = verticalTrack == null ? null : Scrollbar.vertical(
            verticalTrack,
            Math.max(1, plan.totalLines()),
            Math.max(0, plan.topLine())
        );
        Scrollbar horizontal = horizontalTrack == null ? null : Scrollbar.horizontal(
            horizontalTrack,
            Math.max(contentWidth, plan.totalColumns()),
            Math.max(0, plan.leftVisualColumn())
        );
        return new EditorScrollbars(layout, vertical, horizontal);
    }
}
