package dev.tuink.render;

import dev.tuink.core.GutterDiffMarker;
import java.util.List;

/**
 * Render-ready visual data for a single visible editor row.
 *
 * @param sourceVisualColumn absolute cell column of the first UTF-16 offset in {@code text}
 * @param leadingClippedCells cells hidden between that offset and the viewport left edge
 * @param baseSpanCount number of leading spans that do not depend on cursor, search or selection state
 * @param baseStyleKey stable builder-owned identity for the base span layout, or {@code null}
 */
public record LineVisual(
    String gutter,
    String text,
    List<StyleSpan> spans,
    GutterDiffMarker gutterDiff,
    int sourceVisualColumn,
    int leadingClippedCells,
    int baseSpanCount,
    Object baseStyleKey
) {
    public LineVisual(String gutter, String text, List<StyleSpan> spans, GutterDiffMarker gutterDiff) {
        this(gutter, text, spans, gutterDiff, 0, 0, spans == null ? 0 : spans.size(), null);
    }

    public LineVisual(
        String gutter,
        String text,
        List<StyleSpan> spans,
        GutterDiffMarker gutterDiff,
        int sourceVisualColumn,
        int leadingClippedCells
    ) {
        this(
            gutter,
            text,
            spans,
            gutterDiff,
            sourceVisualColumn,
            leadingClippedCells,
            spans == null ? 0 : spans.size(),
            null
        );
    }

    public LineVisual(
        String gutter,
        String text,
        List<StyleSpan> spans,
        GutterDiffMarker gutterDiff,
        int sourceVisualColumn,
        int leadingClippedCells,
        int baseSpanCount
    ) {
        this(
            gutter,
            text,
            spans,
            gutterDiff,
            sourceVisualColumn,
            leadingClippedCells,
            baseSpanCount,
            null
        );
    }

    public LineVisual {
        sourceVisualColumn = Math.max(0, sourceVisualColumn);
        leadingClippedCells = Math.max(0, leadingClippedCells);
        baseSpanCount = Math.max(0, Math.min(baseSpanCount, spans == null ? 0 : spans.size()));
    }
}
