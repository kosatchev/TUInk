package dev.tuink.tui.ui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.render.EditorViewCoordinates;
import dev.tuink.render.EditorViewNavigation;
import dev.tuink.render.RenderPlan;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuiframe.scroll.ScrollInteraction;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * UI helper for the main editor text area.
 */
public final class EditorView {
    private static final int EDGE_AUTO_SCROLL_MAX_STEP = 6;
    private static final long EDGE_AUTO_SCROLL_INTERVAL_NANOS = 50_000_000L;
    private static final long DOUBLE_CLICK_WINDOW_NANOS = 450_000_000L;
    private final LongSupplier nanoClock;

    public enum ScrollAxis {
        NONE,
        VERTICAL,
        HORIZONTAL
    }

    private boolean mouseSelecting;
    private Position lastClickPosition;
    private long lastClickAtNanos;
    private int tabWidth = 4;
    private PointerEvent selectionScrollPointer;
    private long selectionScrollAtNanos;

    public EditorView() {
        this(System::nanoTime);
    }

    public EditorView(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public CursorPosition cursorFromPlan(RenderPlan plan, int contentTopRow) {
        int top = Math.max(1, contentTopRow);
        return new CursorPosition(plan.cursorRow() + top + 1, plan.cursorCol() + 1);
    }

    public void cancelMouseSelection() {
        mouseSelecting = false;
        selectionScrollPointer = null;
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
        selectionScrollPointer = null;
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
        boolean changed = dragSelection(mouse, model, state, plan, contentRows, engine, contentTopRow);
        scheduleSelectionScroll(mouse, plan, contentRows, contentTopRow, changed, nanoClock.getAsLong());
    }

    /** Next selection scroll runs on the UI thread, using the last drag coordinates. */
    public long nanosUntilSelectionScroll(long nowNanos) {
        return selectionScrollPointer == null ? -1L : Math.max(0L, selectionScrollAtNanos - nowNanos);
    }

    public boolean scrollSelectionIfDue(
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine,
        int contentTopRow,
        long nowNanos
    ) {
        if (selectionScrollPointer == null || nowNanos - selectionScrollAtNanos < 0L) {
            return false;
        }
        PointerEvent mouse = selectionScrollPointer;
        boolean changed = dragSelection(mouse, model, state, plan, contentRows, engine, contentTopRow);
        scheduleSelectionScroll(mouse, plan, contentRows, contentTopRow, changed, nowNanos);
        return changed;
    }

    private void scheduleSelectionScroll(
        PointerEvent mouse, RenderPlan plan, int contentRows, int contentTopRow, boolean changed, long nowNanos
    ) {
        int top = Math.max(1, contentTopRow);
        int bottom = top + contentRows - 1;
        boolean verticalEdge = mouse.row() <= top || mouse.row() >= bottom;
        boolean horizontalEdge = !plan.wrapLines()
            && (mouse.column() <= plan.gutterWidth() || mouse.column() >= plan.cols() - plan.scrollbarWidth() - 1);
        selectionScrollPointer = changed && contentRows > 0 && (verticalEdge || horizontalEdge) ? mouse : null;
        selectionScrollAtNanos = nowNanos + EDGE_AUTO_SCROLL_INTERVAL_NANOS;
    }

    private boolean dragSelection(
        PointerEvent mouse, TextModel model, EditorState state, RenderPlan plan, int contentRows,
        EditorEngine engine, int contentTopRow
    ) {
        Position oldCursor = state.getCursor();
        int oldTop = state.getTopLine();
        int oldSegment = state.getTopWrapSegment();
        int oldLeft = state.getLeftVisualColumn();
        int top = Math.max(1, contentTopRow);
        int bottom = top + Math.max(0, contentRows) - 1;
        PointerEvent effectiveMouse = mouse;
        autoScrollVerticalOnDrag(
            mouse,
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
        return !oldCursor.equals(state.getCursor()) || oldTop != state.getTopLine()
            || oldSegment != state.getTopWrapSegment() || oldLeft != state.getLeftVisualColumn();
    }

    private void ensureCursorVisible(EditorEngine engine, RenderPlan plan, int contentRows) {
        int contentColumns = Math.max(0, plan.cols() - plan.gutterWidth() - plan.scrollbarWidth());
        EditorViewNavigation.ensureCursorVisible(engine, contentRows, contentColumns, plan.wrapLines(), plan.wrapByWords());
    }

    private void autoScrollVerticalOnDrag(
        PointerEvent mouse,
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
        int visibleCols = Math.max(0, plan.cols() - plan.gutterWidth() - plan.scrollbarWidth());
        EditorViewCoordinates coordinates = coordinates(textLayout, plan, visibleCols);
        int currentTop = coordinates.rowOffset(EditorViewCoordinates.Top.from(state));
        int targetTop = ScrollInteraction.scrollBy(currentTop, delta, contentRows, coordinates.totalRows());
        coordinates.applyTop(state, coordinates.topAtRow(targetTop));
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
        long now = nanoClock.getAsLong();
        boolean doubleClick = lastClickPosition != null
            && hit.line() == lastClickPosition.line()
            && Math.abs(hit.col() - lastClickPosition.col()) <= 1
            && now - lastClickAtNanos <= DOUBLE_CLICK_WINDOW_NANOS;
        lastClickPosition = hit;
        lastClickAtNanos = now;
        return doubleClick;
    }

    public void handleMouseRelease(EditorEngine engine) {
        selectionScrollPointer = null;
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
        if (textLayout.model() != model) {
            throw new IllegalArgumentException("textLayout must be bound to model");
        }
        int columns = Math.max(0, plan.cols() - plan.gutterWidth() - plan.scrollbarWidth());
        return coordinates(textLayout, plan, columns).positionInViewport(state, contentRows,
            mouse.row() - Math.max(1, contentTopRow), mouse.column() - plan.gutterWidth());
    }

    private EditorViewCoordinates coordinates(EditorTextLayout layout, RenderPlan plan, int columns) {
        return new EditorViewCoordinates(layout, columns, plan.wrapLines(), plan.wrapByWords(), tabWidth);
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
        if (textLayout.model() != model) {
            throw new IllegalArgumentException("textLayout must be bound to model");
        }
        EditorScrollbars scrollbars = EditorScrollbars.from(plan, rows, cols, contentTopRow);
        Point point = new Point(mouseCol, mouseRow);
        if (axis != ScrollAxis.HORIZONTAL && scrollbars.vertical() != null) {
            Scrollbar.Press press = scrollbarPointer(
                scrollbars.vertical(), point, axis == ScrollAxis.VERTICAL, dragThumbOffset
            );
            if (press != null) {
                int targetTop = press.offset();
                int visibleCols = Math.max(0, cols - plan.gutterWidth() - plan.scrollbarWidth());
                EditorViewCoordinates coordinates = coordinates(textLayout, plan, visibleCols);
                coordinates.applyTop(state, coordinates.topAtRow(targetTop));
                return new ScrollPointerResult(
                    true,
                    press.drag() != null ? ScrollAxis.VERTICAL : ScrollAxis.NONE,
                    press.drag() != null ? press.drag().grabOffset() : 0
                );
            }
        }

        if (axis != ScrollAxis.VERTICAL && scrollbars.horizontal() != null) {
            Scrollbar.Press press = scrollbarPointer(
                scrollbars.horizontal(), point, axis == ScrollAxis.HORIZONTAL, dragThumbOffset
            );
            if (press != null) {
                coordinates(textLayout, plan, cols).applyHorizontalOffset(state, press.offset());
                return new ScrollPointerResult(
                    true,
                    press.drag() != null ? ScrollAxis.HORIZONTAL : ScrollAxis.NONE,
                    press.drag() != null ? press.drag().grabOffset() : 0
                );
            }
        }

        return new ScrollPointerResult(false, ScrollAxis.NONE, 0);
    }

    private static Scrollbar.Press scrollbarPointer(
        Scrollbar scrollbar, Point point, boolean dragging, int dragThumbOffset
    ) {
        if (dragging) {
            Scrollbar.Drag drag = new Scrollbar.Drag(scrollbar.axis(), dragThumbOffset);
            return new Scrollbar.Press(scrollbar.drag(point, drag), drag);
        }
        return scrollbar.press(point).orElse(null);
    }

    public record CursorPosition(int row, int col) {
    }

    public record ScrollPointerResult(boolean consumed, ScrollAxis dragAxis, int dragThumbOffset) {
    }

}
