package dev.tuink.tui;

import dev.tuiframe.text.TerminalText;
import dev.tuiframe.text.TerminalTextLayoutCache.MetricsHandle;
import dev.tuink.render.markdown.MarkdownGeometry;
import dev.tuink.render.markdown.MarkdownLayout;

/** Visual caret plus semantic copy selection; decorative glyphs never enter copied text. */
final class PreviewSelection {
    enum Move { LEFT, RIGHT, UP, DOWN, HOME, END, START, DOCUMENT_END, PAGE_UP, PAGE_DOWN }
    record Range(int start, int end) {
        Range { if (start < 0 || end < start) throw new IllegalArgumentException("Selection range"); }
        boolean intersects(int start, int end) { return this.start < end && this.end > start; }
    }
    record Snapshot(int anchor, int caret) {
        static final Snapshot EMPTY = new Snapshot(0, 0);
        int start() { return Math.min(anchor, caret); }
        int end() { return Math.max(anchor, caret); }
        boolean selected() { return anchor != caret; }
        Range range() { return new Range(start(), end()); }
    }
    record Position(int row, int utf16Offset) {}
    record VisualPosition(int row, int column) {}

    private int anchor;
    private int caret;
    private int preferredColumn = -1;
    private MarkdownGeometry geometry;
    private MarkdownLayout.Result visualResult;
    private VisualPosition visual;

    Snapshot snapshot() { return new Snapshot(anchor, caret); }
    int caret() { return caret; }
    int start() { return Math.min(anchor, caret); }
    int end() { return Math.max(anchor, caret); }
    void clear() { anchor = 0; caret = 0; preferredColumn = -1; visual = null; releaseIndex(); }
    void releaseIndex() { geometry = null; visualResult = null; }
    void bindGeometry(MarkdownGeometry geometry) { this.geometry = geometry; }

    void reconcile(String text) {
        anchor = floor(text, Math.min(anchor, text.length()));
        caret = floor(text, Math.min(caret, text.length()));
        preferredColumn = -1; visual = null;
    }

    void selectAll(String text) { anchor = 0; caret = text.length(); preferredColumn = -1; visual = null; }
    void selectRange(String text, int start, int end) {
        anchor = floor(text, Math.max(0, Math.min(start, text.length())));
        caret = ceil(text, Math.max(anchor, Math.min(end, text.length())));
        preferredColumn = -1; visual = null;
    }

    String selectedText(String text) {
        int start = Math.min(start(), text.length());
        int end = Math.min(end(), text.length());
        return start == end ? text : text.substring(start, end);
    }

    void selectAt(MarkdownLayout.Result result, int row, int utf16Offset, boolean extend) {
        if (result.lines().isEmpty()) return;
        int safeRow = Math.max(0, Math.min(row, result.lines().size() - 1));
        String line = result.lines().get(safeRow).text();
        int column;
        try (var access = rowGeometry(result, safeRow)) {
            column = access.metrics.visualColumnAt(Math.max(0, Math.min(utf16Offset, line.length())));
        }
        selectAtColumn(result, safeRow, column, extend);
    }

    void selectAtColumn(MarkdownLayout.Result result, int row, int column, boolean extend) {
        VisualPosition before = visualPosition(result);
        visual = bounded(result, row, column); visualResult = result;
        updateSemantic(result, extend, compare(visual, before) >= 0);
        preferredColumn = -1;
    }

    VisualPosition visualPosition(MarkdownLayout.Result result) {
        if (visual == null || visualResult != result) {
            Position mapped = position(result, caret);
            int column = 0;
            if (!result.lines().isEmpty()) try (var access = rowGeometry(result, mapped.row())) {
                column = access.metrics.visualColumnAt(mapped.utf16Offset());
            }
            visual = bounded(result, mapped.row(), column); visualResult = result;
        }
        return visual;
    }

    /** Restore a visual anchor after reflow without changing semantic selection. */
    void restoreVisual(MarkdownLayout.Result result, int row, int column) {
        visual = bounded(result, row, column); visualResult = result; preferredColumn = -1;
    }

    /** Pan carries the visual caret, preserving an existing copy selection. */
    void pan(MarkdownLayout.Result result, int rows, int columns) {
        VisualPosition before = visualPosition(result);
        visual = bounded(result, before.row() + rows, before.column() + columns); visualResult = result;
        if (anchor == caret) updateSemantic(result, false, rows > 0 || rows == 0 && columns >= 0);
        preferredColumn = -1;
    }

    int targetOffset(MarkdownLayout.Result result) {
        VisualPosition at = visualPosition(result);
        return offsetAtColumn(result, at.row(), at.column());
    }

    void move(MarkdownLayout.Result result, Move move, boolean extend, int pageRows) {
        if (result.lines().isEmpty()) return;
        String text = result.document().projection().text();
        reconcileBounds(text);
        if (!extend && anchor != caret && (move == Move.LEFT || move == Move.RIGHT)) {
            set(move == Move.LEFT ? start() : end(), false); visual = null; visualPosition(result); return;
        }
        VisualPosition before = visualPosition(result);
        int row = before.row(), column = before.column();
        int lineWidth = lineWidth(result, row);
        if (move == Move.LEFT || move == Move.RIGHT) {
            try (var access = rowGeometry(result, row)) {
                var metrics = access.metrics;
                int offset = metrics.offsetForVisualColumn(column, TerminalText.Bias.CEIL);
                if (move == Move.LEFT) {
                    if (column > lineWidth) column--;
                    else if (column > 0) column = metrics.visualColumnAt(metrics.previousBoundary(offset));
                    else if (row > 0) { row--; column = lineWidth(result, row); }
                } else {
                    if (column < lineWidth) column = metrics.visualColumnAt(metrics.nextBoundary(offset));
                    else if (row < result.lines().size() - 1) { row++; column = 0; }
                }
            }
        } else if (move == Move.START) { row = 0; column = 0; }
        else if (move == Move.DOCUMENT_END) {
            row = result.lines().size() - 1; column = lineWidth(result, row);
        } else if (move == Move.HOME) column = 0;
        else if (move == Move.END) column = lineWidth;
        else {
            if (preferredColumn < 0) preferredColumn = column;
            int direction = move == Move.UP || move == Move.PAGE_UP ? -1 : 1;
            int distance = move == Move.PAGE_UP || move == Move.PAGE_DOWN ? Math.max(1, pageRows) : 1;
            row = Math.max(0, Math.min(result.lines().size() - 1, row + direction * distance));
            column = preferredColumn;
        }
        visual = bounded(result, row, column); visualResult = result;
        updateSemantic(result, extend, compare(visual, before) >= 0);
        if (move != Move.UP && move != Move.DOWN && move != Move.PAGE_UP && move != Move.PAGE_DOWN)
            preferredColumn = -1;
    }

    private void updateSemantic(MarkdownLayout.Result result, boolean extend, boolean forward) {
        String text = result.document().projection().text();
        int offset = offsetAtColumn(result, visual.row(), visual.column());
        if (extend) {
            Range atom = atomicAtVisual(result, visual);
            if (atom != null) offset = forward ? atom.end() : atom.start();
        }
        set(floor(text, Math.max(0, Math.min(offset, text.length()))), extend);
    }

    private static int compare(VisualPosition a, VisualPosition b) {
        return a.row() == b.row() ? Integer.compare(a.column(), b.column()) : Integer.compare(a.row(), b.row());
    }
    private VisualPosition bounded(MarkdownLayout.Result result, int row, int column) {
        int safeRow = Math.max(0, Math.min(row, Math.max(0, result.lines().size() - 1)));
        int safeColumn = Math.max(0, Math.min(column, result.extent()));
        if (!result.lines().isEmpty()) {
            try (var access = rowGeometry(result, safeRow)) {
                var metrics = access.metrics;
                if (safeColumn <= metrics.visualWidth()) {
                    int offset = metrics.offsetForVisualColumn(safeColumn, TerminalText.Bias.CEIL);
                    safeColumn = metrics.visualColumnAt(offset);
                }
            }
        }
        return new VisualPosition(safeRow, safeColumn);
    }

    private void reconcileBounds(String text) {
        anchor = Math.min(anchor, text.length()); caret = Math.min(caret, text.length());
    }
    private void set(int offset, boolean extend) { caret = offset; if (!extend) anchor = offset; }

    private int offsetAtColumn(MarkdownLayout.Result result, int row, int visualColumn) {
        if (result.lines().isEmpty()) return 0;
        int safeRow = Math.max(0, Math.min(row, result.lines().size() - 1));
        int offset;
        try (var access = rowGeometry(result, safeRow)) {
            offset = access.metrics.offsetForVisualColumn(Math.max(0, visualColumn), TerminalText.Bias.CEIL);
        }
        Integer direct = lineOffset(result.lines().get(safeRow), offset);
        if (direct != null) return direct;
        if (hasGeometry(result)) {
            int nearest = geometry.nearestContentRow(safeRow);
            if (nearest < 0) return 0;
            Integer mapped = lineOffset(result.lines().get(nearest), nearest > safeRow ? 0 : Integer.MAX_VALUE);
            return mapped == null ? 0 : mapped;
        }
        return offsetAt(result, safeRow, offset);
    }

    static int offsetAt(MarkdownLayout.Result result, int row, int utf16Offset) {
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

    static boolean linear(MarkdownLayout.Run run) {
        return run.projectionStart() >= 0 && run.role() != MarkdownLayout.Role.MATH && run.role() != MarkdownLayout.Role.DIAGRAM
            && run.projectionEnd() - run.projectionStart() == run.end() - run.start();
    }

    static Position position(MarkdownLayout.Result result, int projectionOffset) {
        if (result.lines().isEmpty()) return new Position(0, 0);
        Position nearest = new Position(0, 0);
        int best = Integer.MAX_VALUE;
        for (int row = 0; row < result.lines().size(); row++) {
            var line = result.lines().get(row);
            for (var run : line.runs()) {
                if (run.projectionStart() < 0) continue;
                int start = run.projectionStart(), end = run.projectionEnd();
                if (projectionOffset >= start && projectionOffset < end) {
                    int offset = linear(run) ? run.start() + projectionOffset - start : run.start();
                    return new Position(row, Math.min(offset, line.text().length()));
                }
                int distance = projectionOffset < start ? start - projectionOffset : projectionOffset - end;
                if (distance <= best) {
                    best = distance;
                    nearest = new Position(row, Math.min(projectionOffset < start ? run.start() : run.end(), line.text().length()));
                }
            }
        }
        return nearest;
    }

    private Range atomicAtVisual(MarkdownLayout.Result result, VisualPosition position) {
        if (result.lines().isEmpty()) return null;
        var line = result.lines().get(position.row());
        int offset;
        try (var access = rowGeometry(result, position.row())) {
            offset = access.metrics.offsetForVisualColumn(position.column(), TerminalText.Bias.CEIL);
        }
        for (var run : line.runs()) {
            if (run.projectionStart() >= 0 && !linear(run) && offset >= run.start() && offset <= run.end())
                return new Range(run.projectionStart(), run.projectionEnd());
        }
        return null;
    }

    private boolean hasGeometry(MarkdownLayout.Result result) { return geometry != null && geometry.result() == result; }
    private RowGeometry rowGeometry(MarkdownLayout.Result result, int row) {
        MetricsHandle handle = hasGeometry(result) ? geometry.acquire(row) : null;
        try {
            return new RowGeometry(handle, handle == null
                ? TerminalText.streamingGeometry(result.lines().get(row).text(), 4) : handle.metrics());
        } catch (RuntimeException | Error failure) { if (handle != null) handle.close(); throw failure; }
    }
    private int lineWidth(MarkdownLayout.Result result, int row) {
        try (var access = rowGeometry(result, row)) { return access.metrics.visualWidth(); }
    }
    private static final class RowGeometry implements AutoCloseable {
        private final MetricsHandle handle;
        private final TerminalText.Geometry metrics;
        RowGeometry(MetricsHandle handle, TerminalText.Geometry metrics) { this.handle = handle; this.metrics = metrics; }
        @Override public void close() { if (handle != null) handle.close(); }
    }
    private int floor(String text, int offset) { return boundary(text, offset, false); }
    private int ceil(String text, int offset) { return boundary(text, offset, true); }
    private int boundary(String text, int offset, boolean ceil) {
        if (geometry != null && geometry.result().document().projection().text() == text)
            return ceil ? geometry.projectionCeil(offset) : geometry.projectionFloor(offset);
        int start = text.lastIndexOf('\n', Math.max(-1, offset - 1)) + 1;
        int end = text.indexOf('\n', offset);
        if (end < 0) end = text.length();
        var streamed = TerminalText.streamingGeometry(TerminalText.slice(text, start, end), 4);
        return start + (ceil ? streamed.ceilBoundary(offset - start) : streamed.floorBoundary(offset - start));
    }
}
