package dev.tuink.render;

import dev.tuink.core.GutterDiffMarker;
import java.util.List;

/**
 * Render-ready visual data for a single visible editor row.
 *
 * @param sequence immutable source UTF-16 slice; display payloads may be sanitized without changing these offsets
 * @param sourceVisualColumn absolute cell column of the first UTF-16 offset in {@code sequence}
 * @param leadingClippedCells cells hidden between that offset and the viewport left edge
 * @param baseSpanCount number of leading spans that do not depend on cursor, search or selection state
 * @param baseStyleKey stable, opaque render-owned identity for the base span layout, or {@code null}
 * @param lineBreakSelectionStyle style of a selected real line break after this row's text,
 *                               or {@code null}; the line break occupies one blank display cell
 */
public record LineVisual(
    String gutter,
    CharSequence sequence,
    List<StyleSpan> spans,
    GutterDiffMarker gutterDiff,
    int sourceVisualColumn,
    int leadingClippedCells,
    int baseSpanCount,
    BaseStyleIdentity baseStyleKey,
    Style lineBreakSelectionStyle
) {
    public LineVisual(
        String gutter,
        CharSequence text,
        List<StyleSpan> spans,
        GutterDiffMarker gutterDiff,
        int sourceVisualColumn,
        int leadingClippedCells,
        int baseSpanCount,
        BaseStyleIdentity baseStyleKey
    ) {
        this(gutter, text, spans, gutterDiff, sourceVisualColumn, leadingClippedCells,
            baseSpanCount, baseStyleKey, null);
    }

    public LineVisual(String gutter, CharSequence text, List<StyleSpan> spans, GutterDiffMarker gutterDiff) {
        this(gutter, text, spans, gutterDiff, 0, 0, spans == null ? 0 : spans.size(), null);
    }

    public LineVisual(
        String gutter,
        CharSequence text,
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
        CharSequence text,
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

    /** Contiguous compatibility view; renderers consume {@link #sequence()} directly. */
    public String text() { return sequence == null ? null : sequence.toString(); }
}
