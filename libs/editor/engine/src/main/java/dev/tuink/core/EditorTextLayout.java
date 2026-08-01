package dev.tuink.core;

import java.util.List;

/**
 * Frontend-provided visual layout bound to exactly one text model.
 *
 * <p>The editor core owns this port but does not define a visual-width policy.
 * A terminal frontend can therefore use terminal cells while another frontend
 * can use pixels or another unit. Implementations must observe every completed
 * mutation of {@link #model()} before the next layout query.</p>
 *
 * <p>Line indices are clamped to the bound model. Text offsets are UTF-16
 * offsets, are clamped to their line and never split an extended grapheme
 * cluster. Non-positive tab widths are treated as one visual unit.</p>
 */
public interface EditorTextLayout {
    /** Returns the model instance to which this layout is permanently bound. */
    TextModel model();

    /** Returns the complete line width in frontend-defined visual units. */
    int visualWidth(int line, int tabWidth);

    /** Maps a UTF-16 text offset to its visual column from the line start. */
    int visualColumnAt(int line, int textOffset, int tabWidth);

    /**
     * Maps a visual column to a grapheme boundary, resolving columns inside a
     * multi-unit grapheme according to {@code bias}.
     */
    int textOffsetAtVisualColumn(int line, int visualColumn, int tabWidth, Bias bias);

    /**
     * Resolves the minimal text range covering a visual viewport.
     *
     * <p>The default implementation composes scalar queries. Implementations
     * backed by cached per-line metrics should override it to reuse one metrics
     * lookup.</p>
     */
    default VisualRange visualRange(
        int line,
        int leftVisualColumn,
        int maxVisualWidth,
        int tabWidth
    ) {
        int left = Math.max(0, leftVisualColumn);
        int start = textOffsetAtVisualColumn(line, left, tabWidth, Bias.FLOOR);
        int sourceVisual = visualColumnAt(line, start, tabWidth);
        int right = (int) Math.min(Integer.MAX_VALUE, (long) left + Math.max(0, maxVisualWidth));
        int end = textOffsetAtVisualColumn(line, right, tabWidth, Bias.CEIL);
        return new VisualRange(start, end, sourceVisual);
    }

    /**
     * Returns immutable, ascending, grapheme-safe row starts. The result is
     * never empty and its first offset is always zero.
     */
    List<Integer> wrapStarts(int line, int width, boolean wrapByWords, int tabWidth);

    /** Returns the sum of wrapped row counts before the exclusive line index. */
    int wrappedRowsBefore(int lineIndexExclusive, int width, boolean wrapByWords, int tabWidth);

    /** Must equal {@code wrappedRowsBefore(model().lineCount(), ...)}. */
    int totalWrappedRows(int width, boolean wrapByWords, int tabWidth);

    /** Returns the line containing the clamped wrapped-row offset. */
    int lineIndexFromWrappedOffset(int wrappedOffset, int width, boolean wrapByWords, int tabWidth);

    /** Returns the maximum {@link #visualWidth(int, int)} across the model. */
    int maxVisualLineWidth(int tabWidth);

    record VisualRange(int startOffset, int endOffset, int sourceVisualColumn) {
        public VisualRange {
            startOffset = Math.max(0, startOffset);
            endOffset = Math.max(startOffset, endOffset);
            sourceVisualColumn = Math.max(0, sourceVisualColumn);
        }
    }

    enum Bias {
        FLOOR,
        NEAREST,
        CEIL
    }

    @FunctionalInterface
    interface Factory {
        /** Creates the single layout instance shared by one editor session. */
        EditorTextLayout create(TextModel model);
    }
}
