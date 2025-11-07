package dev.tuink.render;

import java.util.List;

/**
 * Full frame description independent from specific terminal APIs.
 */
public record RenderPlan(
    List<LineVisual> lines,
    String statusText,
    int cursorRow,
    int cursorCol,
    int contentRows,
    int cols,
    int gutterWidth
) {
}
