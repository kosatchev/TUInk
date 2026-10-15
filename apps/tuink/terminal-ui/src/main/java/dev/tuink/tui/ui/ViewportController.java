package dev.tuink.tui.ui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.render.EditorViewCoordinates;
import dev.tuink.render.EditorViewCoordinates.Geometry;
import dev.tuink.render.EditorViewCoordinates.Top;
import dev.tuink.render.EditorViewNavigation;
import dev.tuiframe.scroll.ScrollInteraction;

/**
 * Tracks viewport interaction mode and applies cursor visibility policy.
 */
public final class ViewportController {
    private boolean manualHorizontalPan;
    private boolean manualVerticalPan;
    private ViewportSnapshot previousViewport;

    /** Applied by a completed command's recipient, after local policy and mutation. */
    public enum CursorPolicy {
        PRESERVE,
        FOLLOW_CURSOR,
        PAGE_SCROLL
    }

    public void onCommandResult(CursorPolicy policy) {
        switch (policy) {
            case PRESERVE -> { }
            case FOLLOW_CURSOR -> {
                manualHorizontalPan = false;
                manualVerticalPan = false;
            }
            case PAGE_SCROLL -> {
                manualHorizontalPan = false;
                manualVerticalPan = true;
            }
        }
    }

    /** Recovery restores a reading position before the first visible frame. */
    public void onRecoveredViewport() {
        manualHorizontalPan = true;
        manualVerticalPan = true;
        previousViewport = null;
    }

    public void onManualVerticalPan() {
        manualVerticalPan = true;
    }

    public void onEditorTextInteraction() {
        // Returning focus to the text area should restore cursor-follow behavior.
        onCommandResult(CursorPolicy.FOLLOW_CURSOR);
    }

    public void onScrollbarInteraction(
        EditorView.ScrollPointerResult scrollPointer,
        int leftVisualBefore,
        int leftVisualAfter,
        int topLineBefore,
        int topLineAfter
    ) {
        if (scrollPointer == null || !scrollPointer.consumed()) {
            return;
        }
        if (scrollPointer.dragAxis() == EditorView.ScrollAxis.HORIZONTAL || leftVisualAfter != leftVisualBefore) {
            manualHorizontalPan = true;
        }
        if (scrollPointer.dragAxis() == EditorView.ScrollAxis.VERTICAL || topLineAfter != topLineBefore) {
            manualVerticalPan = true;
        }
    }

    public void ensureCursorVisible(
        EditorEngine engine,
        int contentRows,
        int contentCols,
        boolean wrapLines,
        boolean wrapByWords
    ) {
        ensureCursorVisible(engine, contentRows, contentCols, wrapLines, wrapByWords, 4);
    }

    public void ensureCursorVisible(
        EditorEngine engine,
        int contentRows,
        int contentCols,
        boolean wrapLines,
        boolean wrapByWords,
        int tabWidth
    ) {
        // A hidden editor has no new visual geometry. Keep the last visible
        // layout so its text anchor survives a zero-sized intermediate frame.
        if (contentRows <= 0 || contentCols <= 0) {
            return;
        }
        TextModel model = engine.model();
        EditorState state = engine.state();
        EditorTextLayout layout = engine.textLayout();
        int safeTabWidth = tabWidth <= 2 ? 2 : 4;
        Geometry geometry = new Geometry(contentCols, wrapLines, wrapByWords, safeTabWidth);
        EditorViewCoordinates coordinates = new EditorViewCoordinates(layout, geometry);
        Position anchor = null;
        if (previousViewport != null && previousViewport.model() == model && previousViewport.state() == state) {
            // Mouse scrolling changes state between render passes. Read that
            // position using the geometry in which the gesture took place.
            anchor = previousViewport.revision() == model.revision() && previousViewport.top().equals(Top.from(state))
                ? previousViewport.anchor()
                : new EditorViewCoordinates(layout, previousViewport.geometry()).anchor(Top.from(state));
            if (!geometry.equals(previousViewport.geometry())) {
                coordinates.applyTop(state, coordinates.topForAnchor(anchor));
            }
        }
        if (!manualVerticalPan) {
            EditorViewNavigation.ensureCursorVisible(engine,
                contentRows,
                wrapLines ? contentCols : (manualHorizontalPan ? -1 : contentCols),
                wrapLines,
                wrapByWords
            );
        }
        if (wrapLines) {
            Top top = coordinates.normalizeTop(Top.from(state));
            int offset = coordinates.rowOffset(top);
            int total = coordinates.totalRows();
            int bounded = ScrollInteraction.clampOffset(offset, contentRows, total);
            coordinates.applyTop(state, bounded == offset ? top : coordinates.topAtRow(bounded));
            state.setLeftColumn(0);
            state.setLeftVisualColumn(0);
            rememberViewport(model, coordinates, state, geometry, anchor);
            return;
        }

        state.setTopLine(ScrollInteraction.clampOffset(state.getTopLine(), contentRows, model.lineCount()));
        state.setTopWrapSegment(0);
        Position cursor = model.clamp(state.getCursor());
        int requiredColumns = Math.max(
            layout.maxVisualLineWidth(safeTabWidth),
            layout.visualColumnAt(cursor.line(), cursor.col(), safeTabWidth) + 1
        );
        int left = ScrollInteraction.clampOffset(state.getLeftVisualColumn(), contentCols, requiredColumns);
        if (left != state.getLeftVisualColumn()) {
            coordinates.applyHorizontalOffset(state, left);
        }
        rememberViewport(model, coordinates, state, geometry, anchor);
    }

    private void rememberViewport(
        TextModel model,
        EditorViewCoordinates coordinates,
        EditorState state,
        Geometry geometry,
        Position anchor
    ) {
        Top top = Top.from(state);
        // Keep the original text offset when a wider row starts before it.
        // Repeated resizes can then return to the same text without drift.
        // Cursor following and EOF clamping establish a new visible anchor.
        if (anchor == null || !top.equals(coordinates.topForAnchor(anchor))) {
            anchor = coordinates.anchor(top);
        }
        previousViewport = new ViewportSnapshot(model, state, model.revision(), geometry, top, anchor);
    }

    private record ViewportSnapshot(
        TextModel model,
        EditorState state,
        long revision,
        Geometry geometry,
        Top top,
        Position anchor
    ) {
    }
}
