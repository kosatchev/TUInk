package dev.tuink.render;

import java.util.List;

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
    int contentRows,
    int topLine,
    int totalLines,
    int leftColumn,
    int totalColumns,
    int cols,
    int gutterWidth,
    int scrollbarWidth
) {
}
