package dev.tuink.render.surface;

import java.util.Objects;
import dev.tuiframe.scroll.ScrollInteraction;

/**
 * Shared terminal text interaction algorithms. All state and commits belong to
 * the calling presentation; no editor, Markdown, event, or application host is retained here.
 */
public final class TextSurfaceInteraction {
    private TextSurfaceInteraction() {}

    public record Visual(int row, int column) {}
    public record Viewport(int top, int left, int rows, int columns) {}
    public record Movement(Visual visual, int preferredColumn) {}
    /** translated precedes viewport clipping and supplies the semantic target; visual retains display affinity. */
    public record Pan(Viewport viewport, Visual visual, Visual translated) {}
    public enum Move { LEFT, RIGHT, UP, DOWN, HOME, END, START, DOCUMENT_END, PAGE_UP, PAGE_DOWN }
    public enum PanCaret { PRESERVE, TRANSLATE_VISUAL }

    public static Movement move(TextSurfaceGeometry<?> geometry, Visual before, Move move,
                                int pageRows, int preferredColumn) {
        Objects.requireNonNull(geometry); Objects.requireNonNull(before); Objects.requireNonNull(move);
        if (geometry.totalRows() <= 0) return new Movement(new Visual(0, 0), -1);
        Visual bounded = geometry.normalize(before);
        int row = bounded.row(), column = bounded.column();
        int width = geometry.rowWidth(row);
        boolean vertical = move == Move.UP || move == Move.DOWN || move == Move.PAGE_UP || move == Move.PAGE_DOWN;
        switch (move) {
            case LEFT -> {
                if (column > width) column--;
                else if (column > 0) column = geometry.previousColumn(row, column);
                else if (row > 0) { row--; column = geometry.rowWidth(row); }
            }
            case RIGHT -> {
                if (column < width) column = geometry.nextColumn(row, column);
                else if (row < geometry.totalRows() - 1) { row++; column = 0; }
            }
            case HOME -> column = 0;
            case END -> column = width;
            case START -> { row = 0; column = 0; }
            case DOCUMENT_END -> { row = geometry.totalRows() - 1; column = geometry.rowWidth(row); }
            case UP, DOWN, PAGE_UP, PAGE_DOWN -> {
                if (preferredColumn < 0) preferredColumn = column;
                int direction = move == Move.UP || move == Move.PAGE_UP ? -1 : 1;
                int distance = move == Move.PAGE_UP || move == Move.PAGE_DOWN ? Math.max(1, pageRows) : 1;
                row = boundedRow((long) row + (long) direction * distance, geometry.totalRows());
                column = preferredColumn;
            }
        }
        return new Movement(geometry.normalize(new Visual(row, column)), vertical ? preferredColumn : -1);
    }

    /** Resolves a visual target once, including atomic selection and semantic grapheme boundaries. */
    public static <P> P target(TextSurfaceGeometry<P> geometry, TextSurfaceContent<P> content,
                               Visual visual, boolean extending, boolean forward) {
        P target = geometry.positionAt(visual);
        if (extending) {
            var atom = geometry.selectionAtom(visual);
            if (atom != null) target = forward ? atom.end() : atom.start();
        }
        return content.boundary(target, false);
    }

    /** A window hit and painting use the same viewport placement. */
    public static Visual hit(Viewport viewport, int row, int column) {
        if (row < 0 || row >= viewport.rows()) return null;
        return new Visual(add(viewport.top(), row), add(viewport.left(), Math.max(0, column)));
    }

    /** Shared wheel/drag offset policy, using the framework's bounded scrolling primitive. */
    public static Viewport scrollBy(Viewport viewport, int totalRows, int totalColumns, int rows, int columns) {
        return new Viewport(ScrollInteraction.scrollBy(viewport.top(), rows, viewport.rows(), totalRows),
            ScrollInteraction.scrollBy(viewport.left(), columns, viewport.columns(), totalColumns), viewport.rows(), viewport.columns());
    }

    /** Reflow preserves a semantic anchor, with an affinity for rows inside a non-linear atom. */
    public static <P> int anchorRow(TextSurfaceGeometry<P> geometry, P anchor, int visualDelta) {
        return boundedRow((long) geometry.visualPosition(anchor).row() + visualDelta, geometry.totalRows());
    }

    public static Viewport clamp(Viewport viewport, int totalRows, int totalColumns) {
        return new Viewport(clampOffset(viewport.top(), viewport.rows(), totalRows),
            clampOffset(viewport.left(), viewport.columns(), totalColumns), viewport.rows(), viewport.columns());
    }

    public static Viewport reveal(Viewport viewport, Visual caret, int totalRows, int totalColumns,
                                  int horizontalMargin, boolean clampVertical, boolean revealHorizontal) {
        int top = viewport.top(), left = viewport.left();
        if (viewport.rows() > 0) {
            if (caret.row() < top) top = caret.row();
            else if ((long) caret.row() >= (long) top + viewport.rows()) top = caret.row() - viewport.rows() + 1;
            if (clampVertical) top = clampOffset(top, viewport.rows(), totalRows);
        }
        if (revealHorizontal && viewport.columns() > 0) {
            int maxLeft = Math.max(0, totalColumns - viewport.columns());
            int margin = Math.max(0, Math.min(horizontalMargin, viewport.columns() / 3));
            left = Math.min(left, maxLeft);
            if (caret.column() < (long) left + margin) left = Math.clamp(caret.column() - margin, 0, maxLeft);
            else if (caret.column() > (long) left + viewport.columns() - 1 - margin)
                left = Math.clamp(caret.column() - Math.max(0, viewport.columns() - 1 - margin), 0, maxLeft);
        }
        return new Viewport(top, left, viewport.rows(), viewport.columns());
    }

    public static Visual translate(TextSurfaceGeometry<?> geometry, Visual caret, int rows, int columns) {
        return geometry.normalize(new Visual(add(caret.row(), rows), add(caret.column(), columns)));
    }

    public static Pan pan(TextSurfaceGeometry<?> geometry, Viewport viewport, Visual caret,
                          int nextTop, int nextLeft, PanCaret policy) {
        return pan(geometry, viewport, caret, nextTop, nextLeft, policy, geometry.totalRows(), geometry.totalColumns());
    }

    /** Source may reserve a terminal insertion cell beyond its last printed character. */
    public static Pan pan(TextSurfaceGeometry<?> geometry, Viewport viewport, Visual caret,
                          int nextTop, int nextLeft, PanCaret policy, int totalRows, int totalColumns) {
        Viewport next = clamp(new Viewport(nextTop, nextLeft, viewport.rows(), viewport.columns()), totalRows, totalColumns);
        if (policy == PanCaret.PRESERVE) return new Pan(next, caret, caret);
        Visual translated = translate(geometry, caret, next.top() - viewport.top(), next.left() - viewport.left());
        return new Pan(next, geometry.normalize(keepVisible(translated, next)), translated);
    }

    public static Visual keepVisible(Visual caret, Viewport viewport) {
        return new Visual(Math.clamp(caret.row(), viewport.top(), add(viewport.top(), Math.max(1, viewport.rows()) - 1)),
            Math.clamp(caret.column(), viewport.left(), add(viewport.left(), Math.max(1, viewport.columns()) - 1)));
    }

    public static int pageTop(int oldTop, int delta, int totalRows, int viewportRows, boolean clampToPage) {
        return (int) Math.clamp((long) oldTop + delta, 0L,
            clampToPage ? Math.max(0L, (long) totalRows - Math.max(0, viewportRows)) : Math.max(0L, (long) totalRows - 1));
    }

    public static int compare(Visual first, Visual second) {
        return first.row() == second.row() ? Integer.compare(first.column(), second.column()) : Integer.compare(first.row(), second.row());
    }

    private static int boundedRow(long row, int totalRows) { return (int) Math.clamp(row, 0L, Math.max(0L, (long) totalRows - 1)); }
    private static int clampOffset(int offset, int visible, int total) { return ScrollInteraction.clampOffset(offset, visible, total); }
    private static int add(int first, int second) { return (int) Math.clamp((long) first + second, 0L, Integer.MAX_VALUE); }
}
