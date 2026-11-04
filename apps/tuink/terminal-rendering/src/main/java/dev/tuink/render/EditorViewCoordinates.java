package dev.tuink.render;

import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.render.surface.TextSurfaceGeometry;
import dev.tuink.render.surface.TextSurfaceInteraction;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Document coordinates for one editor geometry, shared by rendering, pointer hits and resize anchors.
 * The bound layout owns all metrics and wrap caches; this object holds no derived text cache.
 */
public final class EditorViewCoordinates implements TextSurfaceGeometry<Position> {
    private final EditorTextLayout layout;
    private final TextModel model;
    private final Geometry geometry;

    public EditorViewCoordinates(EditorTextLayout layout, Geometry geometry) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.model = Objects.requireNonNull(layout.model(), "layout.model");
        this.geometry = Objects.requireNonNull(geometry, "geometry");
    }

    public EditorViewCoordinates(
        EditorTextLayout layout, int columns, boolean wrapLines, boolean wrapByWords, int tabWidth
    ) {
        this(layout, new Geometry(columns, wrapLines, wrapByWords, tabWidth));
    }

    public Top normalizeTop(Top top) {
        Objects.requireNonNull(top, "top");
        if (!geometry.wrapLines()) {
            return new Top(Math.min(top.line(), model.lineCount() - 1), 0);
        }
        int line = Math.min(top.line(), model.lineCount() - 1);
        int segment = top.segment();
        while (line < model.lineCount()) {
            int rows = starts(line).size();
            if (segment < rows) {
                return new Top(line, segment);
            }
            segment -= rows;
            line++;
        }
        return new Top(model.lineCount() - 1, 0);
    }

    public int rowOffset(Top top) {
        Top bounded = normalizeTop(top);
        return geometry.wrapLines()
            ? layout.wrappedRowsBefore(bounded.line(), geometry.columns(), geometry.wrapByWords(), geometry.tabWidth())
                + bounded.segment()
            : bounded.line();
    }

    public Top topAtRow(int row) {
        int bounded = Math.clamp(row, 0, Math.max(0, totalRows() - 1));
        if (!geometry.wrapLines()) {
            return new Top(bounded, 0);
        }
        int line = layout.lineIndexFromWrappedOffset(bounded, geometry.columns(), geometry.wrapByWords(), geometry.tabWidth());
        int before = layout.wrappedRowsBefore(line, geometry.columns(), geometry.wrapByWords(), geometry.tabWidth());
        return new Top(line, bounded - before);
    }

    public int totalRows() {
        return geometry.wrapLines()
            ? layout.totalWrappedRows(geometry.columns(), geometry.wrapByWords(), geometry.tabWidth())
            : model.lineCount();
    }

    /** The row owning an insertion offset; shared wrap boundaries belong to the following row. */
    public Top topForAnchor(Position anchor) {
        Position bounded = model.clamp(Objects.requireNonNull(anchor, "anchor"));
        if (!geometry.wrapLines()) {
            return new Top(bounded.line(), 0);
        }
        List<Integer> starts = starts(bounded.line());
        int index = Collections.binarySearch(starts, bounded.col());
        return new Top(bounded.line(), index >= 0 ? index : Math.max(0, -index - 2));
    }

    public Position anchor(Top top) {
        Top bounded = normalizeTop(top);
        return new Position(bounded.line(), geometry.wrapLines() ? starts(bounded.line()).get(bounded.segment()) : 0);
    }

    /** Converts a document insertion offset to its absolute visual row and row-relative column. */
    public VisualPosition toVisual(Position position) {
        Position bounded = model.clamp(Objects.requireNonNull(position, "position"));
        Top top = topForAnchor(bounded);
        int start = geometry.wrapLines() ? starts(top.line()).get(top.segment()) : 0;
        return new VisualPosition(rowOffset(top), visualColumn(top.line(), bounded.col()) - visualColumn(top.line(), start));
    }

    /**
     * Hits an absolute visual row. Blank rows below EOF map to the final insertion offset.
     * A hit on a non-final wrapped row stays on that row, even past its rendered text.
     */
    public Position positionAt(int row, int column) {
        if (row >= totalRows()) {
            return endOfDocument();
        }
        Top top = topAtRow(row);
        List<Integer> starts = geometry.wrapLines() ? starts(top.line()) : List.of(0);
        int start = starts.get(top.segment());
        boolean hasNext = top.segment() + 1 < starts.size();
        int end = hasNext ? starts.get(top.segment() + 1) : model.lineLength(top.line());
        int requestedVisual = saturatedSum(visualColumn(top.line(), start), Math.max(0, column));
        int offset = layout.textOffsetAtVisualColumn(top.line(), requestedVisual, geometry.tabWidth(),
            EditorTextLayout.Bias.NEAREST);
        offset = Math.clamp(offset, start, end);
        if (hasNext && offset == end && end > start) {
            offset = layout.textOffsetAtVisualColumn(top.line(), Math.max(0, visualColumn(top.line(), end) - 1),
                geometry.tabWidth(), EditorTextLayout.Bias.FLOOR);
        }
        return model.clamp(new Position(top.line(), offset));
    }

    /** Returns null for a row outside the editor content; columns are relative to its text area. */
    public Position positionInViewport(EditorState state, int visibleRows, int row, int column) {
        Objects.requireNonNull(state, "state");
        var hit = TextSurfaceInteraction.hit(new TextSurfaceInteraction.Viewport(rowOffset(Top.from(state)),
            geometry.wrapLines() ? 0 : state.getLeftVisualColumn(), visibleRows, geometry.columns()), row, column);
        return hit == null ? null : positionAt(hit);
    }

    @Override public int totalColumns() { return layout.maxVisualLineWidth(geometry.tabWidth()); }

    @Override public int rowWidth(int row) {
        Top top = topAtRow(row);
        if (!geometry.wrapLines()) return visualColumn(top.line(), model.lineLength(top.line()));
        List<Integer> starts = starts(top.line());
        int start = starts.get(top.segment());
        int end = top.segment() + 1 < starts.size() ? starts.get(top.segment() + 1) : model.lineLength(top.line());
        return visualColumn(top.line(), end) - visualColumn(top.line(), start);
    }

    @Override public TextSurfaceInteraction.Visual visualPosition(Position position) {
        VisualPosition at = toVisual(position);
        return new TextSurfaceInteraction.Visual(at.row(), at.column());
    }

    @Override public Position positionAt(TextSurfaceInteraction.Visual visual) {
        return positionAt(visual.row(), visual.column());
    }

    @Override public TextSurfaceInteraction.Visual normalize(TextSurfaceInteraction.Visual visual) {
        return visualPosition(positionAt(visual));
    }

    @Override public int previousColumn(int row, int column) { return adjacentColumn(row, column, -1); }
    @Override public int nextColumn(int row, int column) { return adjacentColumn(row, column, 1); }

    private int adjacentColumn(int row, int column, int direction) {
        Top top = topAtRow(row);
        int start = geometry.wrapLines() ? starts(top.line()).get(top.segment()) : 0;
        int base = visualColumn(top.line(), start);
        int offset = layout.textOffsetAtVisualColumn(top.line(), saturatedSum(base, Math.max(0, column + direction)),
            geometry.tabWidth(), direction < 0 ? EditorTextLayout.Bias.FLOOR : EditorTextLayout.Bias.CEIL);
        return Math.clamp(visualColumn(top.line(), offset) - base, 0, rowWidth(row));
    }

    public VisualPosition inViewport(Position position, EditorState state) {
        VisualPosition absolute = toVisual(position);
        return new VisualPosition(absolute.row() - rowOffset(Top.from(state)),
            absolute.column() - (geometry.wrapLines() ? 0 : state.getLeftVisualColumn()));
    }

    public TextSurfaceInteraction.Viewport viewport(EditorState state, int rows, int columns) {
        return new TextSurfaceInteraction.Viewport(rowOffset(Top.from(state)),
            geometry.wrapLines() ? 0 : state.getLeftVisualColumn(), rows, columns);
    }

    /** Source viewport adapter: scrolling never creates or changes an editing caret. */
    public void scrollBy(EditorState state, int rows, int columns, int deltaRows, int deltaColumns, int totalColumns) {
        var current = viewport(state, rows, columns);
        var requested = TextSurfaceInteraction.scrollBy(current, totalRows(), totalColumns, deltaRows, deltaColumns);
        var next = TextSurfaceInteraction.pan(this, current, visualPosition(state.getCursor()), requested.top(), requested.left(),
            TextSurfaceInteraction.PanCaret.PRESERVE, totalRows(), totalColumns).viewport();
        applyTop(state, topAtRow(next.top()));
        if (deltaColumns != 0) applyHorizontalOffset(state, next.left());
    }

    public void applyTop(EditorState state, Top top) {
        Top bounded = normalizeTop(top);
        state.setTopLine(bounded.line());
        state.setTopWrapSegment(bounded.segment());
    }

    /** Sets the visual horizontal offset and its compatibility text offset from the same layout. */
    public void applyHorizontalOffset(EditorState state, int visualOffset) {
        Position cursor = model.clamp(state.getCursor());
        int bounded = Math.max(0, visualOffset);
        state.setLeftVisualColumn(bounded);
        state.setLeftColumn(layout.textOffsetAtVisualColumn(cursor.line(), bounded, geometry.tabWidth(),
            EditorTextLayout.Bias.FLOOR));
    }

    private int visualColumn(int line, int offset) {
        return layout.visualColumnAt(line, offset, geometry.tabWidth());
    }

    private List<Integer> starts(int line) {
        return layout.wrapStarts(line, geometry.columns(), geometry.wrapByWords(), geometry.tabWidth());
    }

    private Position endOfDocument() {
        int lastLine = Math.max(0, model.lineCount() - 1);
        return new Position(lastLine, model.lineLength(lastLine));
    }

    private static int saturatedSum(int first, int second) {
        return (int) Math.clamp((long) first + second, 0L, Integer.MAX_VALUE);
    }

    public record Geometry(int columns, boolean wrapLines, boolean wrapByWords, int tabWidth) {
        public Geometry {
            columns = Math.max(1, columns);
            tabWidth = tabWidth <= 2 ? 2 : 4;
        }
    }

    public record Top(int line, int segment) {
        public Top {
            line = Math.max(0, line);
            segment = Math.max(0, segment);
        }

        public static Top from(EditorState state) {
            return new Top(state.getTopLine(), state.getTopWrapSegment());
        }
    }

    public record VisualPosition(int row, int column) {
    }
}
