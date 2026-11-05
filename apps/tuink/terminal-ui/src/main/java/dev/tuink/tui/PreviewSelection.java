package dev.tuink.tui;

import dev.tuink.render.markdown.MarkdownGeometry;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.render.markdown.MarkdownTextSurface;
import dev.tuink.render.surface.TextSurfaceInteraction;

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
    /** Drops copy selection while retaining the caret's two-dimensional visual affinity and index. */
    void collapseSelection() { anchor = caret; }
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

    private MarkdownTextSurface surface(MarkdownLayout.Result result) { return new MarkdownTextSurface(result, geometry); }
    private static TextSurfaceInteraction.Visual common(VisualPosition position) {
        return new TextSurfaceInteraction.Visual(position.row(), position.column());
    }
    private static VisualPosition presentation(TextSurfaceInteraction.Visual position) {
        return new VisualPosition(position.row(), position.column());
    }

    void selectAt(MarkdownLayout.Result result, int row, int utf16Offset, boolean extend) {
        if (result.lines().isEmpty()) return;
        int safeRow = Math.clamp(row, 0, result.lines().size() - 1);
        selectAtColumn(result, safeRow, surface(result).columnAt(safeRow, utf16Offset), extend);
    }

    void selectAtColumn(MarkdownLayout.Result result, int row, int column, boolean extend) {
        VisualPosition before = visualPosition(result);
        visual = presentation(surface(result).normalize(new TextSurfaceInteraction.Visual(row, column))); visualResult = result;
        updateSemantic(result, extend, TextSurfaceInteraction.compare(common(visual), common(before)) >= 0);
        preferredColumn = -1;
    }

    VisualPosition visualPosition(MarkdownLayout.Result result) {
        if (visual == null || visualResult != result) {
            visual = presentation(surface(result).visualPosition(caret)); visualResult = result;
        }
        return visual;
    }

    /** Restore visual affinity after reflow without changing semantic selection. */
    void restoreVisual(MarkdownLayout.Result result, int row, int column) {
        visual = presentation(surface(result).normalize(new TextSurfaceInteraction.Visual(row, column)));
        visualResult = result; preferredColumn = -1;
    }

    /** Pan carries the visual caret, preserving an existing semantic copy selection. */
    void pan(MarkdownLayout.Result result, int rows, int columns) {
        var before = common(visualPosition(result));
        visual = presentation(TextSurfaceInteraction.translate(surface(result), before, rows, columns)); visualResult = result;
        if (anchor == caret) updateSemantic(result, false, rows > 0 || rows == 0 && columns >= 0);
        preferredColumn = -1;
    }

    void pan(MarkdownLayout.Result result, TextSurfaceInteraction.Pan pan, boolean forward) {
        visual = presentation(pan.translated()); visualResult = result;
        if (anchor == caret) updateSemantic(result, false, forward);
        visual = presentation(pan.visual()); preferredColumn = -1;
    }

    int targetOffset(MarkdownLayout.Result result) {
        return surface(result).positionAt(common(visualPosition(result)));
    }

    void move(MarkdownLayout.Result result, Move move, boolean extend, int pageRows) {
        if (result.lines().isEmpty()) return;
        String text = result.document().projection().text();
        anchor = Math.min(anchor, text.length()); caret = Math.min(caret, text.length());
        if (!extend && anchor != caret && (move == Move.LEFT || move == Move.RIGHT)) {
            set(move == Move.LEFT ? start() : end(), false); visual = null; visualPosition(result); return;
        }
        var before = common(visualPosition(result));
        var movement = TextSurfaceInteraction.move(surface(result), before,
            TextSurfaceInteraction.Move.valueOf(move.name()), pageRows, preferredColumn);
        visual = presentation(movement.visual()); visualResult = result; preferredColumn = movement.preferredColumn();
        updateSemantic(result, extend, TextSurfaceInteraction.compare(movement.visual(), before) >= 0);
    }

    private void updateSemantic(MarkdownLayout.Result result, boolean extend, boolean forward) {
        var projection = surface(result);
        set(TextSurfaceInteraction.target(projection, projection, common(visual), extend, forward), extend);
    }
    private void set(int offset, boolean extend) { caret = offset; if (!extend) anchor = offset; }

    static int offsetAt(MarkdownLayout.Result result, int row, int utf16Offset) {
        return MarkdownTextSurface.offsetAt(result, row, utf16Offset);
    }
    static boolean linear(MarkdownLayout.Run run) { return MarkdownTextSurface.linear(run); }
    static Position position(MarkdownLayout.Result result, int projectionOffset) {
        var position = MarkdownTextSurface.textPosition(result, projectionOffset);
        return new Position(position.row(), position.utf16Offset());
    }
    private int floor(String text, int offset) { return boundary(text, offset, false); }
    private int ceil(String text, int offset) { return boundary(text, offset, true); }
    private int boundary(String text, int offset, boolean ceil) {
        if (geometry != null && geometry.result().document().projection().text() == text)
            return surface(geometry.result()).boundary(offset, ceil);
        return MarkdownTextSurface.semanticBoundary(text, offset, ceil);
    }
}
