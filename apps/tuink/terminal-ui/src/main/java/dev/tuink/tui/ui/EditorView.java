package dev.tuink.tui.ui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.EditorViewportBehavior;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.render.RenderPlan;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.scroll.ScrollInteraction;
import dev.tuiframe.scroll.HorizontalScrollbar;
import dev.tuiframe.scroll.VerticalScrollbar;

/**
 * UI helper for the main editor text area.
 */
public final class EditorView {
    private static final int EDGE_AUTO_SCROLL_MAX_STEP = 6;
    private static final long DOUBLE_CLICK_WINDOW_NANOS = 450_000_000L;

    public enum ScrollAxis {
        NONE,
        VERTICAL,
        HORIZONTAL
    }

    private boolean mouseSelecting;
    private Position lastClickPosition;
    private long lastClickAtNanos;
    private int tabWidth = 4;

    public CursorPosition cursorFromPlan(RenderPlan plan, int contentTopRow) {
        int top = Math.max(1, contentTopRow);
        return new CursorPosition(plan.cursorRow() + top + 1, plan.cursorCol() + 1);
    }

    public void cancelMouseSelection() {
        mouseSelecting = false;
        lastClickPosition = null;
        lastClickAtNanos = 0L;
    }

    public void setTabWidth(int tabWidth) {
        this.tabWidth = tabWidth <= 2 ? 2 : 4;
    }

    public boolean handleMousePress(
        PointerEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine,
        int contentTopRow,
        boolean extendSelection
    ) {
        boolean selectedWordByDoubleClick = false;
        Position hit = mouseToTextPosition(
            mouse,
            model,
            engine.textLayout(),
            state,
            plan,
            contentRows,
            contentTopRow
        );
        if (hit != null) {
            mouseSelecting = true;
            if (extendSelection) {
                resetClickSequence();
                engine.extendSelectionTo(hit);
            } else if (isDoubleClick(hit) && engine.selectWordAt(hit)) {
                selectedWordByDoubleClick = true;
            } else {
                engine.beginSelectionAt(hit);
            }
        } else {
            mouseSelecting = false;
            lastClickPosition = null;
            lastClickAtNanos = 0L;
        }
        ensureCursorVisible(engine, plan, contentRows);
        return selectedWordByDoubleClick;
    }

    private void resetClickSequence() {
        lastClickPosition = null;
        lastClickAtNanos = 0L;
    }

    public void handleMouseDrag(
        PointerEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine,
        int contentTopRow
    ) {
        if (!mouseSelecting || mouse.button() != PointerEvent.Button.LEFT) {
            return;
        }
        int top = Math.max(1, contentTopRow);
        int bottom = top + Math.max(0, contentRows) - 1;
        PointerEvent effectiveMouse = mouse;
        autoScrollVerticalOnDrag(
            mouse,
            model,
            engine.textLayout(),
            state,
            plan,
            contentRows,
            contentTopRow
        );
        if (mouse.row() < top || mouse.row() > bottom) {
            int clampedRow = Math.max(
                top,
                Math.min(mouse.row(), bottom)
            );
            effectiveMouse = new PointerEvent(
                clampedRow,
                mouse.column(),
                mouse.kind(),
                mouse.button(),
                mouse.modifiers()
            );
        }
        Position hit = mouseToTextPosition(
            effectiveMouse,
            model,
            engine.textLayout(),
            state,
            plan,
            contentRows,
            contentTopRow
        );
        if (hit != null) {
            engine.extendSelectionTo(hit);
            ensureCursorVisible(engine, plan, contentRows);
        }
    }

    private void ensureCursorVisible(EditorEngine engine, RenderPlan plan, int contentRows) {
        int contentColumns = Math.max(0, plan.cols() - plan.gutterWidth() - plan.scrollbarWidth());
        engine.ensureCursorVisible(contentRows, contentColumns, plan.wrapLines(), plan.wrapByWords());
    }

    private void autoScrollVerticalOnDrag(
        PointerEvent mouse,
        TextModel model,
        EditorTextLayout textLayout,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        int contentTopRow
    ) {
        if (contentRows <= 0) {
            return;
        }
        int top = Math.max(1, contentTopRow);
        int bottom = top + contentRows - 1;
        int delta = autoScrollDeltaForMouseRow(mouse.row(), top, bottom);
        if (delta == 0) {
            return;
        }
        if (plan.wrapLines()) {
            int visibleCols = Math.max(0, plan.cols() - plan.gutterWidth() - plan.scrollbarWidth());
            int currentWrappedTop = Math.max(0, plan.topLine());
            int maxWrappedTop = Math.max(0, plan.totalLines() - Math.max(1, contentRows));
            int targetWrappedTop = Math.max(0, Math.min(maxWrappedTop, currentWrappedTop + delta));
            if (targetWrappedTop == currentWrappedTop) {
                return;
            }
            EditorViewportBehavior.WrapTop targetTop = EditorViewportBehavior.wrapTopFromWrappedOffsetNear(
                model,
                textLayout,
                state.getTopLine(),
                state.getTopWrapSegment(),
                currentWrappedTop,
                targetWrappedTop,
                visibleCols,
                plan.wrapByWords(),
                tabWidth
            );
            EditorViewportBehavior.applyWrapTop(state, targetTop);
            return;
        }
        int currentTop = state.getTopLine();
        int maxTop = Math.max(0, model.lineCount() - Math.max(1, contentRows));
        int targetTop = Math.max(0, Math.min(maxTop, currentTop + delta));
        if (targetTop != currentTop) {
            state.setTopLine(targetTop);
        }
    }

    private int autoScrollDeltaForMouseRow(int mouseRow, int top, int bottom) {
        if (mouseRow < top) {
            return -Math.min(EDGE_AUTO_SCROLL_MAX_STEP, Math.max(1, top - mouseRow));
        }
        if (mouseRow > bottom) {
            return Math.min(EDGE_AUTO_SCROLL_MAX_STEP, Math.max(1, mouseRow - bottom));
        }
        if (mouseRow == top) {
            return -1;
        }
        if (mouseRow == bottom) {
            return 1;
        }
        return 0;
    }

    private boolean isDoubleClick(Position hit) {
        long now = System.nanoTime();
        boolean doubleClick = lastClickPosition != null
            && hit.line() == lastClickPosition.line()
            && Math.abs(hit.col() - lastClickPosition.col()) <= 1
            && now - lastClickAtNanos <= DOUBLE_CLICK_WINDOW_NANOS;
        lastClickPosition = hit;
        lastClickAtNanos = now;
        return doubleClick;
    }

    public void handleMouseRelease(EditorEngine engine) {
        if (!mouseSelecting) {
            return;
        }
        mouseSelecting = false;
        engine.finishSelection();
    }

    public boolean isMouseSelecting() {
        return mouseSelecting;
    }

    public Position mouseToTextPosition(
        PointerEvent mouse,
        TextModel model,
        EditorTextLayout textLayout,
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
            int targetVisualCol = mouse.column() < plan.gutterWidth() ? 0 : mouse.column() - plan.gutterWidth();
            EditorViewportBehavior.WrapTop wrapTop = EditorViewportBehavior.normalizeWrapTop(
                model,
                textLayout,
                state.getTopLine(),
                state.getTopWrapSegment(),
                visibleCols,
                plan.wrapByWords(),
                tabWidth
            );
            int remainingWrappedRows = wrapTop.segmentOffset() + (mouse.row() - top);
            int line = wrapTop.line();
            while (line < model.lineCount()) {
                var starts = textLayout.wrapStarts(line, visibleCols, plan.wrapByWords(), tabWidth);
                if (remainingWrappedRows < starts.size()) {
                    int segmentStart = starts.get(Math.max(0, remainingWrappedRows));
                    int col = EditorViewportBehavior.visualToTextColumn(
                        textLayout,
                        line,
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
            textLayout,
            state,
            contentTopRow,
            contentRows,
            mouse.row(),
            mouse.column(),
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
        EditorTextLayout textLayout,
        EditorState state
    ) {
        return handleScrollbarPointer(
            mouseRow,
            mouseCol,
            rows,
            cols,
            contentTopRow,
            plan,
            model,
            textLayout,
            state,
            ScrollAxis.NONE,
            0
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
        EditorTextLayout textLayout,
        EditorState state,
        ScrollAxis axis
    ) {
        return handleScrollbarPointer(
            mouseRow,
            mouseCol,
            rows,
            cols,
            contentTopRow,
            plan,
            model,
            textLayout,
            state,
            axis,
            0
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
        EditorTextLayout textLayout,
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
                        textLayout,
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
                EditorViewportBehavior.applyHorizontalScrollVisual(
                    state,
                    model,
                    textLayout,
                    targetLeft,
                    tabWidth
                );
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
