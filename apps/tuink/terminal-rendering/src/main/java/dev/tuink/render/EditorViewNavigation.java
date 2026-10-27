package dev.tuink.render;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.render.EditorViewCoordinates.Geometry;
import dev.tuink.render.EditorViewCoordinates.Top;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Navigation context belonging to one editor view. The engine applies text targets and owns history;
 * this view owns preferred visual columns, displayed rows and cursor visibility.
 */
public final class EditorViewNavigation {
    private static final int H_SCROLL_MARGIN = 12;
    private final EditorEngine engine;
    private final BiConsumer<Position, Boolean> navigate;
    private int preferredColumn = -1;
    private Geometry preferredGeometry;
    private long seenNavigationRevision = -1;
    private long seenModelRevision = -1;
    private Position seenCursor;
    private Position seenAnchor;

    public EditorViewNavigation(EditorEngine engine, BiConsumer<Position, Boolean> navigate) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.navigate = Objects.requireNonNull(navigate, "navigate");
    }

    public EditorEngine engine() {
        return engine;
    }

    public void moveUp(boolean selecting, int columns, boolean wrapLines, boolean wrapByWords) {
        moveRows(-1, selecting, columns, wrapLines, wrapByWords);
    }

    public void moveDown(boolean selecting, int columns, boolean wrapLines, boolean wrapByWords) {
        moveRows(1, selecting, columns, wrapLines, wrapByWords);
    }

    private void moveRows(int delta, boolean selecting, int columns, boolean wrapLines, boolean wrapByWords) {
        EditorState state = engine.state();
        TextModel model = engine.model();
        Geometry geometry = new Geometry(wrapLines ? columns : 1, wrapLines,
            wrapLines && wrapByWords, engine.tabWidth());
        var coordinates = new EditorViewCoordinates(engine.textLayout(), geometry);
        var previous = coordinates.toVisual(state.getCursor());
        if (preferredColumn < 0 || !geometry.equals(preferredGeometry)
            || seenNavigationRevision != engine.navigationRevision() || seenModelRevision != model.revision()
            || !Objects.equals(seenCursor, state.getCursor()) || !Objects.equals(seenAnchor, state.getSelectionAnchor())) {
            preferredColumn = previous.column();
        }
        preferredGeometry = geometry;
        int targetRow = (int) Math.clamp((long) previous.row() + delta, 0L, Math.max(0, coordinates.totalRows() - 1));
        Position target = targetRow == previous.row() ? state.getCursor() : coordinates.positionAt(targetRow, preferredColumn);
        navigate.accept(target, selecting);
        rememberNavigation();
    }

    public void pageUp(int rows, int columns, boolean wrapLines, boolean wrapByWords) {
        page(-Math.max(1, rows), columns, wrapLines, wrapByWords);
    }

    public void pageDown(int rows, int columns, boolean wrapLines, boolean wrapByWords) {
        page(Math.max(1, rows), columns, wrapLines, wrapByWords);
    }

    private void page(int delta, int columns, boolean wrapLines, boolean wrapByWords) {
        EditorState state = engine.state();
        var coordinates = new EditorViewCoordinates(engine.textLayout(), columns, wrapLines, wrapByWords, engine.tabWidth());
        int oldTop = coordinates.rowOffset(Top.from(state));
        if (!state.hasSelection()) {
            moveRows(delta, false, columns, wrapLines, wrapByWords);
        }
        if (wrapLines) {
            int maxTop = Math.max(0, coordinates.totalRows() - Math.abs(delta));
            int targetTop = (int) Math.clamp((long) oldTop + delta, 0L, maxTop);
            coordinates.applyTop(state, coordinates.topAtRow(targetTop));
            return;
        }
        coordinates.applyTop(state, coordinates.topAtRow((int) Math.clamp((long) oldTop + delta, 0L, Integer.MAX_VALUE)));
    }

    public void ensureCursorVisible(int rows, int columns, boolean wrapLines, boolean wrapByWords) {
        ensureCursorVisible(engine, rows, columns, wrapLines, wrapByWords);
    }

    /** Visibility has no preferred-column state and can be shared by pointer and resize adapters. */
    public static void ensureCursorVisible(
        EditorEngine engine, int rows, int columns, boolean wrapLines, boolean wrapByWords
    ) {
        if (rows <= 0) {
            return;
        }
        EditorState state = engine.state();
        TextModel model = engine.model();
        EditorTextLayout layout = engine.textLayout();
        var coordinates = new EditorViewCoordinates(layout, columns, wrapLines, wrapByWords, engine.tabWidth());
        Position cursor = model.clamp(state.getCursor());
        if (wrapLines) {
            int cursorRow = coordinates.toVisual(cursor).row();
            int top = coordinates.rowOffset(Top.from(state));
            int target = cursorRow < top ? cursorRow : cursorRow >= (long) top + rows ? cursorRow - rows + 1 : top;
            target = Math.clamp(target, 0, Math.max(0, coordinates.totalRows() - rows));
            coordinates.applyTop(state, coordinates.topAtRow(target));
            coordinates.applyHorizontalOffset(state, 0);
            return;
        }
        if (cursor.line() < state.getTopLine()) {
            state.setTopLine(cursor.line());
        } else if (cursor.line() >= (long) state.getTopLine() + rows) {
            state.setTopLine(cursor.line() - rows + 1);
        }
        if (columns <= 0) {
            return;
        }
        int margin = Math.max(0, Math.min(H_SCROLL_MARGIN, columns / 3));
        int cursorColumn = coordinates.toVisual(cursor).column();
        int requiredColumns = Math.max(layout.maxVisualLineWidth(engine.tabWidth()),
            (int) Math.min(Integer.MAX_VALUE, (long) cursorColumn + 1));
        int maxLeft = Math.max(0, requiredColumns - columns);
        int left = Math.min(state.getLeftVisualColumn(), maxLeft);
        if (cursorColumn < (long) left + margin) {
            left = Math.clamp(cursorColumn - margin, 0, maxLeft);
        } else if (cursorColumn > (long) left + columns - 1 - margin) {
            left = Math.clamp(cursorColumn - Math.max(0, columns - 1 - margin), 0, maxLeft);
        }
        coordinates.applyHorizontalOffset(state, left);
    }

    private void rememberNavigation() {
        seenNavigationRevision = engine.navigationRevision();
        seenModelRevision = engine.model().revision();
        seenCursor = engine.state().getCursor();
        seenAnchor = engine.state().getSelectionAnchor();
    }
}
