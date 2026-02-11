package dev.tuink.tui.ui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.ViewportMetrics;
import dev.tuink.core.EditorViewportBehavior;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.ScrollInteraction;
import dev.tuink.tui.KeyEvent;
import dev.tuink.tui.render.HorizontalScrollbar;
import dev.tuink.tui.render.VerticalScrollbar;

/**
 * UI helper for the main editor text area.
 */
public final class EditorView {
    public enum ScrollAxis {
        NONE,
        VERTICAL,
        HORIZONTAL
    }

    private final int contentTopRow;
    private boolean mouseSelecting;
    private int tabWidth = 4;

    public EditorView() {
        this(1);
    }

    public EditorView(int contentTopRow) {
        this.contentTopRow = Math.max(1, contentTopRow);
    }

    public CursorPosition cursorFromPlan(RenderPlan plan) {
        return new CursorPosition(plan.cursorRow() + contentTopRow + 1, plan.cursorCol() + 1);
    }

    public CursorPosition cursorFromPlan(RenderPlan plan, int contentTopRow) {
        int top = Math.max(1, contentTopRow);
        return new CursorPosition(plan.cursorRow() + top + 1, plan.cursorCol() + 1);
    }

    public void cancelMouseSelection() {
        mouseSelecting = false;
    }

    public void setTabWidth(int tabWidth) {
        this.tabWidth = tabWidth <= 2 ? 2 : 4;
    }

    public void handleMousePress(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine
    ) {
        handleMousePress(mouse, model, state, plan, contentRows, engine, contentTopRow);
    }

    public void handleMousePress(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine,
        int contentTopRow
    ) {
        Position hit = mouseToTextPosition(mouse, model, state, plan, contentRows, contentTopRow);
        if (hit != null) {
            mouseSelecting = true;
            state.setSelectionAnchor(hit);
            state.setCursor(hit);
        } else {
            mouseSelecting = false;
        }
        engine.ensureCursorVisible(contentRows);
    }

    public void handleMouseDrag(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine
    ) {
        handleMouseDrag(mouse, model, state, plan, contentRows, engine, contentTopRow);
    }

    public void handleMouseDrag(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine,
        int contentTopRow
    ) {
        if (!mouseSelecting || mouse.button() != KeyEvent.MouseButton.LEFT) {
            return;
        }
        Position hit = mouseToTextPosition(mouse, model, state, plan, contentRows, contentTopRow);
        if (hit != null) {
            if (state.getSelectionAnchor() == null) {
                state.setSelectionAnchor(state.getCursor());
            }
            state.setCursor(hit);
            engine.ensureCursorVisible(contentRows);
        }
    }

    public void handleMouseRelease(EditorState state) {
        if (!mouseSelecting) {
            return;
        }
        mouseSelecting = false;
        if (state.getSelectionAnchor() != null && state.getSelectionAnchor().equals(state.getCursor())) {
            state.clearSelection();
        }
    }

    public Position mouseToTextPosition(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows
    ) {
        return mouseToTextPosition(mouse, model, state, plan, contentRows, contentTopRow);
    }

    public Position mouseToTextPosition(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        int contentTopRow
    ) {
        if (plan.wrapLines()) {
            int top = Math.max(1, contentTopRow);
            if (mouse.row() < top || mouse.row() >= top + contentRows) {
                return null;
            }
            int visibleCols = Math.max(0, plan.cols() - plan.gutterWidth() - plan.scrollbarWidth());
            int targetVisualCol = mouse.col() < plan.gutterWidth() ? 0 : mouse.col() - plan.gutterWidth();
            EditorViewportBehavior.WrapTop wrapTop = EditorViewportBehavior.normalizeWrapTop(
                model,
                state.getTopLine(),
                state.getTopWrapSegment(),
                visibleCols,
                plan.wrapByWords(),
                tabWidth
            );
            int remainingWrappedRows = wrapTop.segmentOffset() + (mouse.row() - top);
            int line = wrapTop.line();
            while (line < model.lineCount()) {
                String lineText = model.getLine(line);
                var starts = model.wrapStarts(line, visibleCols, plan.wrapByWords(), tabWidth);
                if (remainingWrappedRows < starts.size()) {
                    int segmentStart = starts.get(Math.max(0, remainingWrappedRows));
                    int col = ViewportMetrics.visualToTextColumn(
                        lineText,
                        segmentStart,
                        Math.max(0, targetVisualCol),
                        tabWidth
                    );
                    return model.clamp(new Position(line, Math.max(0, col)));
                }
                remainingWrappedRows -= starts.size();
                line++;
            }
            return model.clamp(new Position(Math.max(0, model.lineCount() - 1), 0));
        }
        return EditorViewportBehavior.pointerToTextPosition(
            model,
            state,
            contentTopRow,
            contentRows,
            mouse.row(),
            mouse.col(),
            plan.gutterWidth(),
            tabWidth
        );
    }

    public ScrollPointerResult handleScrollbarPointer(
        int mouseRow,
        int mouseCol,
        int rows,
        int cols,
        int contentTopRow,
        RenderPlan plan,
        TextModel model,
        EditorState state
    ) {
        return handleScrollbarPointer(mouseRow, mouseCol, rows, cols, contentTopRow, plan, model, state, ScrollAxis.NONE, 0);
    }

    public ScrollPointerResult handleScrollbarPointer(
        int mouseRow,
        int mouseCol,
        int rows,
        int cols,
        int contentTopRow,
        RenderPlan plan,
        TextModel model,
        EditorState state,
        ScrollAxis axis
    ) {
        return handleScrollbarPointer(mouseRow, mouseCol, rows, cols, contentTopRow, plan, model, state, axis, 0);
    }

    public ScrollPointerResult handleScrollbarPointer(
        int mouseRow,
        int mouseCol,
        int rows,
        int cols,
        int contentTopRow,
        RenderPlan plan,
        TextModel model,
        EditorState state,
        ScrollAxis axis,
        int dragThumbOffset
    ) {
        if (axis != ScrollAxis.HORIZONTAL && plan.scrollbarWidth() > 0 && cols > 0 && rows > 2) {
            int barCol = cols - 1;
            int trackTop = contentTopRow;
            int trackHeight = Math.min(plan.contentRows(), Math.max(0, rows - contentTopRow - 1));
            int verticalScroll = Math.max(0, plan.topLine());
            int verticalTotal = Math.max(1, plan.totalLines());
            int verticalThumbStart = trackTop + ScrollInteraction.thumbOffset(trackHeight, verticalTotal, verticalScroll);
            boolean draggingVertical = axis == ScrollAxis.VERTICAL;
            ScrollInteraction.Hit verticalHit = draggingVertical
                ? ScrollInteraction.Hit.THUMB
                : VerticalScrollbar.hit(
                    mouseRow,
                    mouseCol,
                    trackTop,
                    barCol,
                    trackHeight,
                    verticalTotal,
                    verticalScroll
                );
            if (trackHeight > 0 && verticalHit != ScrollInteraction.Hit.NONE) {
                int targetTop = verticalHit == ScrollInteraction.Hit.TRACK
                    ? VerticalScrollbar.pageScrollFromTrackClick(
                        verticalScroll,
                        mouseRow,
                        trackTop,
                        trackHeight,
                        verticalTotal
                    )
                    : (draggingVertical
                        ? ScrollInteraction.scrollFromThumbStart(mouseRow - trackTop - dragThumbOffset, trackHeight, verticalTotal)
                        : verticalScroll);
                if (plan.wrapLines()) {
                    int visibleCols = Math.max(0, cols - plan.gutterWidth() - plan.scrollbarWidth());
                    EditorViewportBehavior.WrapTop target = EditorViewportBehavior.wrapTopFromWrappedOffset(
                        model,
                        targetTop,
                        visibleCols,
                        plan.wrapByWords(),
                        tabWidth
                    );
                    EditorViewportBehavior.applyWrapTop(state, target);
                } else {
                    EditorViewportBehavior.applyVerticalScrollKeepCursor(state, model, targetTop);
                }
                int thumbGrabOffset = verticalHit == ScrollInteraction.Hit.THUMB
                    ? Math.max(0, mouseRow - verticalThumbStart)
                    : 0;
                return new ScrollPointerResult(
                    true,
                    verticalHit == ScrollInteraction.Hit.THUMB ? ScrollAxis.VERTICAL : ScrollAxis.NONE,
                    thumbGrabOffset
                );
            }
        }

        if (axis != ScrollAxis.VERTICAL && rows > 2 && cols > 0) {
            int visibleCols = Math.max(0, cols - plan.gutterWidth() - plan.scrollbarWidth());
            int row = contentTopRow + plan.contentRows();
            boolean hasHorizontal = visibleCols > 0 && (plan.leftVisualColumn() > 0 || plan.totalColumns() > visibleCols);
            int horizontalTotal = Math.max(visibleCols, plan.totalColumns());
            int horizontalThumbStart = plan.gutterWidth() + ScrollInteraction.thumbOffset(
                visibleCols,
                horizontalTotal,
                plan.leftVisualColumn()
            );
            boolean draggingHorizontal = axis == ScrollAxis.HORIZONTAL;
            ScrollInteraction.Hit horizontalHit = !hasHorizontal ? ScrollInteraction.Hit.NONE
                : (draggingHorizontal
                    ? ScrollInteraction.Hit.THUMB
                    : HorizontalScrollbar.hit(
                        mouseRow,
                        mouseCol,
                        row,
                        plan.gutterWidth(),
                        visibleCols,
                        horizontalTotal,
                        plan.leftVisualColumn()
                    ));
            if (hasHorizontal && row >= contentTopRow && row < rows - 1 && horizontalHit != ScrollInteraction.Hit.NONE) {
                int targetLeft = horizontalHit == ScrollInteraction.Hit.TRACK
                    ? HorizontalScrollbar.pageScrollFromTrackClick(
                        plan.leftVisualColumn(),
                        mouseCol,
                        plan.gutterWidth(),
                        visibleCols,
                        horizontalTotal
                    )
                    : (draggingHorizontal
                        ? ScrollInteraction.scrollFromThumbStart(
                            mouseCol - plan.gutterWidth() - dragThumbOffset,
                            visibleCols,
                            horizontalTotal
                        )
                        : plan.leftVisualColumn());
                EditorViewportBehavior.applyHorizontalScrollVisual(state, model, targetLeft, tabWidth);
                int thumbGrabOffset = horizontalHit == ScrollInteraction.Hit.THUMB
                    ? Math.max(0, mouseCol - horizontalThumbStart)
                    : 0;
                return new ScrollPointerResult(
                    true,
                    horizontalHit == ScrollInteraction.Hit.THUMB ? ScrollAxis.HORIZONTAL : ScrollAxis.NONE,
                    thumbGrabOffset
                );
            }
        }

        return new ScrollPointerResult(false, ScrollAxis.NONE, 0);
    }

    public record CursorPosition(int row, int col) {
    }

    public record ScrollPointerResult(boolean consumed, ScrollAxis dragAxis, int dragThumbOffset) {
    }

}
