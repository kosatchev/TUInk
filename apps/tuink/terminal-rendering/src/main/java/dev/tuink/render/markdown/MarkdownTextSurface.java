package dev.tuink.render.markdown;

import dev.tuiframe.text.TerminalText;
import dev.tuiframe.text.TerminalTextLayoutCache.MetricsHandle;
import dev.tuink.render.surface.TextSurfaceContent;
import dev.tuink.render.surface.TextSurfaceGeometry;
import dev.tuink.render.surface.TextSurfaceInteraction;
import java.util.Objects;

/** Borrowed immutable Markdown projection and metrics, with no editor or selection state. */
public final class MarkdownTextSurface implements TextSurfaceGeometry<Integer>, TextSurfaceContent<Integer> {
    public record TextOffset(int row, int utf16Offset) {}
    private final MarkdownLayout.Result result;
    private final MarkdownGeometry geometry;

    public MarkdownTextSurface(MarkdownLayout.Result result, MarkdownGeometry geometry) {
        this.result = Objects.requireNonNull(result);
        this.geometry = geometry;
    }

    @Override public int totalRows() { return result.lines().size(); }
    @Override public int totalColumns() { return result.extent(); }
    @Override public int rowWidth(int row) {
        if (totalRows() == 0) return 0;
        try (var access = rowGeometry(row)) { return access.metrics.visualWidth(); }
    }
    public int columnAt(int row, int utf16Offset) {
        if (totalRows() == 0) return 0;
        try (var access = rowGeometry(row)) { return access.metrics.visualColumnAt(utf16Offset); }
    }
    @Override public TextSurfaceInteraction.Visual visualPosition(Integer offset) {
        TextOffset at = textPosition(result, offset);
        return normalize(new TextSurfaceInteraction.Visual(at.row(), columnAt(at.row(), at.utf16Offset())));
    }
    @Override public TextSurfaceInteraction.Visual normalize(TextSurfaceInteraction.Visual visual) {
        int row = Math.clamp(visual.row(), 0, Math.max(0, totalRows() - 1));
        int column = Math.clamp(visual.column(), 0, result.extent());
        if (totalRows() > 0) try (var access = rowGeometry(row)) {
            if (column <= access.metrics.visualWidth()) {
                int offset = access.metrics.offsetForVisualColumn(column, TerminalText.Bias.CEIL);
                column = access.metrics.visualColumnAt(offset);
            }
        }
        return new TextSurfaceInteraction.Visual(row, column);
    }
    @Override public int previousColumn(int row, int column) {
        try (var access = rowGeometry(row)) {
            int offset = access.metrics.offsetForVisualColumn(column, TerminalText.Bias.CEIL);
            return access.metrics.visualColumnAt(access.metrics.previousBoundary(offset));
        }
    }
    @Override public int nextColumn(int row, int column) {
        try (var access = rowGeometry(row)) {
            int offset = access.metrics.offsetForVisualColumn(column, TerminalText.Bias.CEIL);
            return access.metrics.visualColumnAt(access.metrics.nextBoundary(offset));
        }
    }
    @Override public Integer positionAt(TextSurfaceInteraction.Visual visual) {
        if (totalRows() == 0) return 0;
        int row = Math.clamp(visual.row(), 0, totalRows() - 1);
        int offset;
        try (var access = rowGeometry(row)) {
            offset = access.metrics.offsetForVisualColumn(Math.max(0, visual.column()), TerminalText.Bias.CEIL);
        }
        Integer direct = lineOffset(result.lines().get(row), offset);
        if (direct != null) return direct;
        if (hasGeometry()) {
            int nearest = geometry.nearestContentRow(row);
            if (nearest < 0) return 0;
            Integer mapped = lineOffset(result.lines().get(nearest), nearest > row ? 0 : Integer.MAX_VALUE);
            return mapped == null ? 0 : mapped;
        }
        return offsetAt(result, row, offset);
    }
    @Override public Atom<Integer> selectionAtom(TextSurfaceInteraction.Visual visual) {
        if (totalRows() == 0) return null;
        int row = Math.clamp(visual.row(), 0, totalRows() - 1);
        int offset;
        try (var access = rowGeometry(row)) {
            offset = access.metrics.offsetForVisualColumn(visual.column(), TerminalText.Bias.CEIL);
        }
        for (var run : result.lines().get(row).runs()) {
            if (run.projectionStart() >= 0 && !linear(run) && offset >= run.start() && offset <= run.end())
                return new Atom<>(run.projectionStart(), run.projectionEnd());
        }
        return null;
    }
    @Override public Integer boundary(Integer offset, boolean ceil) {
        String text = result.document().projection().text();
        int bounded = Math.clamp(offset, 0, text.length());
        if (hasGeometry()) return ceil ? geometry.projectionCeil(bounded) : geometry.projectionFloor(bounded);
        return semanticBoundary(text, bounded, ceil);
    }
    public static int semanticBoundary(String text, int offset, boolean ceil) {
        int safe = Math.clamp(offset, 0, text.length());
        int start = text.lastIndexOf('\n', Math.max(-1, safe - 1)) + 1;
        int end = text.indexOf('\n', safe);
        if (end < 0) end = text.length();
        var streamed = TerminalText.streamingGeometry(TerminalText.slice(text, start, end), 4);
        return start + (ceil ? streamed.ceilBoundary(safe - start) : streamed.floorBoundary(safe - start));
    }

    public static int offsetAt(MarkdownLayout.Result result, int row, int utf16Offset) {
        if (result.lines().isEmpty()) return 0;
        int safeRow = Math.max(0, Math.min(row, result.lines().size() - 1));
        Integer direct = lineOffset(result.lines().get(safeRow), utf16Offset);
        if (direct != null) return direct;
        for (int distance = 1; distance < result.lines().size(); distance++) {
            if (safeRow + distance < result.lines().size()) {
                Integer next = lineOffset(result.lines().get(safeRow + distance), 0);
                if (next != null) return next;
            }
            if (safeRow - distance >= 0) {
                Integer previous = lineOffset(result.lines().get(safeRow - distance), Integer.MAX_VALUE);
                if (previous != null) return previous;
            }
        }
        return 0;
    }

    private static Integer lineOffset(MarkdownLayout.Line line, int utf16Offset) {
        int offset = Math.max(0, Math.min(utf16Offset, line.text().length()));
        MarkdownLayout.Run nearest = null;
        int distance = Integer.MAX_VALUE;
        for (var run : line.runs()) {
            if (run.projectionStart() < 0) continue;
            int start = Math.min(run.start(), line.text().length());
            int end = Math.min(run.end(), line.text().length());
            int candidateDistance = offset < start ? start - offset : offset > end ? offset - end : 0;
            if (candidateDistance < distance) { nearest = run; distance = candidateDistance; }
            if (candidateDistance == 0 && offset < end) break;
        }
        if (nearest == null) return null;
        int start = Math.min(nearest.start(), line.text().length());
        int end = Math.min(nearest.end(), line.text().length());
        if (linear(nearest)) return nearest.projectionStart() + Math.max(0, Math.min(offset - start, end - start));
        return nearest.projectionStart();
    }

    public static boolean linear(MarkdownLayout.Run run) {
        return run.projectionStart() >= 0 && run.role() != MarkdownLayout.Role.MATH && run.role() != MarkdownLayout.Role.DIAGRAM
            && run.projectionEnd() - run.projectionStart() == run.end() - run.start();
    }

    public static TextOffset textPosition(MarkdownLayout.Result result, int projectionOffset) {
        if (result.lines().isEmpty()) return new TextOffset(0, 0);
        TextOffset nearest = new TextOffset(0, 0);
        int best = Integer.MAX_VALUE;
        for (int row = 0; row < result.lines().size(); row++) {
            var line = result.lines().get(row);
            for (var run : line.runs()) {
                if (run.projectionStart() < 0) continue;
                int start = run.projectionStart(), end = run.projectionEnd();
                if (projectionOffset >= start && projectionOffset < end) {
                    int offset = linear(run) ? run.start() + projectionOffset - start : run.start();
                    return new TextOffset(row, Math.min(offset, line.text().length()));
                }
                int distance = projectionOffset < start ? start - projectionOffset : projectionOffset - end;
                if (distance <= best) {
                    best = distance;
                    nearest = new TextOffset(row, Math.min(projectionOffset < start ? run.start() : run.end(), line.text().length()));
                }
            }
        }
        return nearest;
    }

    private boolean hasGeometry() { return geometry != null && geometry.result() == result; }
    private RowGeometry rowGeometry(int row) {
        MetricsHandle handle = hasGeometry() ? geometry.acquire(row) : null;
        try {
            return new RowGeometry(handle, handle == null
                ? TerminalText.streamingGeometry(result.lines().get(row).text(), 4) : handle.metrics());
        } catch (RuntimeException | Error failure) { if (handle != null) handle.close(); throw failure; }
    }
    private static final class RowGeometry implements AutoCloseable {
        private final MetricsHandle handle;
        private final TerminalText.Geometry metrics;
        RowGeometry(MetricsHandle handle, TerminalText.Geometry metrics) { this.handle = handle; this.metrics = metrics; }
        @Override public void close() { if (handle != null) handle.close(); }
    }
}
