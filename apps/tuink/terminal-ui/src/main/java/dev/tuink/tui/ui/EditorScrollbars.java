package dev.tuink.tui.ui;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.scroll.ScrollLayout;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuink.render.RenderPlan;

/** Maps the editor's render plan onto the common scrollbar geometry. */
public record EditorScrollbars(ScrollLayout layout, Scrollbar vertical, Scrollbar horizontal) {
    public static EditorScrollbars from(RenderPlan plan, int rows, int cols, int contentTopRow) {
        int top = Math.max(1, contentTopRow);
        Rect surface = plan.editorBounds().intersection(
            new Rect(0, top, Math.max(0, cols), Math.max(0, rows - top - 1)));
        ScrollLayout planned = plan.scrollLayout();
        Rect content = planned.contentBounds().intersection(surface);
        // Placements belong to one frame. A resized surface may hide a whole
        // track, but must not negotiate a different viewport from this plan.
        Rect verticalTrack = visibleTrack(planned.verticalScrollbarBounds(), surface);
        Rect horizontalTrack = visibleTrack(planned.horizontalScrollbarBounds(), surface);
        ScrollLayout layout = content.equals(planned.contentBounds())
            && verticalTrack == planned.verticalScrollbarBounds()
            && horizontalTrack == planned.horizontalScrollbarBounds()
            ? planned
            : new ScrollLayout(content, content, null, verticalTrack, horizontalTrack);
        Scrollbar vertical = verticalTrack == null ? null : Scrollbar.vertical(
            verticalTrack,
            Math.max(1, plan.totalLines()),
            Math.max(0, plan.topLine())
        );
        Scrollbar horizontal = horizontalTrack == null ? null : Scrollbar.horizontal(
            horizontalTrack,
            Math.max(content.width(), plan.totalColumns()),
            Math.max(0, plan.leftVisualColumn())
        );
        return new EditorScrollbars(layout, vertical, horizontal);
    }

    private static Rect visibleTrack(Rect track, Rect surface) {
        return track != null && surface.contains(track) ? track : null;
    }
}
