package dev.tuink.render;

import dev.tuink.core.GutterDiffMarker;
import java.util.List;

/**
 * Render-ready visual data for a single visible editor row.
 *
 * @param sourceVisualColumn absolute cell column of the first UTF-16 offset in {@code text}
 * @param leadingClippedCells cells hidden between that offset and the viewport left edge
 */
public record LineVisual(
    String gutter,
    String text,
    List<StyleSpan> spans,
    GutterDiffMarker gutterDiff,
    int sourceVisualColumn,
    int leadingClippedCells
) {
    public LineVisual(String gutter, String text, List<StyleSpan> spans, GutterDiffMarker gutterDiff) {
        this(gutter, text, spans, gutterDiff, 0, 0);
    }

    public LineVisual {
        sourceVisualColumn = Math.max(0, sourceVisualColumn);
        leadingClippedCells = Math.max(0, leadingClippedCells);
    }
}
