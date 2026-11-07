package dev.tuink.tui;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.scroll.ScrollLayout;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuink.render.markdown.MarkdownLayout;

/** Exact-frame Markdown viewport negotiated through the same scrollbar layout as Source. */
record PreviewScrollbars(ScrollLayout layout, Scrollbar vertical, Scrollbar horizontal) {
    static PreviewScrollbars from(Rect bounds, MarkdownLayout.Result result, int top, int left) {
        int rows = result == null ? 0 : result.lines().size();
        int columns = result == null ? 0 : result.extent();
        ScrollLayout layout = ScrollLayout.calculate(bounds, rows, columns, 0);
        Scrollbar vertical = layout.verticalScrollbarBounds() == null ? null
            : Scrollbar.vertical(layout.verticalScrollbarBounds(), rows, top);
        Scrollbar horizontal = layout.horizontalScrollbarBounds() == null ? null
            : Scrollbar.horizontal(layout.horizontalScrollbarBounds(), Math.max(layout.contentBounds().width(), columns), left);
        return new PreviewScrollbars(layout, vertical, horizontal);
    }
}
