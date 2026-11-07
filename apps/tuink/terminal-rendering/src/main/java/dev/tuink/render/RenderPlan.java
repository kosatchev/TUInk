package dev.tuink.render;

import dev.tuiframe.scroll.ScrollLayout;
import dev.tuiframe.geometry.Rect;
import java.util.List;
import java.util.Objects;

/**
 * Full frame description independent from specific terminal APIs.
 */
public record RenderPlan(
    List<LineVisual> lines,
    String topBarText,
    String bottomBarText,
    String interactiveBarText,
    Style interactiveBarStyle,
    String notificationBarText,
    Style notificationBarStyle,
    int cursorRow,
    int cursorCol,
    boolean cursorInViewport,
    boolean wrapLines,
    boolean wrapByWords,
    int contentRows,
    int topLine,
    int totalLines,
    int leftColumn,
    int leftVisualColumn,
    int totalColumns,
    int cols,
    int gutterWidth,
    int scrollbarWidth,
    ScrollLayout scrollLayout,
    Rect editorBounds
) {
    public RenderPlan {
        Objects.requireNonNull(scrollLayout, "scrollLayout");
        Objects.requireNonNull(editorBounds, "editorBounds");
    }

    /** Compatibility constructor for a full-width editor surface. */
    public RenderPlan(
        List<LineVisual> lines, String topBarText, String bottomBarText,
        String interactiveBarText, Style interactiveBarStyle,
        String notificationBarText, Style notificationBarStyle,
        int cursorRow, int cursorCol, boolean cursorInViewport,
        boolean wrapLines, boolean wrapByWords, int contentRows,
        int topLine, int totalLines, int leftColumn, int leftVisualColumn, int totalColumns,
        int cols, int gutterWidth, int scrollbarWidth, ScrollLayout scrollLayout
    ) {
        this(lines, topBarText, bottomBarText, interactiveBarText, interactiveBarStyle,
            notificationBarText, notificationBarStyle, cursorRow, cursorCol, cursorInViewport,
            wrapLines, wrapByWords, contentRows, topLine, totalLines, leftColumn, leftVisualColumn,
            totalColumns, cols, gutterWidth, scrollbarWidth, scrollLayout,
            new Rect(0, scrollLayout.contentBounds().y(), Math.max(0, cols),
                Math.max(0, contentRows) + (scrollLayout.horizontalScrollbarBounds() == null ? 0 : 1)));
    }
}
