package dev.tuink.render;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.render.EditorViewCoordinates.Geometry;
import dev.tuink.render.EditorViewCoordinates.Top;
import dev.tuink.render.surface.TextSurfaceInteraction;
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
        var movement = TextSurfaceInteraction.move(coordinates,
            new TextSurfaceInteraction.Visual(previous.row(), previous.column()),
            delta < 0 ? TextSurfaceInteraction.Move.PAGE_UP : TextSurfaceInteraction.Move.PAGE_DOWN,
            Math.abs(delta), preferredColumn);
        preferredColumn = movement.preferredColumn();
        Position target = movement.visual().row() == previous.row() ? state.getCursor()
            : TextSurfaceInteraction.target(coordinates, (position, ceil) -> model.clamp(position), movement.visual(), false, delta > 0);
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
        int targetTop = TextSurfaceInteraction.pageTop(oldTop, delta, coordinates.totalRows(), Math.abs(delta), wrapLines);
        coordinates.applyTop(state, coordinates.topAtRow(targetTop));
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
        var visual = coordinates.visualPosition(cursor);
        int requiredColumns = wrapLines || columns <= 0 ? 0 : Math.max(layout.maxVisualLineWidth(engine.tabWidth()),
            (int) Math.min(Integer.MAX_VALUE, (long) visual.column() + 1));
        var viewport = new TextSurfaceInteraction.Viewport(coordinates.rowOffset(Top.from(state)),
            state.getLeftVisualColumn(), rows, columns);
        var visible = TextSurfaceInteraction.reveal(viewport, visual, coordinates.totalRows(), requiredColumns,
            H_SCROLL_MARGIN, wrapLines, !wrapLines && columns > 0);
        coordinates.applyTop(state, coordinates.topAtRow(visible.top()));
        if (wrapLines) coordinates.applyHorizontalOffset(state, 0);
        else if (columns > 0) coordinates.applyHorizontalOffset(state, visible.left());
    }

    private void rememberNavigation() {
        seenNavigationRevision = engine.navigationRevision();
        seenModelRevision = engine.model().revision();
        seenCursor = engine.state().getCursor();
        seenAnchor = engine.state().getSelectionAnchor();
    }
}
